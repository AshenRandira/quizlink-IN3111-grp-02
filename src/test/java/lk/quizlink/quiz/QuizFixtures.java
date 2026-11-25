package lk.quizlink.quiz;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;

/** Shared test data for the quiz engine tests. */
final class QuizFixtures {
    static final String ROOM = "AB12CD";
    static final Participant ALICE = new Participant("p1", "Alice");
    static final Participant BOB = new Participant("p2", "Bob");

    private QuizFixtures() {
    }

    /** q1: correct option 1, 10 points. q2: correct option 0, 5 points. */
    static Quiz twoQuestionQuiz(int timeLimitSeconds) {
        return new Quiz("quiz-1", "Networking", List.of(
                new Question("q1", "Reliable byte stream?", List.of("UDP", "TCP", "IP"), 1, timeLimitSeconds, 10),
                new Question("q2", "Connectionless protocol?", List.of("UDP", "TCP"), 0, timeLimitSeconds, 5)));
    }

    static Quiz singleQuestionQuiz(int timeLimitSeconds) {
        return new Quiz("quiz-2", "Single", List.of(
                new Question("q1", "Reliable byte stream?", List.of("UDP", "TCP"), 1, timeLimitSeconds, 10)));
    }

    /** A clock the test can move forward to simulate question expiry deterministically. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-25T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
