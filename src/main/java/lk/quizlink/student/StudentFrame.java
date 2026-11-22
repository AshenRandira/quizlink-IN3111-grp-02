package lk.quizlink.student;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.QuestionView;

/** Student Swing shell; network work is delegated to {@link StudentClientController}. */
public final class StudentFrame extends JFrame implements StudentClientView {
    private static final String JOIN_CARD = "join";
    private static final String LOBBY_CARD = "lobby";
    private static final String QUESTION_CARD = "question";
    private static final String RESULTS_CARD = "results";
    private static final Color BACKGROUND = new Color(245, 247, 250);
    private static final Color PRIMARY = new Color(34, 75, 140);

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JTextField serverHostField = new JTextField("localhost", 18);
    private final JTextField roomCodeField = new JTextField(18);
    private final JTextField displayNameField = new JTextField(18);
    private final JButton joinButton = createPrimaryButton("Join room");
    private final JLabel joinErrorLabel = new JLabel(" ");
    private final JLabel lobbyRoomLabel = new JLabel();
    private final JLabel lobbyNameLabel = new JLabel();
    private final JLabel questionNumberLabel = new JLabel();
    private final JLabel questionTextLabel = new JLabel();
    private final JPanel optionsPanel = new JPanel();
    private final JLabel answerStatusLabel = new JLabel(" ");
    private final JButton submitAnswerButton = createPrimaryButton("Submit answer");
    private final JLabel resultsTitleLabel = new JLabel("Final leaderboard");
    private BiConsumer<String, StudentJoinRequest> joinHandler =
            (host, request) -> showConnectionError("Student client is not configured");
    private BiConsumer<String, Integer> answerHandler =
            (questionId, optionIndex) -> showConnectionError("Student client is not configured");
    private String displayedQuestionId;
    private int selectedOptionIndex = -1;

    public StudentFrame() {
        super("QuizLink Student");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(700, 480));
        setSize(760, 520);
        setLocationByPlatform(true);

