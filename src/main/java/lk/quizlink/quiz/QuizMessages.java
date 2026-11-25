package lk.quizlink.quiz;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.QuestionView;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;

/**
 * Builds the protocol messages owned by the quiz engine, with payloads as documented in
 * docs/PROTOCOL.md. Question events accept only {@link QuestionView}, so the answer key cannot be
 * sent to students.
 */
public final class QuizMessages {
    private static final Gson GSON = new Gson();
    private static final String EVENT_REQUEST_ID = "";
    private static final String INTERNAL_ERROR_MESSAGE = "The quiz service could not process the request";

    private QuizMessages() {
    }

    public static Message question(String roomCode, QuestionView question, int questionNumber, int totalQuestions) {
        JsonObject payload = roomPayload(roomCode);
        payload.add("question", GSON.toJsonTree(question));
        payload.addProperty("questionNumber", questionNumber);
        payload.addProperty("totalQuestions", totalQuestions);
        return Message.of(MessageType.QUESTION, EVENT_REQUEST_ID, payload);
    }

    public static Message leaderboard(String roomCode, List<LeaderboardEntry> entries) {
        return entriesEvent(MessageType.LEADERBOARD, roomCode, entries);
    }

    public static Message quizEnded(String roomCode, List<LeaderboardEntry> finalEntries) {
        return entriesEvent(MessageType.QUIZ_ENDED, roomCode, finalEntries);
    }

    /** Direct reply to SUBMIT_ANSWER; preserves the request's ID. */
    public static Message answerResult(String requestId, AnswerResult result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("questionId", result.questionId());
        payload.addProperty("accepted", result.accepted());
        if (!result.accepted()) {
            payload.addProperty("reason", result.reason().name());
        }
        return Message.of(MessageType.ANSWER_RESULT, requestId, payload);
    }

    /**
     * ERROR reply for a failed engine future. QuizException codes and messages are passed through;
     * anything else becomes INTERNAL_ERROR without exposing internal details.
     */
    public static Message error(String requestId, Throwable failure) {
        Throwable cause = unwrap(failure);
        JsonObject payload = new JsonObject();
        if (cause instanceof QuizException quizException) {
            payload.addProperty("code", quizException.error().name());
            payload.addProperty("message", quizException.getMessage());
        } else {
            payload.addProperty("code", QuizError.INTERNAL_ERROR.name());
            payload.addProperty("message", INTERNAL_ERROR_MESSAGE);
        }
        return Message.of(MessageType.ERROR, requestId, payload);
    }

    private static Message entriesEvent(MessageType type, String roomCode, List<LeaderboardEntry> entries) {
        JsonObject payload = roomPayload(roomCode);
        payload.add("entries", GSON.toJsonTree(entries));
        return Message.of(type, EVENT_REQUEST_ID, payload);
    }

    private static JsonObject roomPayload(String roomCode) {
        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", roomCode);
        return payload;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
