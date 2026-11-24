package lk.quizlink.multicast;

import lk.quizlink.common.protocol.ProtocolConstants;

/**
 * Interactive command-line tool to listen for multicast announcements.
 * <p>
 * Usage:
 * <pre>
 *   java lk.quizlink.multicast.AnnouncementListenerMain [roomCode]
 * </pre>
 */
public final class AnnouncementListenerMain {

    public static void main(String[] args) {
        String roomFilter = (args.length > 0 && !args[0].isBlank()) ? args[0] : null;

        System.out.printf("Listening on %s:%d%s...%n",
                ProtocolConstants.MULTICAST_GROUP,
                ProtocolConstants.MULTICAST_PORT,
                roomFilter != null ? " (filtered for room: " + roomFilter + ")" : " (all rooms)");
        System.out.println("Press Enter or Ctrl+C to terminate.");

        try (AnnouncementListener listener = new AnnouncementListener(roomFilter, announcement -> {
            System.out.printf("[%s] -> %s%n", announcement.roomCode(), announcement.text());
        })) {
            listener.start();
            // Block until user presses Enter in console
            int ignored = System.in.read();
        } catch (Exception e) {
            System.err.printf("Listener encountered an error: %s%n", e.getMessage());
        }
    }
}
