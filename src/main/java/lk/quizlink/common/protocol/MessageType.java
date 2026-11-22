package lk.quizlink.common.protocol;

/** Draft wire operations; payload contracts are documented in docs/PROTOCOL.md. */
public enum MessageType {
    CREATE_ROOM, ROOM_CREATED, JOIN_ROOM, ROOM_JOINED, LEAVE_ROOM, PARTICIPANTS_UPDATED,
    START_QUIZ, QUESTION, SUBMIT_ANSWER, ANSWER_RESULT, LEADERBOARD, END_QUIZ, QUIZ_ENDED,
    SEND_ANNOUNCEMENT, ANNOUNCEMENT, PING, PONG, ERROR
}
