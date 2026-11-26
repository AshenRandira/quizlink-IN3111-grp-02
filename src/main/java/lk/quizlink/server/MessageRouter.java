package lk.quizlink.server;

import com.google.gson.JsonObject;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Decides what to do with each decoded message, based on its MessageType.
 *
 * The router does not know any quiz logic. It only looks up a registered handler.
 * Until the other members register their real handlers, the types they own get a polite
 * "not available yet" ERROR (a mock handler) so the server stays usable and testable.
 *
 * Only PING is answered here (PING -> PONG proves two-way communication).
 */
public final class MessageRouter {

    /** Message types a client is allowed to send. Everything else travels server -> client. */
    private static final Set<MessageType> CLIENT_REQUESTS = EnumSet.of(
            MessageType.PING, MessageType.CREATE_ROOM, MessageType.JOIN_ROOM, MessageType.LEAVE_ROOM,
            MessageType.START_QUIZ, MessageType.SUBMIT_ANSWER, MessageType.END_QUIZ,
            MessageType.SEND_ANNOUNCEMENT);

    private final Map<MessageType, MessageHandler> handlers = new EnumMap<>(MessageType.class);
    private final List<Consumer<ClientConnection>> disconnectListeners = new CopyOnWriteArrayList<>();

    public MessageRouter() {
        register(MessageType.PING, MessageRouter::handlePing);
    }

    /** Called by other modules to take over a message type. */
    public synchronized void register(MessageType type, MessageHandler handler) {
        handlers.put(type, handler);
    }

    /** Lets the lobby/quiz modules clean up (remove participant, ...) when a client drops. */
    public void onDisconnect(Consumer<ClientConnection> listener) {
        disconnectListeners.add(listener);
    }

    /** Called by the server when a connection has been closed. */
    void connectionClosed(ClientConnection connection) {
        for (Consumer<ClientConnection> listener : disconnectListeners) {
            try {
                listener.accept(connection);
            } catch (RuntimeException e) {
                System.err.println("Disconnect listener failed for " + connection + ": " + e);
            }
        }
    }

    /** Called by the server for every complete, valid message from a client. */
    void route(ClientConnection connection, Message message, MessageSender sender) {
        if (!CLIENT_REQUESTS.contains(message.type())) {
            sender.send(connection, errorReply(message.requestId(), "INVALID_MESSAGE",
                    "Clients may not send " + message.type()));
            return;
        }

        MessageHandler handler;
        synchronized (this) {
            handler = handlers.get(message.type());
        }
        if (handler == null) {
            // Mock handler: this module has not been connected yet.
            sender.send(connection, errorReply(message.requestId(), "INTERNAL_ERROR",
                    message.type() + " is not available yet (handler not connected)"));
            return;
        }

        try {
            handler.handle(connection, message, sender);
        } catch (RuntimeException e) {
            // Log details on the server only; never send stack traces to clients.
            System.err.println("Handler for " + message.type() + " failed: " + e);
            sender.send(connection, errorReply(message.requestId(), "INTERNAL_ERROR", "Request failed"));
        }
    }

    private static void handlePing(ClientConnection connection, Message message, MessageSender sender) {
        sender.send(connection, Message.of(MessageType.PONG, message.requestId(), new JsonObject()));
    }

    /** Builds an ERROR reply as described in docs/PROTOCOL.md. */
    static Message errorReply(String requestId, String code, String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", text);
        return Message.of(MessageType.ERROR, requestId, payload);
    }
}
