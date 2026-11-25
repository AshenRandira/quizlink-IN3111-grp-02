package lk.quizlink.quiz;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.QuestionView;
import lk.quizlink.common.model.Quiz;

/**
 * Runtime state of one quiz room: question progression, accepted answers and scores.
 *
 * <p>Every method is synchronized, so the session is safe to use from several worker threads.
 * {@link QuizEngine} adds timers and event publishing around it. Participant IDs passed in must
 * already be verified by the server against the sending connection.
 */
final class QuizSession {
    private final String roomCode;
    private final Quiz quiz;
    private final Clock clock;
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final Set<String> activeParticipantIds = new HashSet<>();
    private final Map<String, Integer> scores = new HashMap<>();
    private final Set<String> answeredCurrentQuestion = new HashSet<>();
    private SessionStatus status = SessionStatus.WAITING;
    private int currentIndex = -1;
    private Instant deadline = Instant.MIN;

    QuizSession(String roomCode, Quiz quiz, Clock clock) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new QuizException(QuizError.INVALID_ROOM, "A room code is required");
        }
        this.roomCode = roomCode;
        this.quiz = Objects.requireNonNull(quiz, "quiz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    String roomCode() {
        return roomCode;
    }

    int totalQuestions() {
        return quiz.questions().size();
    }

    synchronized SessionStatus status() {
        return status;
    }

    /** Zero-based index of the current question, or -1 before the quiz starts. */
    synchronized int currentQuestionIndex() {
        return currentIndex;
    }

    synchronized int activeParticipantCount() {
        return activeParticipantIds.size();
    }

    /** Adds a participant, or reactivates one who rejoins. Late joiners start with zero points. */
    synchronized void addParticipant(Participant participant) {
        Objects.requireNonNull(participant, "participant");
        if (status == SessionStatus.ENDED) {
            throw new QuizException(QuizError.INVALID_ROOM, "The quiz in this room has ended");
        }
        participants.put(participant.id(), participant);
        activeParticipantIds.add(participant.id());
        scores.putIfAbsent(participant.id(), 0);
    }

    /**
     * Before the quiz starts the participant is dropped entirely. Afterwards they stop counting
     * towards "everyone answered" but keep their score on the leaderboard.
     */
    synchronized void removeParticipant(String participantId) {
        activeParticipantIds.remove(participantId);
        if (status == SessionStatus.WAITING) {
            participants.remove(participantId);
            scores.remove(participantId);
        }
    }

    synchronized boolean hasNextQuestion() {
        return currentIndex + 1 < totalQuestions();
    }

    /** Opens the next question, starts its deadline and returns the student-safe view. */
    synchronized QuestionView openNextQuestion() {
        if (status != SessionStatus.WAITING && status != SessionStatus.QUESTION_CLOSED) {
            throw new IllegalStateException("Cannot open a question while the session is " + status);
        }
        if (!hasNextQuestion()) {
            throw new IllegalStateException("No questions remain");
        }
        currentIndex++;
        answeredCurrentQuestion.clear();
        Question question = currentQuestion();
        deadline = clock.instant().plusSeconds(question.timeLimitSeconds());
        status = SessionStatus.QUESTION_OPEN;
        return question.toView();
    }

    /** Validates and records an answer. Only the first valid answer per question is accepted. */
    synchronized AnswerResult submitAnswer(String participantId, String questionId, int selectedOptionIndex) {
        Optional<QuizError> rejection = validateSubmission(participantId, questionId, selectedOptionIndex);
        if (rejection.isPresent()) {
            return AnswerResult.rejected(questionId, rejection.get());
        }
        answeredCurrentQuestion.add(participantId);
        scores.merge(participantId, ScoringRule.pointsFor(currentQuestion(), selectedOptionIndex), Integer::sum);
        return AnswerResult.accepted(questionId);
    }

    synchronized boolean allActiveParticipantsAnswered() {
        return status == SessionStatus.QUESTION_OPEN && answeredCurrentQuestion.containsAll(activeParticipantIds);
    }

    /**
     * Closes the question at {@code questionIndex} if it is still the open one. Returning false
     * makes a late timer and an early "everyone answered" close safe to race.
     */
    synchronized boolean closeQuestion(int questionIndex) {
        if (status != SessionStatus.QUESTION_OPEN || currentIndex != questionIndex) {
            return false;
        }
        status = SessionStatus.QUESTION_CLOSED;
        return true;
    }

    /** Ends the session; returns false if it had already ended. */
    synchronized boolean end() {
        if (status == SessionStatus.ENDED) {
            return false;
        }
        status = SessionStatus.ENDED;
        return true;
    }

    synchronized List<LeaderboardEntry> leaderboard() {
        return Leaderboard.rank(participants.values(), scores);
    }

    private Optional<QuizError> validateSubmission(String participantId, String questionId, int selectedOptionIndex) {
        if (!activeParticipantIds.contains(participantId)) {
            return Optional.of(QuizError.FORBIDDEN);
        }
        if (status == SessionStatus.WAITING) {
            return Optional.of(QuizError.QUIZ_NOT_STARTED);
        }
        if (status != SessionStatus.QUESTION_OPEN) {
            return Optional.of(QuizError.QUESTION_EXPIRED);
        }
        Question question = currentQuestion();
        if (!question.id().equals(questionId)) {
            return Optional.of(isEarlierQuestion(questionId) ? QuizError.QUESTION_EXPIRED : QuizError.INVALID_MESSAGE);
        }
        if (clock.instant().isAfter(deadline)) {
            return Optional.of(QuizError.QUESTION_EXPIRED);
        }
        if (answeredCurrentQuestion.contains(participantId)) {
            return Optional.of(QuizError.ALREADY_ANSWERED);
        }
        if (selectedOptionIndex < 0 || selectedOptionIndex >= question.options().size()) {
            return Optional.of(QuizError.INVALID_MESSAGE);
        }
        return Optional.empty();
    }

    private boolean isEarlierQuestion(String questionId) {
        return quiz.questions().subList(0, currentIndex).stream().anyMatch(q -> q.id().equals(questionId));
    }

    private Question currentQuestion() {
        return quiz.questions().get(currentIndex);
    }
}
