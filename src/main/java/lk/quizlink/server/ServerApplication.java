package lk.quizlink.server;

import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;

/**
 * Starts the central QuizLink server (Member 2).
 *
 * Usage: ServerApplication [port]   (default: ProtocolConstants.TCP_PORT = 5000)
 *
 * The other modules plug in here later by registering handlers on the router, for example:
 *   router.register(MessageType.JOIN_ROOM, lobbyService::handleJoin);
 * Until then, their message types get a "not available yet" ERROR reply.
 */
public final class ServerApplication {
    public static void main(String[] args) {
        int port = ProtocolConstants.TCP_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "'. Usage: ServerApplication [port]");
                System.exit(1);
            }
        }

        MessageRouter router = new MessageRouter();
        NioServer server = new NioServer(port, router);

        try {
            server.open();
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port + ": " + e.getMessage());
            System.exit(1);
        }

        // Ctrl+C asks the loop to stop so connections are closed cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.run(); // blocks here until stop()
    }
}
