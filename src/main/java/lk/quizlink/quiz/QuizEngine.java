package lk.quizlink.quiz;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.QuestionView;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;

/**
 * Runs quiz sessions for all rooms without blocking the NIO Selector thread.
 *
 * <p>Commands that change quiz state ({@link #startQuiz}, {@link #submitAnswer}, {@link #endQuiz})
 * run on a fixed worker pool and return a {@link CompletableFuture}. Question timeouts and the
 * pause between questions run on a single scheduler thread. Each room is guarded by its own lock,
 * so rooms progress independently and events for one room are published in order.
 *
 * <p>Typical server integration: on SUBMIT_ANSWER, call {@code submitAnswer(...)} and, when the
 * future completes, enqueue {@link QuizMessages#answerResult} (or {@link QuizMessages#error}) for
 * the sender and wake the Selector. Broadcast events arrive through the {@link QuizEventSink}.
 */
public final class QuizEngine implements AutoCloseable {
    public static final int DEFAULT_WORKER_THREADS = 4;
    public static final Duration DEFAULT_INTERMISSION = Duration.ofSeconds(3);

    private static final Logger LOGGER = Logger.getLogger(QuizEngine.class.getName());

    private final QuizEventSink sink;
    private final Duration intermission;
    private final Clock clock;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();

    public QuizEngine(QuizEventSink sink) {
        this(sink, DEFAULT_WORKER_THREADS, DEFAULT_INTERMISSION, Clock.systemUTC());
    }

    /**
     * @param workerThreads size of the answer-processing pool
     * @param intermission  pause between a question's leaderboard and the next question
     * @param clock         time source for question deadlines
     */
    public QuizEngine(QuizEventSink sink, int workerThreads, Duration intermission, Clock clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.intermission = Objects.requireNonNull(intermission, "intermission");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be at least 1");
        }
        if (intermission.isNegative()) {
            throw new IllegalArgumentException("intermission must not be negative");
        }
        this.workers = Executors.newFixedThreadPool(workerThreads, daemonThreads("quiz-worker"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreads("quiz-timer"));
    }

    /** Registers the quiz definition for a newly created room. */
    public void createSession(String roomCode, Quiz quiz) {
        Room room = new Room(new QuizSession(roomCode, quiz, clock));
        if (rooms.putIfAbsent(roomCode, room) != null) {
            throw new QuizException(QuizError.INVALID_ROOM, "A quiz session already exists for room " + roomCode);
        }
    }

    public boolean hasSession(String roomCode) {
        return roomCode != null && rooms.containsKey(roomCode);
    }

    /** Called by the lobby once a participant has joined the room. */
    public void addParticipant(String roomCode, Participant participant) {
        Room room = room(roomCode);
        synchronized (room) {
            room.session.addParticipant(participant);
        }
    }

    /** Called when a participant leaves or disconnects. May close the open question early. */
    public void removeParticipant(String roomCode, String participantId) {
        Room room = room(roomCode);
        synchronized (room) {
            room.session.removeParticipant(participantId);
            closeEarlyIfEveryoneAnswered(room);
        }
    }

    /** Host command: sends the first question and starts its timer. */
    public CompletableFuture<Void> startQuiz(String roomCode) {
        return onWorker(() -> {
            Room room = room(roomCode);
            synchronized (room) {
                if (room.session.status() != SessionStatus.WAITING) {
                    throw new QuizException(QuizError.INVALID_MESSAGE, "The quiz has already started");
                }
                if (room.session.activeParticipantCount() == 0) {
                    throw new QuizException(QuizError.INVALID_MESSAGE, "Cannot start a quiz without participants");
                }
                openNextQuestion(room);
            }
            return null;
        });
    }

    /**
     * Student command. {@code participantId} must be the ID the server associated with the sending
     * connection, never a client-supplied value.
     */
    public CompletableFuture<AnswerResult> submitAnswer(String roomCode, String participantId,
                                                        String questionId, int selectedOptionIndex) {
        return onWorker(() -> {
            requireText(participantId, "participantId");
            requireText(questionId, "questionId");
            Room room = room(roomCode);
            synchronized (room) {
                AnswerResult result = room.session.submitAnswer(participantId, questionId, selectedOptionIndex);
                if (result.accepted()) {
                    closeEarlyIfEveryoneAnswered(room);
                }
                return result;
            }
        });
    }

