package lk.quizlink.quiz;

/**
 * Outcome of one answer submission. {@code accepted} acknowledges receipt only: correctness is
 * revealed through the leaderboard after the question closes.
 */
public record AnswerResult(String questionId, boolean accepted, QuizError reason) {
    public AnswerResult {
        if (accepted == (reason != null)) {
            throw new IllegalArgumentException("A reason is required exactly when the answer is rejected");
        }
    }

    public static AnswerResult accepted(String questionId) {
        return new AnswerResult(questionId, true, null);
    }

    public static AnswerResult rejected(String questionId, QuizError reason) {
        return new AnswerResult(questionId, false, reason);
    }
}
