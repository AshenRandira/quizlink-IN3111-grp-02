package lk.quizlink.lobby;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Parses lobby request payloads and builds lobby protocol messages.
 * <p>
 * This helper does not perform authorization, mutate lobby membership, route messages, or perform
 * network I/O. The central server remains responsible for those operations.
 */
public final class LobbyMessages {

    private static final Gson GSON = new Gson();
    private static final String INVALID_MESSAGE_TEXT = "The request payload is invalid.";
    private static final String INTERNAL_ERROR_TEXT = "The lobby could not process the request.";

    private LobbyMessages() {
    }

    /**
     * Reads and validates the shared Quiz definition in a CREATE_ROOM payload.
     * Unknown fields are ignored for forward compatibility.
     *
     * @throws IllegalArgumentException if the payload does not contain a valid Quiz definition
     */
    public static Quiz parseCreateRoomPayload(JsonObject payload) {
        JsonObject quizObject = requiredObject(payload, "quiz", "payload");
        String id = requiredString(quizObject, "id", "quiz.id");
        String title = requiredString(quizObject, "title", "quiz.title");
        JsonArray questionNodes = requiredArray(quizObject, "questions", "quiz.questions");
        List<Question> questions = new ArrayList<>(questionNodes.size());
        for (int index = 0; index < questionNodes.size(); index++) {
            String field = "quiz.questions[" + index + "]";
            JsonObject questionObject = requiredObject(questionNodes.get(index), field);
            String questionId = requiredString(questionObject, "id", field + ".id");
            String text = requiredString(questionObject, "text", field + ".text");
            JsonArray optionNodes = requiredArray(questionObject, "options", field + ".options");
            List<String> options = new ArrayList<>(optionNodes.size());
            for (int optionIndex = 0; optionIndex < optionNodes.size(); optionIndex++) {
                options.add(requiredString(optionNodes.get(optionIndex),
                        field + ".options[" + optionIndex + "]"));
            }
            int correctOptionIndex = requiredInt(questionObject, "correctOptionIndex",
                    field + ".correctOptionIndex");
            int timeLimitSeconds = requiredInt(questionObject, "timeLimitSeconds",
                    field + ".timeLimitSeconds");
            int points = requiredInt(questionObject, "points", field + ".points");
            questions.add(new Question(questionId, text, options, correctOptionIndex,
                    timeLimitSeconds, points));
        }
        return new Quiz(id, title, questions);
    }

    /**
     * Parses and normalizes a JOIN_ROOM payload. Any client-supplied participant ID or other
     * unknown field is ignored; participant identity is assigned by {@link LobbyService}.
     *
     * @throws IllegalArgumentException for missing, null, blank, or incorrectly typed fields
     * @throws LobbyException with {@code INVALID_ROOM} for a malformed non-blank room code
     */
    public static JoinRequest parseJoinRoomPayload(JsonObject payload) {
        String suppliedCode = requiredString(payload, "roomCode", "roomCode");
        String roomCode = normalizeRequestRoomCode(suppliedCode);
        String suppliedDisplayName = requiredString(payload, "displayName", "displayName");
        String displayName = suppliedDisplayName.trim();
        try {
            displayName = new Participant("payload-validation", displayName).displayName();
        } catch (IllegalArgumentException exception) {
            throw invalidPayload("displayName");
        }
        return new JoinRequest(roomCode, displayName);
    }

    /**
     * Parses and normalizes a LEAVE_ROOM payload. Unknown fields are ignored; a client-supplied
     * participant ID is never returned or used as an identity.
     */
    public static String parseLeaveRoomPayload(JsonObject payload) {
        return normalizeRequestRoomCode(requiredString(payload, "roomCode", "roomCode"));
    }

    /** Builds a correlated ROOM_CREATED reply using the actual registered room code. */
    public static Message roomCreated(String requestId, Room room) {
        if (room == null) {
            throw new IllegalArgumentException("room is required");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", room.roomCode());
        return Message.of(MessageType.ROOM_CREATED, requestId, payload);
    }

    /**
     * Builds a correlated ROOM_JOINED reply. The supplied participant must be the value returned
     * by {@link LobbyService#joinRoom(String, String)}; this builder does not join again.
     */
    public static Message roomJoined(String requestId, String roomCode, Participant participant) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        if (participant == null) {
            throw new IllegalArgumentException("participant is required");
        }
        JsonObject payload = roomPayload(normalizedCode);
        payload.add("participant", GSON.toJsonTree(participant));
        return Message.of(MessageType.ROOM_JOINED, requestId, payload);
    }

