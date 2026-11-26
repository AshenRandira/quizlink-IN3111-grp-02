package lk.quizlink.quiz;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.QuestionView;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;

/**
 * Member 4 individual demonstration: runs a quiz with several simulated students who answer
 * concurrently from their own threads, then prints accepted/rejected answers and the updated
 * leaderboard after each question. No network is involved; the server module supplies that.
 *
 * <pre>mvn compile exec:java -Dexec.mainClass=lk.quizlink.quiz.QuizEngineDemo</pre>
 */
public final class QuizEngineDemo {
    private static final String ROOM_CODE = "DEMO01";
    private static final int QUESTION_SECONDS = 6;
    private static final Duration INTERMISSION = Duration.ofSeconds(2);
    private static final int MAX_THINKING_MILLIS = 4_000;
    private static final long EVENT_TIMEOUT_SECONDS = 30;
    private static final Gson GSON = new Gson();

    /** Each simulated student answers correctly with this probability. */
    private static final Map<Participant, Double> STUDENTS = Map.of(
            new Participant("p1", "Amaya"), 0.9,
            new Participant("p2", "Bhanuka"), 0.6,
            new Participant("p3", "Chathuri"), 0.4,
            new Participant("p4", "Dilan"), 0.2);

    private QuizEngineDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        Quiz quiz = demoQuiz();
        BlockingQueue<Message> events = new LinkedBlockingQueue<>();
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService studentThreads = Executors.newFixedThreadPool(STUDENTS.size(),
                runnable -> new Thread(runnable, "student-" + threadNumber.incrementAndGet()));

        try (QuizEngine engine = new QuizEngine((room, event) -> events.add(event),
                QuizEngine.DEFAULT_WORKER_THREADS, INTERMISSION, Clock.systemUTC())) {
            engine.createSession(ROOM_CODE, quiz);
            STUDENTS.keySet().forEach(student -> engine.addParticipant(ROOM_CODE, student));
            System.out.printf("Room %s: \"%s\" with %d students%n", ROOM_CODE, quiz.title(), STUDENTS.size());
            engine.startQuiz(ROOM_CODE).join();

            Message event;
            do {
                event = events.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (event == null) {
                    System.out.println("Timed out waiting for the next quiz event.");
                    return;
                }
                handleEvent(event, quiz, engine, studentThreads);
            } while (event.type() != MessageType.QUIZ_ENDED);
        } finally {
            studentThreads.shutdownNow();
        }
    }

    private static void handleEvent(Message event, Quiz quiz, QuizEngine engine, ExecutorService studentThreads) {
        JsonObject payload = event.payload();
        switch (event.type()) {
            case QUESTION -> {
                QuestionView view = GSON.fromJson(payload.get("question"), QuestionView.class);
                System.out.printf("%nQuestion %d/%d (%ds): %s %s%n", payload.get("questionNumber").getAsInt(),
                        payload.get("totalQuestions").getAsInt(), view.timeLimitSeconds(), view.text(), view.options());
                Question question = findQuestion(quiz, view.id());
                STUDENTS.forEach((student, skill) ->
                        studentThreads.submit(() -> answer(engine, student, skill, question)));
            }
            case LEADERBOARD -> printLeaderboard("Leaderboard", payload);
            case QUIZ_ENDED -> printLeaderboard("Final results", payload);
            default -> System.out.println("Ignoring " + event.type());
        }
    }

    /** Simulates a student thinking, answering, and (sometimes) trying to answer twice. */
    private static void answer(QuizEngine engine, Participant student, double skill, Question question) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        try {
            Thread.sleep(random.nextInt(MAX_THINKING_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        int choice = random.nextDouble() < skill
                ? question.correctOptionIndex()
                : (question.correctOptionIndex() + 1 + random.nextInt(question.options().size() - 1))
                        % question.options().size();
        report(student, choice, engine.submitAnswer(ROOM_CODE, student.id(), question.id(), choice).join());
        if (random.nextInt(4) == 0) {
            report(student, choice, engine.submitAnswer(ROOM_CODE, student.id(), question.id(), choice).join());
        }
    }

    private static void report(Participant student, int choice, AnswerResult result) {
        String outcome = result.accepted() ? "accepted" : "rejected (" + result.reason() + ")";
        System.out.printf("  [%s] %s chose option %d -> %s%n",
                Thread.currentThread().getName(), student.displayName(), choice, outcome);
    }

    private static void printLeaderboard(String title, JsonObject payload) {
        LeaderboardEntry[] entries = GSON.fromJson(payload.get("entries"), LeaderboardEntry[].class);
        System.out.println("  " + title + ":");
        for (LeaderboardEntry entry : entries) {
            System.out.printf("    #%d %-10s %3d pts%n", entry.rank(), entry.displayName(), entry.score());
        }
    }

    private static Question findQuestion(Quiz quiz, String questionId) {
        return quiz.questions().stream().filter(q -> q.id().equals(questionId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown question " + questionId));
    }

    private static Quiz demoQuiz() {
        return new Quiz("demo", "Network Programming Warm-up", List.of(
                new Question("q1", "Which protocol gives a reliable, ordered byte stream?",
                        List.of("UDP", "TCP", "ICMP"), 1, QUESTION_SECONDS, 10),
                new Question("q2", "Which Java class multiplexes many NIO channels?",
                        List.of("Socket", "Thread", "Selector", "DatagramPacket"), 2, QUESTION_SECONDS, 20),
                new Question("q3", "Which address range is used for IPv4 multicast?",
                        List.of("10.0.0.0/8", "224.0.0.0/4", "192.168.0.0/16"), 1, QUESTION_SECONDS, 15)));
    }
}
