package lk.quizlink.common.model;

import java.util.List;

/** Safe question data sent to student clients, without the answer key. */
public record QuestionView(String id, String text, List<String> options,
                           int timeLimitSeconds, int points) {
    public QuestionView {
        id = ModelValidation.requiredText(id, "id");
        text = ModelValidation.requiredText(text, "text");
        options = ModelValidation.copyOptions(options);
        if (timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("timeLimitSeconds must be positive");
        }
        if (points < 0) {
            throw new IllegalArgumentException("points must not be negative");
        }
    }
}