    /**
     * Builds an unsolicited event from a safely copied snapshot. Later changes to the caller's
     * list or lobby membership cannot alter this message's participant array.
     */
    public static Message participantsUpdated(String roomCode, List<Participant> participants) {
        String normalizedCode = RoomCodeGenerator.normalizeCode(roomCode);
        if (participants == null) {
            throw new IllegalArgumentException("participants are required");
        }
        List<Participant> snapshot = List.copyOf(participants);
        JsonObject payload = roomPayload(normalizedCode);
        payload.add("participants", GSON.toJsonTree(snapshot));
        return Message.of(MessageType.PARTICIPANTS_UPDATED, "", payload);
    }

    /**
     * Maps payload and lobby failures to existing wire error codes without exposing internal
     * exception details.
     */
    public static Message error(String requestId, Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure is required");
        }
        Throwable cause = unwrap(failure);
        String code;
        String message;
        if (cause instanceof LobbyException lobbyException) {
            switch (lobbyException.reason()) {
                case INVALID_ROOM -> {
                    code = "INVALID_ROOM";
                    message = "The requested quiz room does not exist.";
                }
                case INVALID_QUIZ, INVALID_DISPLAY_NAME -> {
                    code = "INVALID_MESSAGE";
                    message = INVALID_MESSAGE_TEXT;
                }
                case ROOM_CODE_ALLOCATION_FAILED, PARTICIPANT_ID_ALLOCATION_FAILED -> {
                    code = "INTERNAL_ERROR";
                    message = INTERNAL_ERROR_TEXT;
                }
                default -> throw new IllegalStateException(
                        "Unmapped lobby failure reason: " + lobbyException.reason());
            }
        } else if (cause instanceof IllegalArgumentException) {
            code = "INVALID_MESSAGE";
            message = INVALID_MESSAGE_TEXT;
        } else {
            code = "INTERNAL_ERROR";
            message = INTERNAL_ERROR_TEXT;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        return Message.of(MessageType.ERROR, requestId, payload);
    }

    private static String normalizeRequestRoomCode(String roomCode) {
        try {
            return RoomCodeGenerator.normalizeCode(roomCode);
        } catch (IllegalArgumentException exception) {
            throw new LobbyException(LobbyException.Reason.INVALID_ROOM,
                    "The requested room code is invalid");
        }
    }

    private static JsonObject roomPayload(String roomCode) {
        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", roomCode);
        return payload;
    }

    private static JsonObject requiredObject(JsonObject payload, String fieldName, String path) {
        if (payload == null) {
            throw invalidPayload("payload");
        }
        return requiredObject(payload.get(fieldName), path);
    }

    private static JsonObject requiredObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw invalidPayload(path);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject payload, String fieldName, String path) {
        JsonElement element = payload.get(fieldName);
        if (element == null || !element.isJsonArray()) {
            throw invalidPayload(path);
        }
        return element.getAsJsonArray();
    }

    private static String requiredString(JsonObject payload, String fieldName, String path) {
        if (payload == null) {
            throw invalidPayload("payload");
        }
        return requiredString(payload.get(fieldName), path);
    }

    private static String requiredString(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw invalidPayload(path);
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw invalidPayload(path);
        }
        return value;
    }

    private static int requiredInt(JsonObject payload, String fieldName, String path) {
        JsonElement element = payload.get(fieldName);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw invalidPayload(path);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            BigDecimal number = primitive.getAsBigDecimal();
            return number.intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalidPayload(path);
        }
    }

    private static IllegalArgumentException invalidPayload(String field) {
        return new IllegalArgumentException("Invalid lobby request field: " + field);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Validated values from a JOIN_ROOM request. */
    public record JoinRequest(String roomCode, String displayName) {
    }
}
