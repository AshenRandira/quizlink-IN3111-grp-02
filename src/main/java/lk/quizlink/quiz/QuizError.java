package lk.quizlink.quiz;

/** Quiz-engine failure reasons. Names match the protocol error codes in docs/PROTOCOL.md. */
public enum QuizError {
    INVALID_MESSAGE,
    INVALID_ROOM,
    QUIZ_NOT_STARTED,
    QUESTION_EXPIRED,
    ALREADY_ANSWERED,
    FORBIDDEN,
    INTERNAL_ERROR
}