    /** Host command: ends the quiz immediately and returns the final leaderboard. Idempotent. */
    public CompletableFuture<List<LeaderboardEntry>> endQuiz(String roomCode) {
        return onWorker(() -> {
            Room room = room(roomCode);
            synchronized (room) {
                finish(room);
                return room.session.leaderboard();
            }
        });
    }

    public List<LeaderboardEntry> leaderboard(String roomCode) {
        return room(roomCode).session.leaderboard();
    }

    public SessionStatus status(String roomCode) {
        return room(roomCode).session.status();
    }

    /** Discards a room's session and cancels its timers, for example when the room closes. */
    public void removeSession(String roomCode) {
        Room room = roomCode == null ? null : rooms.remove(roomCode);
        if (room != null) {
            synchronized (room) {
                cancelPendingTask(room);
            }
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
        scheduler.shutdownNow();
    }

    // --- Room transitions: every method below is called while holding the room's lock. ---

    private void openNextQuestion(Room room) {
        QuestionView question = room.session.openNextQuestion();
        int index = room.session.currentQuestionIndex();
        publish(room, QuizMessages.question(room.code(), question, index + 1, room.session.totalQuestions()));
        schedule(room, Duration.ofSeconds(question.timeLimitSeconds()), r -> closeQuestion(r, index));
    }

    private void closeQuestion(Room room, int questionIndex) {
        if (!room.session.closeQuestion(questionIndex)) {
            return;
        }
        cancelPendingTask(room);
        if (!room.session.hasNextQuestion()) {
            finish(room);
            return;
        }
        publish(room, QuizMessages.leaderboard(room.code(), room.session.leaderboard()));
        schedule(room, intermission, r -> advance(r, questionIndex));
    }

    private void advance(Room room, int closedQuestionIndex) {
        if (room.session.status() == SessionStatus.QUESTION_CLOSED
                && room.session.currentQuestionIndex() == closedQuestionIndex) {
            openNextQuestion(room);
        }
    }

    private void finish(Room room) {
        if (room.session.end()) {
            cancelPendingTask(room);
            publish(room, QuizMessages.quizEnded(room.code(), room.session.leaderboard()));
        }
    }

    /**
     * Closing runs as a separate scheduler task rather than inline, so the final ANSWER_RESULT
     * reply is normally queued before the LEADERBOARD broadcast.
     */
    private void closeEarlyIfEveryoneAnswered(Room room) {
        if (room.session.allActiveParticipantsAnswered()) {
            int index = room.session.currentQuestionIndex();
            runOnScheduler(room, () -> scheduler.execute(guarded(room, r -> closeQuestion(r, index))));
        }
    }

    private void schedule(Room room, Duration delay, Consumer<Room> action) {
        cancelPendingTask(room);
        runOnScheduler(room, () -> room.pendingTask =
                scheduler.schedule(guarded(room, action), delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    private static void cancelPendingTask(Room room) {
        if (room.pendingTask != null) {
            room.pendingTask.cancel(false);
            room.pendingTask = null;
        }
    }

    private void runOnScheduler(Room room, Runnable submission) {
        try {
            submission.run();
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.WARNING, "Quiz engine is shut down; timer for room {0} not scheduled", room.code());
        }
    }

    /** Wraps a timer task so it runs under the room lock, only if the room is still registered. */
    private Runnable guarded(Room room, Consumer<Room> action) {
        return () -> {
            if (rooms.get(room.code()) != room) {
                return;
            }
            synchronized (room) {
                try {
                    action.accept(room);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE, "Quiz timer task failed for room " + room.code(), e);
                }
            }
        };
    }

    private void publish(Room room, Message event) {
        try {
            sink.publish(room.code(), event);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to publish " + event.type() + " for room " + room.code(), e);
        }
    }

    private <T> CompletableFuture<T> onWorker(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, workers);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new QuizException(QuizError.INTERNAL_ERROR, "The quiz service is not running"));
        }
    }

    private Room room(String roomCode) {
        Room room = roomCode == null ? null : rooms.get(roomCode);
        if (room == null) {
            throw new QuizException(QuizError.INVALID_ROOM, "No quiz session exists for this room");
        }
        return room;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new QuizException(QuizError.INVALID_MESSAGE, fieldName + " is required");
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** A session plus its pending timer; all fields are guarded by the Room's monitor. */
    private static final class Room {
        private final QuizSession session;
        private ScheduledFuture<?> pendingTask;

        private Room(QuizSession session) {
            this.session = session;
        }

        private String code() {
            return session.roomCode();
        }
    }
}
