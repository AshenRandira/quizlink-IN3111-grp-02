package lk.quizlink.student;

import java.util.List;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.QuestionView;

/** UI operations used by the student network controller. */
public interface StudentClientView {
    void showConnecting();

    void showLobbyScreen(StudentJoinRequest request);

    void showQuestion(QuestionView question, int questionNumber, int totalQuestions);

    void showLeaderboard(List<LeaderboardEntry> entries, boolean finalResults);

    void showConnectionError(String message);
}
