package lk.quizlink.quiz;

/** Lifecycle of a quiz session: WAITING → (QUESTION_OPEN ⇄ QUESTION_CLOSED)* → ENDED. */
public enum SessionStatus {
    /** Participants can join; no question has been sent. */
    WAITING,
    /** A question is accepting answers until its deadline. */
    QUESTION_OPEN,
    /** The current question has closed; the next one opens after the intermission. */
    QUESTION_CLOSED,
    /** The quiz has finished or was ended by the host. */
    ENDED
}
