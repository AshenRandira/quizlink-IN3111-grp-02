package lk.quizlink.quiz;

import static lk.quizlink.quiz.QuizFixtures.ALICE;
import static lk.quizlink.quiz.QuizFixtures.BOB;
import static lk.quizlink.quiz.QuizFixtures.ROOM;
import static lk.quizlink.quiz.QuizFixtures.singleQuestionQuiz;
import static lk.quizlink.quiz.QuizFixtures.twoQuestionQuiz;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class QuizEngineTest {
    private static final Duration EVENT_WAIT = Duration.ofSeconds(5);
    private static final Duration QUIET_PERIOD = Duration.ofMillis(300);
    private static final Duration SHORT_INTERMISSION = Duration.ofMillis(50);
    private static final int LONG_TIME_LIMIT = 30;

    private RecordingSink sink;
    private QuizEngine engine;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        engine = new QuizEngine(sink, 4, SHORT_INTERMISSION, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void runsAQuizToCompletionWhenEveryoneAnswers() throws Exception {
        startWithAliceAndBob(twoQuestionQuiz(LONG_TIME_LIMIT));

        Message first = sink.next(MessageType.QUESTION);
        assertEquals("q1", first.payload().getAsJsonObject("question").get("id").getAsString());
        assertEquals(1, first.payload().get("questionNumber").getAsInt());
        assertEquals(2, first.payload().get("totalQuestions").getAsInt());
        assertFalse(first.payload().toString().contains("correctOptionIndex"));

        assertTrue(engine.submitAnswer(ROOM, ALICE.id(), "q1", 1).get().accepted());
        assertTrue(engine.submitAnswer(ROOM, BOB.id(), "q1", 0).get().accepted());

        JsonObject board = sink.next(MessageType.LEADERBOARD).payload();
        assertEntry(board, 0, "p1", 10, 1);
        assertEntry(board, 1, "p2", 0, 2);

        Message second = sink.next(MessageType.QUESTION);
        assertEquals(2, second.payload().get("questionNumber").getAsInt());
        engine.submitAnswer(ROOM, ALICE.id(), "q2", 0).get();
        engine.submitAnswer(ROOM, BOB.id(), "q2", 0).get();

        JsonObject finalBoard = sink.next(MessageType.QUIZ_ENDED).payload();
        assertEquals(ROOM, finalBoard.get("roomCode").getAsString());
        assertEntry(finalBoard, 0, "p1", 15, 1);
        assertEntry(finalBoard, 1, "p2", 5, 2);
        assertEquals(SessionStatus.ENDED, engine.status(ROOM));
        sink.assertQuiet();
    }

    @Test
    void closesTheQuestionWhenTheTimerExpires() throws Exception {
        startWithAliceAndBob(twoQuestionQuiz(1));
        sink.next(MessageType.QUESTION);
        engine.submitAnswer(ROOM, ALICE.id(), "q1", 1).get();

        JsonObject board = sink.next(MessageType.LEADERBOARD).payload();
        assertEntry(board, 0, "p1", 10, 1);

        AnswerResult late = engine.submitAnswer(ROOM, BOB.id(), "q1", 1).get();
        assertEquals(QuizError.QUESTION_EXPIRED, late.reason());
    }

    @Test
    void processesConcurrentAnswersFromManyParticipants() throws Exception {
        int participantCount = 40;
        engine.createSession(ROOM, singleQuestionQuiz(LONG_TIME_LIMIT));
        for (int i = 0; i < participantCount; i++) {
            engine.addParticipant(ROOM, new Participant("p" + i, "Student " + i));
        }
        engine.startQuiz(ROOM).get();
        sink.next(MessageType.QUESTION);

        List<AnswerResult> results = submitConcurrently(participantCount,
                i -> engine.submitAnswer(ROOM, "p" + i, "q1", i % 2 == 0 ? 1 : 0));

        assertTrue(results.stream().allMatch(AnswerResult::accepted));
        JsonObject finalBoard = sink.next(MessageType.QUIZ_ENDED).payload();
        assertEquals(participantCount, finalBoard.getAsJsonArray("entries").size());
        long winners = engine.leaderboard(ROOM).stream().filter(entry -> entry.score() == 10).count();
        assertEquals(participantCount / 2, winners);
        sink.assertQuiet();
    }

    @Test
    void acceptsOnlyOneOfManyConcurrentDuplicateAnswers() throws Exception {
        startWithAliceAndBob(twoQuestionQuiz(LONG_TIME_LIMIT));
        sink.next(MessageType.QUESTION);

        List<AnswerResult> results = submitConcurrently(20,
                i -> engine.submitAnswer(ROOM, ALICE.id(), "q1", 1));

        assertEquals(1, results.stream().filter(AnswerResult::accepted).count());
        assertEquals(19, results.stream().filter(r -> r.reason() == QuizError.ALREADY_ANSWERED).count());
        assertEquals(10, engine.leaderboard(ROOM).get(0).score());
    }

    @Test
    void hostCanEndTheQuizEarly() throws Exception {
        startWithAliceAndBob(twoQuestionQuiz(1));
        sink.next(MessageType.QUESTION);

        engine.endQuiz(ROOM).get();

        sink.next(MessageType.QUIZ_ENDED);
        assertEquals(QuizError.QUESTION_EXPIRED, engine.submitAnswer(ROOM, ALICE.id(), "q1", 1).get().reason());
        Thread.sleep(1_200);
        sink.assertQuiet();
    }

    @Test
    void closesTheQuestionWhenTheLastUnansweredParticipantLeaves() throws Exception {
        startWithAliceAndBob(twoQuestionQuiz(LONG_TIME_LIMIT));
        sink.next(MessageType.QUESTION);
        engine.submitAnswer(ROOM, ALICE.id(), "q1", 1).get();

        engine.removeParticipant(ROOM, BOB.id());

        sink.next(MessageType.LEADERBOARD);
    }

    @Test
    void rejectsCommandsForUnknownRooms() {
        assertQuizError(QuizError.INVALID_ROOM, engine.startQuiz("NOPE00"));
        assertQuizError(QuizError.INVALID_ROOM, engine.submitAnswer("NOPE00", "p1", "q1", 0));
        assertQuizError(QuizError.INVALID_ROOM, engine.endQuiz(null));
        assertThrows(QuizException.class, () -> engine.leaderboard("NOPE00"));
    }

    @Test
    void rejectsStartingWithoutParticipantsOrStartingTwice() throws Exception {
        engine.createSession(ROOM, twoQuestionQuiz(LONG_TIME_LIMIT));
        assertQuizError(QuizError.INVALID_MESSAGE, engine.startQuiz(ROOM));

        engine.addParticipant(ROOM, ALICE);
        engine.startQuiz(ROOM).get();

        assertQuizError(QuizError.INVALID_MESSAGE, engine.startQuiz(ROOM));
    }

    @Test
    void rejectsDuplicateSessionsAndMissingAnswerFields() {
        engine.createSession(ROOM, twoQuestionQuiz(LONG_TIME_LIMIT));

        QuizException duplicate = assertThrows(QuizException.class,
                () -> engine.createSession(ROOM, twoQuestionQuiz(LONG_TIME_LIMIT)));
        assertEquals(QuizError.INVALID_ROOM, duplicate.error());
        assertQuizError(QuizError.INVALID_MESSAGE, engine.submitAnswer(ROOM, ALICE.id(), " ", 0));
    }

    @Test
    void removedSessionsStopTheirTimers() throws Exception {
        startWithAliceAndBob(twoQuestionQuiz(1));
        sink.next(MessageType.QUESTION);

        engine.removeSession(ROOM);

        assertFalse(engine.hasSession(ROOM));
        Thread.sleep(1_200);
        sink.assertQuiet();
    }

    @Test
    void failsCommandsAfterTheEngineIsClosed() {
        engine.createSession(ROOM, twoQuestionQuiz(LONG_TIME_LIMIT));
        engine.close();

        assertQuizError(QuizError.INTERNAL_ERROR, engine.startQuiz(ROOM));
    }

    private void startWithAliceAndBob(Quiz quiz) throws Exception {
        engine.createSession(ROOM, quiz);
        engine.addParticipant(ROOM, ALICE);
        engine.addParticipant(ROOM, BOB);
        engine.startQuiz(ROOM).get();
    }

    private interface Submission {
        CompletableFuture<AnswerResult> submit(int index);
    }

    /** Releases all submissions at once from separate threads to maximise contention. */
    private static List<AnswerResult> submitConcurrently(int count, Submission submission) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<AnswerResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(callers.submit(() -> {
                    go.await();
                    return submission.submit(index).get();
                }));
            }
            go.countDown();
            List<AnswerResult> results = new ArrayList<>();
            for (Future<AnswerResult> future : futures) {
                results.add(future.get(EVENT_WAIT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } finally {
            callers.shutdownNow();
        }
    }

    private static void assertEntry(JsonObject payload, int position, String participantId, int score, int rank) {
        JsonObject entry = payload.getAsJsonArray("entries").get(position).getAsJsonObject();
        assertEquals(participantId, entry.get("participantId").getAsString());
        assertEquals(score, entry.get("score").getAsInt());
        assertEquals(rank, entry.get("rank").getAsInt());
    }

    private static void assertQuizError(QuizError expected, CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, future::get);
        QuizException cause = assertInstanceOf(QuizException.class, failure.getCause());
        assertEquals(expected, cause.error());
    }

    /** Thread-safe sink that records events in publication order. */
    private static final class RecordingSink implements QuizEventSink {
        private final BlockingQueue<Message> events = new LinkedBlockingQueue<>();

        @Override
        public void publish(String roomCode, Message event) {
            assertEquals(ROOM, roomCode);
            events.add(event);
        }

        Message next(MessageType expectedType) throws InterruptedException {
            Message event = events.poll(EVENT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(event != null, "Timed out waiting for " + expectedType);
            assertEquals(expectedType, event.type());
            return event;
        }

        void assertQuiet() throws InterruptedException {
            assertNull(events.poll(QUIET_PERIOD.toMillis(), TimeUnit.MILLISECONDS), "Unexpected extra event");
        }
    }
}
