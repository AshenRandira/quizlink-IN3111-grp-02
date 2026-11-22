package lk.quizlink.student;

/** UI operations used by the student network controller. */
public interface StudentClientView {
    void showConnecting();

    void showLobbyScreen(StudentJoinRequest request);

    void showConnectionError(String message);
}
