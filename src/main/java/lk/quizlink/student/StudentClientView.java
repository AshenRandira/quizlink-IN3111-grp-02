package lk.quizlink.student;

import java.util.List;
import java.util.function.BiConsumer;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.QuestionView;

/** UI operations used by the student network controller. */
public interface StudentClientView {
    void showConnecting();

    void showLobbyScreen(StudentJoinRequest request);

    void showParticipants(List<Participant> participants);

    void setLeaveHandler(Runnable leaveHandler);

    void showJoinScreen();

    void showQuestion(QuestionView question, int questionNumber, int totalQuestions);

    void setAnswerHandler(BiConsumer<String, Integer> answerHandler);

    void showAnswerSubmitting(String questionId);

    void showAnswerResult(String questionId, boolean accepted, String reason);

    void showLeaderboard(List<LeaderboardEntry> entries, boolean finalResults);

    void showConnectionError(String message);
}
