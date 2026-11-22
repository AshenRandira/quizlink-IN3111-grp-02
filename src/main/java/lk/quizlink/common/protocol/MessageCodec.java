package lk.quizlink.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Encodes one JSON message per line. Socket streams must use UTF-8. */
public final class MessageCodec {
    private static final Gson GSON = new Gson();

    public String encode(Message message) {
        if (message == null) throw new IllegalArgumentException("Message is required");
        // Gson escapes newlines inside strings, preserving the one-line frame boundary.
        return GSON.toJson(message) + "\n";
    }

    /** Accept a single complete line, with or without its final LF/CRLF. */
    public Message decode(String line) {
        if (line == null || line.isBlank()) throw new IllegalArgumentException("Message line is empty");
        String body = line.endsWith("\r\n") ? line.substring(0, line.length() - 2)
                : line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
        if (body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Expected one complete JSON line");
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            // Check the envelope before Gson can substitute primitive defaults.
            if (!json.has("version") || !json.has("type") || !json.has("requestId") || !json.has("payload")) {
                throw new IllegalArgumentException("Missing required envelope field");
            }
            return GSON.fromJson(json, Message.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid protocol message", exception);
        }
    }
}
