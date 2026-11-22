package lk.quizlink.common.model;

import java.util.List;

/** Persistent quiz definition; runtime state belongs to the quiz engine. */
public record Quiz(String id, String title, List<Question> questions) { }
