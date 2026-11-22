package lk.quizlink.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import lk.quizlink.common.model.Question;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {
    private final MessageCodec codec = new MessageCodec();

    @Test
    void preservesUnicodeAndEscapesEmbeddedNewlines() {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", "Hello සිංහල\nsecond line");
        Message original = Message.of(MessageType.ANNOUNCEMENT, "", payload);
        String encoded = codec.encode(original);
        assertEquals(1, encoded.chars().filter(character -> character == '\n').count());
        assertEquals(original, codec.decode(encoded));
    }

    @Test
    void decodesConsecutiveFramesIndependently() throws Exception {
        String frame = codec.encode(Message.of(MessageType.PING, "r1", new JsonObject()));
        try (BufferedReader reader = new BufferedReader(new StringReader(frame + frame))) {
            assertEquals("r1", codec.decode(reader.readLine()).requestId());
            assertEquals(MessageType.PING, codec.decode(reader.readLine()).type());
            assertNull(reader.readLine());
        }
    }

    @Test
    void rejectsMalformedOrIncompatibleEnvelopes() {
        List<String> invalid = List.of("", "null", "[]", "{}", "not JSON",
                "{\"version\":2,\"type\":\"PING\",\"requestId\":\"r1\",\"payload\":{}}",
                "{\"version\":1,\"type\":\"UNKNOWN\",\"requestId\":\"r1\",\"payload\":{}}",
                "{\"version\":1,\"type\":\"PING\",\"requestId\":\"r1\",\"payload\":null}");
        for (String line : invalid) assertThrows(IllegalArgumentException.class, () -> codec.decode(line), line);
    }

    @Test
    void acceptsCrLfButRejectsMultipleFramesInOneDecode() {
        String frame = codec.encode(Message.of(MessageType.PING, "r1", new JsonObject()));
        assertEquals(MessageType.PING, codec.decode(frame.replace("\n", "\r\n")).type());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(frame + frame));
    }

    @Test
    void studentQuestionPayloadExcludesAnswerKey() {
        Question definition = new Question("q1", "Example?", List.of("A", "B"), 1, 20, 10);
        JsonObject json = new Gson().toJsonTree(definition.toView()).getAsJsonObject();
        assertFalse(json.has("correctOptionIndex"));
        assertEquals(2, json.getAsJsonArray("options").size());
    }
}
