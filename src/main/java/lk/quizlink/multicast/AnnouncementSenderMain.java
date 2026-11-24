package lk.quizlink.multicast;

/**
 * Interactive command-line tool to send a single announcement to a multicast room.
 * <p>
 * Usage:
 * <pre>
 *   java lk.quizlink.multicast.AnnouncementSenderMain &lt;roomCode&gt; &lt;text&gt;
 * </pre>
 */
public final class AnnouncementSenderMain {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java lk.quizlink.multicast.AnnouncementSenderMain <roomCode> <text>");
            System.err.println("Example: java lk.quizlink.multicast.AnnouncementSenderMain ROOM1 \"Quiz starting in 2 minutes!\"");
            return;
        }

        String roomCode = args[0];
        String text = args[1];

        try (AnnouncementSender sender = new AnnouncementSender()) {
            Announcement announcement = new Announcement(roomCode, text);
            sender.send(announcement);
            System.out.printf("Successfully sent announcement to room [%s]: %s%n", roomCode, text);
        } catch (Exception e) {
            System.err.printf("Error sending announcement: %s%n", e.getMessage());
        }
    }
}
