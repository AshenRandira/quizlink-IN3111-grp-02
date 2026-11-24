package lk.quizlink.host;

import javax.swing.SwingUtilities;

/**
 * Main application launcher for the QuizLink host and administration dashboard.
 */
public final class HostApplication {

    private HostApplication() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            HostFrame frame = new HostFrame();
            frame.setVisible(true);
        });
    }
}
