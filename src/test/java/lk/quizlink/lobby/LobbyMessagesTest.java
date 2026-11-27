package lk.quizlink.lobby;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import lk.quizlink.common.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyMessagesTest {

    private static final Gson GSON = new Gson();
    private static final MessageCodec CODEC = new MessageCodec();
    private static final Quiz QUIZ = new Quiz("network-quiz", "Network Basics", List.of(
            new Question("q1", "Which protocol is reliable?",
                    List.of("UDP", "TCP"), 1, 20, 10)));

    @Test
    void parsesFullSharedQuizDefinitionAndIgnoresUnknownFields() {
        JsonObject payload = createPayload();
        payload.addProperty("trace", "forward-compatible");
        payload.getAsJsonObject("quiz").addProperty("futureQuizField", true);
        payload.getAsJsonObject("quiz").getAsJsonArray("questions")
                .get(0).getAsJsonObject().addProperty("futureQuestionField", 123);

        Quiz parsed = LobbyMessages.parseCreateRoomPayload(payload);

        assertEquals(QUIZ, parsed);
        assertEquals(1, parsed.questions().size());
        assertEquals(1, parsed.questions().get(0).correctOptionIndex());
    }

    @Test
    void rejectsMalformedCreateRoomPayloadsWithoutJsonTypeCoercion() {
        JsonObject missingQuiz = new JsonObject();
        JsonObject nullQuiz = new JsonObject();
        nullQuiz.add("quiz", com.google.gson.JsonNull.INSTANCE);
        JsonObject quizAsString = new JsonObject();
        quizAsString.addProperty("quiz", "not a quiz");
        JsonObject wrongFieldType = createPayload();
        wrongFieldType.getAsJsonObject("quiz").addProperty("id", 42);
        JsonObject numericString = createPayload();
        numericString.getAsJsonObject("quiz").getAsJsonArray("questions")
                .get(0).getAsJsonObject().addProperty("correctOptionIndex", "1");
        JsonObject fractionalIndex = createPayload();
        fractionalIndex.getAsJsonObject("quiz").getAsJsonArray("questions")
                .get(0).getAsJsonObject().addProperty("correctOptionIndex", 1.5);
        JsonObject invalidOptionType = createPayload();
        invalidOptionType.getAsJsonObject("quiz").getAsJsonArray("questions")
                .get(0).getAsJsonObject().getAsJsonArray("options").set(0,
                        com.google.gson.JsonNull.INSTANCE);

        assertThrows(IllegalArgumentException.class,
                () -> LobbyMessages.parseCreateRoomPayload(null));
        for (JsonObject invalid : List.of(missingQuiz, nullQuiz, quizAsString, wrongFieldType,
                numericString, fractionalIndex, invalidOptionType)) {
            assertThrows(IllegalArgumentException.class,
                    () -> LobbyMessages.parseCreateRoomPayload(invalid));
        }
    }

    @Test
    void buildsCorrelatedRoomCreatedFromRegisteredRoomAndRoundTrips() {
        LobbyService lobby = new LobbyService();
        Room room = lobby.createRoom(QUIZ);

        Message response = roundTrip(LobbyMessages.roomCreated("create-17", room));

        assertEquals(MessageType.ROOM_CREATED, response.type());
        assertEquals("create-17", response.requestId());
        assertEquals(ProtocolConstants.VERSION, response.version());
        assertEquals(room.roomCode(), response.payload().get("roomCode").getAsString());
        assertEquals(1, response.payload().size());
    }

    @Test
    void parsesJoinPayloadWithCanonicalCodeAndSharedDisplayNameRules() {
        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", " ab12cd ");
        payload.addProperty("displayName", " Student 李 ");
        payload.addProperty("participantId", "untrusted-client-value");
        payload.addProperty("futureField", true);

        LobbyMessages.JoinRequest request = LobbyMessages.parseJoinRoomPayload(payload);

        assertEquals("AB12CD", request.roomCode());
        assertEquals("Student 李", request.displayName());
    }

    @Test
    void rejectsMalformedJoinFieldsAndClassifiesMalformedRoomCodes() {
        JsonObject missingRoomCode = new JsonObject();
        missingRoomCode.addProperty("displayName", "Student");
        JsonObject nullRoomCode = new JsonObject();
        nullRoomCode.add("roomCode", com.google.gson.JsonNull.INSTANCE);
        nullRoomCode.addProperty("displayName", "Student");
        JsonObject missingDisplayName = new JsonObject();
        missingDisplayName.addProperty("roomCode", "AB12CD");
        JsonObject nullDisplayName = new JsonObject();
        nullDisplayName.addProperty("roomCode", "AB12CD");
        nullDisplayName.add("displayName", com.google.gson.JsonNull.INSTANCE);
        JsonObject nonStringCode = new JsonObject();
        nonStringCode.addProperty("roomCode", 123456);
        nonStringCode.addProperty("displayName", "Student");
        JsonObject nonStringName = new JsonObject();
        nonStringName.addProperty("roomCode", "AB12CD");
        nonStringName.addProperty("displayName", true);
        JsonObject blankName = new JsonObject();
        blankName.addProperty("roomCode", "AB12CD");
        blankName.addProperty("displayName", " \t ");
        JsonObject controlName = new JsonObject();
        controlName.addProperty("roomCode", "AB12CD");
        controlName.addProperty("displayName", "Bad\nName");
        JsonObject malformedCode = new JsonObject();
        malformedCode.addProperty("roomCode", "BAD");
        malformedCode.addProperty("displayName", "Student");

        for (JsonObject invalid : List.of(missingRoomCode, nullRoomCode, missingDisplayName,
                nullDisplayName, nonStringCode, nonStringName, blankName, controlName)) {
            assertThrows(IllegalArgumentException.class,
                    () -> LobbyMessages.parseJoinRoomPayload(invalid));
        }
        LobbyException roomError = assertThrows(LobbyException.class,
                () -> LobbyMessages.parseJoinRoomPayload(malformedCode));
        assertEquals(LobbyException.Reason.INVALID_ROOM, roomError.reason());
        assertThrows(IllegalArgumentException.class,
                () -> LobbyMessages.parseJoinRoomPayload(null));
    }

    @Test
    void buildsRoomJoinedWithActualServerParticipantWithoutJoiningAgain() {
        LobbyService lobby = new LobbyService();
        Room room = lobby.createRoom(QUIZ);
        Participant participant = lobby.joinRoom(room.roomCode(), "Student 1");

        Message response = roundTrip(LobbyMessages.roomJoined(
                "join-29", " " + room.roomCode().toLowerCase() + " ", participant));

        assertEquals(MessageType.ROOM_JOINED, response.type());
        assertEquals("join-29", response.requestId());
        assertEquals(ProtocolConstants.VERSION, response.version());
        assertEquals(room.roomCode(), response.payload().get("roomCode").getAsString());
        assertEquals(participant, GSON.fromJson(
                response.payload().getAsJsonObject("participant"), Participant.class));
        assertEquals(1, lobby.getParticipantCount(room.roomCode()));
    }

    @Test
    void participantUpdateIsRoomScopedUnsolicitedCopiedAndAllowsEmptyList() {
        LobbyService lobby = new LobbyService();
        Room room = lobby.createRoom(QUIZ);
        Participant participant = lobby.joinRoom(room.roomCode(), "Student 1");
        List<Participant> source = new ArrayList<>(lobby.getParticipants(room.roomCode()));

        Message event = roundTrip(LobbyMessages.participantsUpdated(
                " " + room.roomCode().toLowerCase() + " ", source));
        source.clear();

        assertEquals(MessageType.PARTICIPANTS_UPDATED, event.type());
        assertEquals("", event.requestId());
        assertEquals(ProtocolConstants.VERSION, event.version());
        assertEquals(room.roomCode(), event.payload().get("roomCode").getAsString());
        assertEquals(participant, GSON.fromJson(
                event.payload().getAsJsonArray("participants").get(0), Participant.class));

        Message emptyEvent = roundTrip(LobbyMessages.participantsUpdated(room.roomCode(), List.of()));
        assertTrue(emptyEvent.payload().getAsJsonArray("participants").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> lobby.getParticipants(room.roomCode()).clear());
    }

    @Test
    void parsesLeavePayloadButNeverReturnsClientSuppliedIdentity() {
        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", " ab12cd ");
        payload.addProperty("participantId", "must-not-be-trusted");
        payload.addProperty("futureField", false);

        assertEquals("AB12CD", LobbyMessages.parseLeaveRoomPayload(payload));

        JsonObject wrongType = new JsonObject();
        wrongType.addProperty("roomCode", false);
        assertThrows(IllegalArgumentException.class,
                () -> LobbyMessages.parseLeaveRoomPayload(wrongType));
        JsonObject nullCode = new JsonObject();
        nullCode.add("roomCode", com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class,
                () -> LobbyMessages.parseLeaveRoomPayload(nullCode));
        assertThrows(IllegalArgumentException.class,
                () -> LobbyMessages.parseLeaveRoomPayload(new JsonObject()));
        JsonObject malformedCode = new JsonObject();
        malformedCode.addProperty("roomCode", "BAD");
        LobbyException roomError = assertThrows(LobbyException.class,
                () -> LobbyMessages.parseLeaveRoomPayload(malformedCode));
        assertEquals(LobbyException.Reason.INVALID_ROOM, roomError.reason());
    }

    @Test
    void mapsLobbyAndPayloadFailuresToExistingSafeCorrelatedErrors() {
        Message invalidRoom = roundTrip(LobbyMessages.error("req-room",
                new LobbyException(LobbyException.Reason.INVALID_ROOM, "internal room detail")));
        Message invalidPayload = roundTrip(LobbyMessages.error("req-payload",
                new CompletionException(new IllegalArgumentException("private payload detail"))));
        Message invalidName = roundTrip(LobbyMessages.error("req-name",
                new LobbyException(LobbyException.Reason.INVALID_DISPLAY_NAME, "raw name detail")));
        Message invalidQuiz = roundTrip(LobbyMessages.error("req-quiz",
                new LobbyException(LobbyException.Reason.INVALID_QUIZ, "raw quiz detail")));
        Message roomCodeAllocation = roundTrip(LobbyMessages.error("req-code-allocation",
                new LobbyException(LobbyException.Reason.ROOM_CODE_ALLOCATION_FAILED,
                        "generator detail")));
        Message allocationFailure = roundTrip(LobbyMessages.error("req-allocation",
                new LobbyException(LobbyException.Reason.PARTICIPANT_ID_ALLOCATION_FAILED,
                        "generator detail")));
        Message unexpectedFailure = roundTrip(LobbyMessages.error("req-internal",
                new IllegalStateException("private implementation detail")));

        assertError(invalidRoom, "req-room", "INVALID_ROOM");
        assertError(invalidPayload, "req-payload", "INVALID_MESSAGE");
        assertError(invalidName, "req-name", "INVALID_MESSAGE");
        assertError(invalidQuiz, "req-quiz", "INVALID_MESSAGE");
        assertError(roomCodeAllocation, "req-code-allocation", "INTERNAL_ERROR");
        assertError(allocationFailure, "req-allocation", "INTERNAL_ERROR");
        assertError(unexpectedFailure, "req-internal", "INTERNAL_ERROR");
        for (Message error : List.of(invalidRoom, invalidPayload, invalidName, invalidQuiz,
                roomCodeAllocation, allocationFailure, unexpectedFailure)) {
            assertFalse(error.payload().toString().contains("detail"));
            assertFalse(error.payload().toString().contains("private"));
            assertFalse(error.payload().toString().contains("generator"));
            assertTrue(error.payload().has("message"));
        }
    }

    private static JsonObject createPayload() {
        JsonObject question = new JsonObject();
        question.addProperty("id", "q1");
        question.addProperty("text", "Which protocol is reliable?");
        JsonArray options = new JsonArray();
        options.add("UDP");
        options.add("TCP");
        question.add("options", options);
        question.addProperty("correctOptionIndex", 1);
        question.addProperty("timeLimitSeconds", 20);
        question.addProperty("points", 10);
        JsonArray questions = new JsonArray();
        questions.add(question);

        JsonObject quiz = new JsonObject();
        quiz.addProperty("id", "network-quiz");
        quiz.addProperty("title", "Network Basics");
        quiz.add("questions", questions);
        JsonObject payload = new JsonObject();
        payload.add("quiz", quiz);
        return payload;
    }

    private static void assertError(Message message, String requestId, String code) {
        assertEquals(MessageType.ERROR, message.type());
        assertEquals(requestId, message.requestId());
        assertEquals(ProtocolConstants.VERSION, message.version());
        assertEquals(code, message.payload().get("code").getAsString());
    }

    private static Message roundTrip(Message message) {
        return CODEC.decode(CODEC.encode(message));
    }
}
