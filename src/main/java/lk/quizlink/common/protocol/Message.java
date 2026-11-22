package lk.quizlink.common.protocol;

import com.google.gson.JsonObject;

/** requestId correlates a response with its request; events can use an empty ID. */
public record Message(int version, MessageType type, String requestId, JsonObject payload) {
    public Message {
        if (version != ProtocolConstants.VERSION) throw new IllegalArgumentException("Unsupported protocol version");
        if (type == null) throw new IllegalArgumentException("Message type is required");
        if (requestId == null) throw new IllegalArgumentException("requestId is required (empty for events)");
        if (payload == null) throw new IllegalArgumentException("payload is required");
    }

    public static Message of(MessageType type, String requestId, JsonObject payload) {
        return new Message(ProtocolConstants.VERSION, type, requestId, payload);
    }
}
