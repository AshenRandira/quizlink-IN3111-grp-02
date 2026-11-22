package lk.quizlink.student;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.SwingUtilities;

/** Launches the student Swing interface and its background TCP controller. */
public final class StudentApplication {
    public static void main(String[] args) {
        // Swing components must be created and updated on the Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> {
            StudentFrame frame = new StudentFrame();
            StudentClientController controller = new StudentClientController(frame);
            frame.setJoinHandler(controller::connectAndJoin);
            frame.setLeaveHandler(controller::leaveRoom);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    controller.close();
                }
            });
            frame.setVisible(true);
        });
    }
}
