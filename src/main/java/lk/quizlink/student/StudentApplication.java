package lk.quizlink.student;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Member 1 replaces this launch placeholder with the student interface and TCP client. */
public final class StudentApplication {
    public static void main(String[] args) {
        // Swing components must be created and updated on the Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                "QuizLink student foundation is ready.\nJoin, quiz and results screens are pending.",
                "QuizLink Student", JOptionPane.INFORMATION_MESSAGE));
    }
}
