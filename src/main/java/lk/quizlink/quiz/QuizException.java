package lk.quizlink.quiz;

import java.util.Objects;

/** A rejected quiz command. The message is safe to show to clients. */
public final class QuizException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final QuizError error;

    public QuizException(QuizError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    public QuizError error() {
        return error;
    }
}
