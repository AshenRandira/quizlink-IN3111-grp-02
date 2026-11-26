package lk.quizlink.server;

import lk.quizlink.common.protocol.Message;

/**
 * How handlers send a message to a client. NioServer implements it.
 * It is safe to call from any thread (for example a quiz worker thread),
 * because the server only queues the message and wakes up the Selector thread.
 */
public interface MessageSender {
    void send(ClientConnection connection, Message message);
}
