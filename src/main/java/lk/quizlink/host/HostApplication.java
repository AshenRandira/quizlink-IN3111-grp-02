package lk.quizlink.host;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Member 5 replaces this launch placeholder with the host dashboard. */
public final class HostApplication {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                "QuizLink host foundation is ready.\nQuiz management and session controls are pending.",
                "QuizLink Host", JOptionPane.INFORMATION_MESSAGE));
    }
}
