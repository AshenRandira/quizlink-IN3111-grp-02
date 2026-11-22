package lk.quizlink.server;

import lk.quizlink.common.protocol.ProtocolConstants;

/** Member 2 supplies the NIO server. This placeholder does not bind a port. */
public final class ServerApplication {
    public static void main(String[] args) {
        System.out.println("QuizLink server foundation. Planned TCP port: " + ProtocolConstants.TCP_PORT);
        System.out.println("NIO server implementation is pending; no listening socket has been opened.");
    }
}
