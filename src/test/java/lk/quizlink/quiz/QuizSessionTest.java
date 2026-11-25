package lk.quizlink.quiz;

import static lk.quizlink.quiz.QuizFixtures.ALICE;
import static lk.quizlink.quiz.QuizFixtures.BOB;
import static lk.quizlink.quiz.QuizFixtures.ROOM;
import static lk.quizlink.quiz.QuizFixtures.twoQuestionQuiz;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.QuestionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuizSessionTest {
    private QuizFixtures.MutableClock clock;
    private QuizSession session;

    @BeforeEach
    void setUp() {
        clock = new QuizFixtures.MutableClock();
        session = new QuizSession(ROOM, twoQuestionQuiz(20), clock);
        session.addParticipant(ALICE);
        session.addParticipant(BOB);
    }

    @Test
    void startsWaitingAndOpensQuestionsInOrder() {
        assertEquals(SessionStatus.WAITING, session.status());

        QuestionView first = session.openNextQuestion();

        assertEquals("q1", first.id());
        assertEquals(SessionStatus.QUESTION_OPEN, session.status());
        assertEquals(0, session.currentQuestionIndex());
        assertTrue(session.hasNextQuestion());
    }

    @Test
    void awardsFullPointsForCorrectAnswersAndZeroForWrongAnswers() {
        session.openNextQuestion();

        assertTrue(session.submitAnswer(ALICE.id(), "q1", 1).accepted());
        assertTrue(session.submitAnswer(BOB.id(), "q1", 0).accepted());

        List<LeaderboardEntry> board = session.leaderboard();
        assertEquals(new LeaderboardEntry("p1", "Alice", 10, 1), board.get(0));
        assertEquals(new LeaderboardEntry("p2", "Bob", 0, 2), board.get(1));
    }

    @Test
    void rejectsAnswersBeforeTheQuizStarts() {
        AnswerResult result = session.submitAnswer(ALICE.id(), "q1", 1);

        assertEquals(AnswerResult.rejected("q1", QuizError.QUIZ_NOT_STARTED), result);
    }

    @Test
    void rejectsSecondAnswerToTheSameQuestion() {
        session.openNextQuestion();
        session.submitAnswer(ALICE.id(), "q1", 0);

        AnswerResult second = session.submitAnswer(ALICE.id(), "q1", 1);

        assertEquals(QuizError.ALREADY_ANSWERED, second.reason());
        assertEquals(0, session.leaderboard().get(0).score() + session.leaderboard().get(1).score());
    }

    @Test
    void rejectsAnswersAfterTheDeadlineEvenBeforeTheTimerCloses() {
        session.openNextQuestion();
        clock.advance(Duration.ofSeconds(21));

        AnswerResult result = session.submitAnswer(ALICE.id(), "q1", 1);

        assertEquals(QuizError.QUESTION_EXPIRED, result.reason());
    }

    @Test
    void acceptsAnswersExactlyAtTheDeadline() {
        session.openNextQuestion();
        clock.advance(Duration.ofSeconds(20));

        assertTrue(session.submitAnswer(ALICE.id(), "q1", 1).accepted());
    }

    @Test
    void rejectsAnswersForPreviousOrUnknownQuestions() {
        session.openNextQuestion();
        session.closeQuestion(0);
        session.openNextQuestion();

        assertEquals(QuizError.QUESTION_EXPIRED, session.submitAnswer(ALICE.id(), "q1", 1).reason());
        assertEquals(QuizError.INVALID_MESSAGE, session.submitAnswer(ALICE.id(), "q99", 1).reason());
    }

    @Test
    void rejectsOutOfRangeOptionsWithoutConsumingTheAnswer() {
        session.openNextQuestion();

        assertEquals(QuizError.INVALID_MESSAGE, session.submitAnswer(ALICE.id(), "q1", 3).reason());
        assertEquals(QuizError.INVALID_MESSAGE, session.submitAnswer(ALICE.id(), "q1", -1).reason());
        assertTrue(session.submitAnswer(ALICE.id(), "q1", 1).accepted());
    }

    @Test
    void rejectsAnswersFromUnknownParticipants() {
        session.openNextQuestion();

        assertEquals(QuizError.FORBIDDEN, session.submitAnswer("intruder", "q1", 1).reason());
    }

    @Test
    void rejectsAnswersOnceTheQuestionIsClosed() {
        session.openNextQuestion();
        session.closeQuestion(0);

        assertEquals(QuizError.QUESTION_EXPIRED, session.submitAnswer(ALICE.id(), "q1", 1).reason());
    }

    @Test
    void closesAQuestionOnlyOnceAndOnlyForTheCurrentIndex() {
        session.openNextQuestion();

        assertFalse(session.closeQuestion(1));
        assertTrue(session.closeQuestion(0));
        assertFalse(session.closeQuestion(0));
        assertEquals(SessionStatus.QUESTION_CLOSED, session.status());
    }

    @Test
    void reportsWhenAllActiveParticipantsHaveAnswered() {
        session.openNextQuestion();
        session.submitAnswer(ALICE.id(), "q1", 1);
        assertFalse(session.allActiveParticipantsAnswered());

        session.submitAnswer(BOB.id(), "q1", 1);

        assertTrue(session.allActiveParticipantsAnswered());
    }

    @Test
    void removingAParticipantBeforeStartDropsThemCompletely() {
        session.removeParticipant(BOB.id());

        assertEquals(1, session.leaderboard().size());
        assertEquals(1, session.activeParticipantCount());
    }

    @Test
    void removingAParticipantDuringTheQuizKeepsTheirScore() {
        session.openNextQuestion();
        session.submitAnswer(BOB.id(), "q1", 1);

        session.removeParticipant(BOB.id());

        assertEquals(new LeaderboardEntry("p2", "Bob", 10, 1), session.leaderboard().get(0));
        assertEquals(1, session.activeParticipantCount());
        assertEquals(QuizError.FORBIDDEN, session.submitAnswer(BOB.id(), "q1", 1).reason());
    }

    @Test
    void refusesToOpenQuestionsBeyondTheQuizOrWhileOneIsOpen() {
        session.openNextQuestion();
        assertThrows(IllegalStateException.class, session::openNextQuestion);

        session.closeQuestion(0);
        session.openNextQuestion();
        session.closeQuestion(1);

        assertFalse(session.hasNextQuestion());
        assertThrows(IllegalStateException.class, session::openNextQuestion);
    }

    @Test
    void endingIsIdempotentAndBlocksNewParticipants() {
        assertTrue(session.end());
        assertFalse(session.end());

        QuizException error = assertThrows(QuizException.class,
                () -> session.addParticipant(new Participant("p3", "Cara")));
        assertEquals(QuizError.INVALID_ROOM, error.error());
    }
}
