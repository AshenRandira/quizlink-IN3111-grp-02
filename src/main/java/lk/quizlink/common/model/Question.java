package lk.quizlink.common.model;

import java.util.List;

/** Quiz definition stored on the host/server. Never send correctOptionIndex to students. */
public record Question(String id, String text, List<String> options,
                       int correctOptionIndex, int timeLimitSeconds, int points) {
    public Question {
        id = ModelValidation.requiredText(id, "id");
        text = ModelValidation.requiredText(text, "text");
        options = ModelValidation.copyOptions(options);
        if (correctOptionIndex < 0 || correctOptionIndex >= options.size()) {
            throw new IllegalArgumentException("correctOptionIndex must refer to an option");
        }
        if (timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("timeLimitSeconds must be positive");
        }
        if (points < 0) {
            throw new IllegalArgumentException("points must not be negative");
        }
    }

    public QuestionView toView() {
        return new QuestionView(id, text, options, timeLimitSeconds, points);
    }
}