        cardPanel.add(createJoinPanel(), JOIN_CARD);
        cardPanel.add(createLobbyPanel(), LOBBY_CARD);
        cardPanel.add(createQuestionPanel(), QUESTION_CARD);
        cardPanel.add(createResultsPanel(), RESULTS_CARD);
        add(cardPanel);
        showJoinScreen();
    }

    /** Shows the form used before the TCP client sends a join request. */
    public void showJoinScreen() {
        joinButton.setEnabled(true);
        cards.show(cardPanel, JOIN_CARD);
        roomCodeField.requestFocusInWindow();
    }

    public void setJoinHandler(BiConsumer<String, StudentJoinRequest> joinHandler) {
        if (joinHandler == null) {
            throw new IllegalArgumentException("joinHandler is required");
        }
        this.joinHandler = joinHandler;
    }

    @Override
    public void setAnswerHandler(BiConsumer<String, Integer> answerHandler) {
        if (answerHandler == null) {
            throw new IllegalArgumentException("answerHandler is required");
        }
        this.answerHandler = answerHandler;
    }

    @Override
    public void showConnecting() {
        joinButton.setEnabled(false);
        joinErrorLabel.setForeground(Color.DARK_GRAY);
        joinErrorLabel.setText("Connecting to the QuizLink server...");
    }

    /** Shows the waiting state after the server confirms the join. */
    @Override
    public void showLobbyScreen(StudentJoinRequest request) {
        joinButton.setEnabled(true);
        lobbyRoomLabel.setText("Room: " + request.roomCode());
        lobbyNameLabel.setText("Name: " + request.displayName());
        cards.show(cardPanel, LOBBY_CARD);
    }

    @Override
    public void showConnectionError(String message) {
        joinButton.setEnabled(true);
        joinErrorLabel.setForeground(new Color(170, 35, 35));
        joinErrorLabel.setText(message);
        cards.show(cardPanel, JOIN_CARD);
    }

    /** Displays a server-provided question without exposing its answer key. */
    @Override
    public void showQuestion(QuestionView question, int questionNumber, int totalQuestions) {
        displayedQuestionId = question.id();
        selectedOptionIndex = -1;
        submitAnswerButton.setEnabled(false);
        questionNumberLabel.setText("Question " + questionNumber + " of " + totalQuestions);
        questionTextLabel.setText("<html><div style='width:560px'>" + escapeHtml(question.text()) + "</div></html>");
        optionsPanel.removeAll();
        ButtonGroup optionGroup = new ButtonGroup();
        for (int index = 0; index < question.options().size(); index++) {
            JRadioButton option = new JRadioButton((index + 1) + ". " + question.options().get(index));
            option.setActionCommand(Integer.toString(index));
            option.addActionListener(event -> {
                selectedOptionIndex = Integer.parseInt(event.getActionCommand());
                submitAnswerButton.setEnabled(true);
                answerStatusLabel.setText("Selected option " + (selectedOptionIndex + 1));
            });
            optionGroup.add(option);
            optionsPanel.add(option);
        }
        answerStatusLabel.setText("Choose an answer, then submit it to the server.");
        optionsPanel.revalidate();
        optionsPanel.repaint();
        cards.show(cardPanel, QUESTION_CARD);
    }

    @Override
    public void showAnswerSubmitting(String questionId) {
        if (questionId.equals(displayedQuestionId)) {
            submitAnswerButton.setEnabled(false);
            answerStatusLabel.setText("Submitting your answer...");
        }
    }

    @Override
    public void showAnswerResult(String questionId, boolean accepted, String reason) {
        if (!questionId.equals(displayedQuestionId)) {
            return;
        }
        if (accepted) {
            answerStatusLabel.setText("Answer accepted. Waiting for the next question...");
        } else {
            answerStatusLabel.setText(reason == null || reason.isBlank()
                    ? "Answer was not accepted." : "Answer not accepted: " + reason);
        }
        submitAnswerButton.setEnabled(false);
    }

    /** Displays a server-provided leaderboard or final result. */
    @Override
    public void showLeaderboard(List<LeaderboardEntry> entries, boolean finalResults) {
        resultsTitleLabel.setText(finalResults ? "Final leaderboard" : "Leaderboard");
        DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"Rank", "Student", "Score"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (LeaderboardEntry entry : entries) {
            tableModel.addRow(new Object[]{entry.rank(), entry.displayName(), entry.score()});
        }
        JTable table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel resultsPanel = (JPanel) cardPanel.getComponent(3);
        resultsPanel.removeAll();
        resultsPanel.add(createHeader(resultsTitleLabel), BorderLayout.NORTH);
        resultsPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        resultsPanel.add(createFooter("The server owns the authoritative leaderboard."), BorderLayout.SOUTH);
        resultsPanel.revalidate();
        resultsPanel.repaint();
        cards.show(cardPanel, RESULTS_CARD);
    }

    private JPanel createJoinPanel() {
        JPanel panel = createScreenPanel();
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(7, 7, 7, 7);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addFormRow(form, constraints, 0, "Server address", serverHostField);
        addFormRow(form, constraints, 1, "Room code", roomCodeField);
        addFormRow(form, constraints, 2, "Display name", displayNameField);

        joinButton.addActionListener(event -> joinFromForm());
        constraints.gridx = 1;
        constraints.gridy = 3;
        form.add(joinButton, constraints);

        joinErrorLabel.setForeground(new Color(170, 35, 35));
        constraints.gridy = 4;
        form.add(joinErrorLabel, constraints);

        panel.add(createHeader(new JLabel("Join a QuizLink room")), BorderLayout.NORTH);
        panel.add(form, BorderLayout.CENTER);
        panel.add(createFooter("Enter the server address manually; LAN discovery is owned by the discovery module."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createLobbyPanel() {
        JPanel panel = createScreenPanel();
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        lobbyRoomLabel.setAlignmentX(CENTER_ALIGNMENT);
        lobbyNameLabel.setAlignmentX(CENTER_ALIGNMENT);
        JLabel waiting = new JLabel("Waiting for the host to start the quiz...");
        waiting.setAlignmentX(CENTER_ALIGNMENT);
        content.add(Box.createVerticalGlue());
        content.add(lobbyRoomLabel);
        content.add(Box.createVerticalStrut(8));
        content.add(lobbyNameLabel);
        content.add(Box.createVerticalStrut(28));
        content.add(waiting);
        content.add(Box.createVerticalGlue());
        panel.add(createHeader(new JLabel("Quiz lobby")), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        panel.add(createFooter("The lobby state will be updated by server events."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createQuestionPanel() {
        JPanel panel = createScreenPanel();
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        questionNumberLabel.setAlignmentX(LEFT_ALIGNMENT);
        questionTextLabel.setAlignmentX(LEFT_ALIGNMENT);
        optionsPanel.setOpaque(false);
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setAlignmentX(LEFT_ALIGNMENT);
        answerStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
        content.add(questionNumberLabel);
        content.add(Box.createVerticalStrut(16));
        content.add(questionTextLabel);
        content.add(Box.createVerticalStrut(18));
        content.add(optionsPanel);
        content.add(Box.createVerticalStrut(18));
        submitAnswerButton.setAlignmentX(LEFT_ALIGNMENT);
        submitAnswerButton.addActionListener(event -> submitSelectedAnswer());
        content.add(submitAnswerButton);
        content.add(Box.createVerticalStrut(10));
        content.add(answerStatusLabel);
        panel.add(createHeader(new JLabel("Current question")), BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        panel.add(createFooter("The quiz engine will provide timing and answer-result events."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createResultsPanel() {
        JPanel panel = createScreenPanel();
        panel.add(createHeader(resultsTitleLabel), BorderLayout.NORTH);
        panel.add(new JLabel("Results will appear when the quiz ends."), BorderLayout.CENTER);
        panel.add(createFooter("The server owns the authoritative leaderboard."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createScreenPanel() {
        JPanel panel = new JPanel(new BorderLayout(20, 20));
        panel.setBorder(BorderFactory.createEmptyBorder(28, 36, 28, 36));
        panel.setBackground(BACKGROUND);
        return panel;
    }

    private JPanel createHeader(JLabel title) {
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(PRIMARY);
        return wrap(title);
    }

    private JPanel createFooter(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.DARK_GRAY);
        return wrap(label);
    }

    private JPanel wrap(JLabel label) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(label, BorderLayout.WEST);
        return panel;
    }

    private JButton createPrimaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(PRIMARY);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        return button;
    }

    private void addFormRow(JPanel form, GridBagConstraints constraints, int row, String label, JTextField field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        form.add(new JLabel(label + ":"), constraints);
        constraints.gridx = 1;
        form.add(field, constraints);
    }

    private void joinFromForm() {
        try {
            String serverHost = serverHostField.getText().trim();
            if (serverHost.isBlank()) {
                throw new IllegalArgumentException("server address is required");
            }
            StudentJoinRequest request = new StudentJoinRequest(roomCodeField.getText(), displayNameField.getText());
            joinErrorLabel.setText(" ");
            joinHandler.accept(serverHost, request);
        } catch (IllegalArgumentException exception) {
            showConnectionError(exception.getMessage());
        }
    }

    private void submitSelectedAnswer() {
        if (displayedQuestionId == null || selectedOptionIndex < 0) {
            answerStatusLabel.setText("Select an answer before submitting.");
            return;
        }
        answerHandler.accept(displayedQuestionId, selectedOptionIndex);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
