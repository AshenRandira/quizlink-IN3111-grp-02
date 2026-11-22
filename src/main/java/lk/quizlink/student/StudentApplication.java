package lk.quizlink.student;

import javax.swing.SwingUtilities;

/** Launches the student Swing interface. Networking is added in a later client slice. */
public final class StudentApplication {
    public static void main(String[] args) {
        // Swing components must be created and updated on the Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> new StudentFrame().setVisible(true));
    }
}
