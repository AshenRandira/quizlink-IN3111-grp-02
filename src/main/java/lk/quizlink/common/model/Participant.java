package lk.quizlink.common.model;

/** The server assigns participant IDs; clients must not choose another student's ID. */
public record Participant(String id, String displayName) { }
