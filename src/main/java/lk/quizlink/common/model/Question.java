package lk.quizlink.common.model;

import java.util.List;

/** Quiz definition stored on the host/server. Never send correctOptionIndex to students. */
public record Question(String id, String text, List<String> options,
                       int correctOptionIndex, int timeLimitSeconds, int points) {
    public QuestionView toView() {
        return new QuestionView(id, text, options, timeLimitSeconds, points);
    }
}
