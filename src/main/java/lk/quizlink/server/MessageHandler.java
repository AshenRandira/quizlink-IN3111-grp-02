package lk.quizlink.server;

import lk.quizlink.common.protocol.Message;

/**
 * Plug-in point for the other modules. Member 3 (lobby), Member 4 (quiz engine) and
 * Member 5 (host) register a handler for the message types they own:
 * <pre>router.register(MessageType.JOIN_ROOM, lobby::handleJoin);</pre>
 *
 * Handlers run on the Selector thread, so they must return quickly.
 * Slow work belongs on a worker thread; the result can be sent later with the sender.
 */
@FunctionalInterface
public interface MessageHandler {
    void handle(ClientConnection connection, Message message, MessageSender sender);
}
