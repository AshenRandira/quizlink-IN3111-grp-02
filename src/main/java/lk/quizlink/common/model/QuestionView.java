package lk.quizlink.common.model;

import java.util.List;

/** Safe question data sent to student clients, without the answer key. */
public record QuestionView(String id, String text, List<String> options,
                           int timeLimitSeconds, int points) { }
