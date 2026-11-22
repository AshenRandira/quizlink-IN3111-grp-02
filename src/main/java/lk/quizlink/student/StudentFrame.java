package lk.quizlink.student;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
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
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.ListSelectionModel;
import javax.swing.DefaultListModel;
import javax.swing.table.DefaultTableModel;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.QuestionView;

/** Student Swing shell; network work is delegated to {@link StudentClientController}. */
public final class StudentFrame extends JFrame implements StudentClientView {
    private static final String JOIN_CARD = "join";
    private static final String LOBBY_CARD = "lobby";
    private static final String QUESTION_CARD = "question";
    private static final String RESULTS_CARD = "results";
    private static final Color BACKGROUND = new Color(241, 245, 249);
    private static final Color SURFACE = Color.WHITE;
    private static final Color PRIMARY = new Color(30, 64, 175);
    private static final Color PRIMARY_HOVER = new Color(29, 78, 216);
    private static final Color TEXT = new Color(30, 41, 59);
    private static final Color MUTED_TEXT = new Color(71, 85, 105);
    private static final Color BORDER = new Color(203, 213, 225);
    private static final Color ERROR = new Color(185, 28, 28);
    private static final Color SUCCESS = new Color(21, 128, 61);
    private static final Color SUCCESS_BACKGROUND = new Color(220, 252, 231);
    private static final Color OPTION_SELECTED = new Color(219, 234, 254);

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JTextField serverHostField = new JTextField("localhost", 18);
    private final JTextField roomCodeField = new JTextField(18);
    private final JTextField displayNameField = new JTextField(18);
    private final JButton joinButton = createPrimaryButton("Join room");
    private final JLabel joinErrorLabel = new JLabel(" ");
    private final JLabel lobbyRoomLabel = new JLabel();
    private final JLabel lobbyNameLabel = new JLabel();
    private final JLabel lobbyStatusLabel = new JLabel("Connected - waiting for the host");
    private final JLabel participantCountLabel = new JLabel("Participants (0)");
    private final DefaultListModel<String> participantNames = new DefaultListModel<>();
    private final JList<String> participantList = new JList<>(participantNames);
    private final JButton leaveRoomButton = createSecondaryButton("Leave room");
    private final JLabel questionNumberLabel = new JLabel();
    private final JLabel questionTextLabel = new JLabel();
    private final JPanel optionsPanel = new JPanel();
    private final JLabel answerStatusLabel = new JLabel(" ");
    private final JButton submitAnswerButton = createPrimaryButton("Submit answer");
    private final JLabel resultsTitleLabel = new JLabel("Final leaderboard");
    private final JButton joinAnotherRoomButton = createPrimaryButton("Join another room");
    private BiConsumer<String, StudentJoinRequest> joinHandler =
            (host, request) -> showConnectionError("Student client is not configured");
    private BiConsumer<String, Integer> answerHandler =
            (questionId, optionIndex) -> showConnectionError("Student client is not configured");
    private String displayedQuestionId;
    private int selectedOptionIndex = -1;
    private Runnable leaveHandler = this::showJoinScreen;

    public StudentFrame() {
        super("QuizLink Student");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540));
        setSize(820, 580);
        setLocationByPlatform(true);
        cardPanel.setBackground(BACKGROUND);
        configureTextField(serverHostField, "Example: 192.168.1.10 or localhost");
        configureTextField(roomCodeField, "Six-character room code");
        configureTextField(displayNameField, "Name shown to other students");
        configureAccessibility();
        participantList.setFont(participantList.getFont().deriveFont(14f));
        participantList.setForeground(TEXT);
        participantList.setBackground(SURFACE);
        participantList.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        cardPanel.add(createJoinPanel(), JOIN_CARD);
        cardPanel.add(createLobbyPanel(), LOBBY_CARD);
        cardPanel.add(createQuestionPanel(), QUESTION_CARD);
        cardPanel.add(createResultsPanel(), RESULTS_CARD);
        joinAnotherRoomButton.addActionListener(event -> showJoinScreen());
        serverHostField.addActionListener(event -> joinFromForm());
        roomCodeField.addActionListener(event -> joinFromForm());
        displayNameField.addActionListener(event -> joinFromForm());
        add(cardPanel);
        showJoinScreen();
    }

    /** Shows the form used before the TCP client sends a join request. */
    @Override
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
    public void setLeaveHandler(Runnable leaveHandler) {
        if (leaveHandler == null) {
            throw new IllegalArgumentException("leaveHandler is required");
        }
        this.leaveHandler = leaveHandler;
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
        joinErrorLabel.setForeground(MUTED_TEXT);
        joinErrorLabel.setText("Connecting to the QuizLink server...");
    }

    /** Shows the waiting state after the server confirms the join. */
    @Override
    public void showLobbyScreen(StudentJoinRequest request) {
        joinButton.setEnabled(true);
        lobbyRoomLabel.setText("Room: " + request.roomCode());
        lobbyNameLabel.setText("Name: " + request.displayName());
        participantNames.clear();
        participantCountLabel.setText("Participants (0)");
        cards.show(cardPanel, LOBBY_CARD);
    }

    @Override
    public void showParticipants(List<Participant> participants) {
        participantNames.clear();
        for (Participant participant : participants) {
            participantNames.addElement(participant.displayName());
        }
        participantCountLabel.setText("Participants (" + participants.size() + ")");
    }

    @Override
    public void showConnectionError(String message) {
        joinButton.setEnabled(true);
        joinErrorLabel.setForeground(ERROR);
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
            option.setForeground(TEXT);
            option.setFont(option.getFont().deriveFont(14f));
            option.setAlignmentX(LEFT_ALIGNMENT);
            option.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
            styleAnswerOption(option);
            option.setActionCommand(Integer.toString(index));
            option.addChangeListener(event -> styleAnswerOption(option));
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
        table.setRowHeight(28);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        JPanel resultsPanel = (JPanel) cardPanel.getComponent(3);
        resultsPanel.removeAll();
        resultsPanel.add(createHeader(resultsTitleLabel,
                "See how everyone performed in this quiz."), BorderLayout.NORTH);
        resultsPanel.add(createContentCard(new JScrollPane(table)), BorderLayout.CENTER);
        resultsPanel.add(createResultsFooter(finalResults), BorderLayout.SOUTH);
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

        joinErrorLabel.setForeground(ERROR);
        constraints.gridy = 4;
        form.add(joinErrorLabel, constraints);

        panel.add(createHeader(new JLabel("Join a QuizLink room"),
                "Enter the details shared by your quiz host."), BorderLayout.NORTH);
        panel.add(createContentCard(form), BorderLayout.CENTER);
        panel.add(createFooter("Use localhost for a quiz running on this computer."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createLobbyPanel() {
        JPanel panel = createScreenPanel();
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        lobbyRoomLabel.setAlignmentX(CENTER_ALIGNMENT);
        lobbyNameLabel.setAlignmentX(CENTER_ALIGNMENT);
        lobbyRoomLabel.setFont(lobbyRoomLabel.getFont().deriveFont(Font.BOLD, 16f));
        lobbyRoomLabel.setForeground(TEXT);
        lobbyNameLabel.setForeground(MUTED_TEXT);
        lobbyStatusLabel.setAlignmentX(CENTER_ALIGNMENT);
        lobbyStatusLabel.setForeground(SUCCESS);
        lobbyStatusLabel.setBackground(SUCCESS_BACKGROUND);
        lobbyStatusLabel.setOpaque(true);
        lobbyStatusLabel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        content.add(Box.createVerticalGlue());
        content.add(lobbyRoomLabel);
        content.add(Box.createVerticalStrut(8));
        content.add(lobbyNameLabel);
        content.add(Box.createVerticalStrut(28));
        content.add(lobbyStatusLabel);
        content.add(Box.createVerticalStrut(18));
        participantCountLabel.setAlignmentX(CENTER_ALIGNMENT);
        content.add(participantCountLabel);
        JScrollPane participantScrollPane = new JScrollPane(participantList);
        participantScrollPane.setAlignmentX(CENTER_ALIGNMENT);
        participantScrollPane.setPreferredSize(new Dimension(320, 120));
        participantScrollPane.setMaximumSize(new Dimension(320, 120));
        content.add(Box.createVerticalStrut(6));
        content.add(participantScrollPane);
        leaveRoomButton.setAlignmentX(CENTER_ALIGNMENT);
        leaveRoomButton.addActionListener(event -> leaveHandler.run());
        content.add(Box.createVerticalStrut(16));
        content.add(leaveRoomButton);
        content.add(Box.createVerticalGlue());
        panel.add(createHeader(new JLabel("Quiz lobby"),
                "You are in the room. Invitees will appear below."), BorderLayout.NORTH);
        panel.add(createContentCard(content), BorderLayout.CENTER);
        panel.add(createFooter("The host will start the quiz when everyone is ready."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createQuestionPanel() {
        JPanel panel = createScreenPanel();
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        questionNumberLabel.setAlignmentX(LEFT_ALIGNMENT);
        questionNumberLabel.setForeground(Color.WHITE);
        questionNumberLabel.setBackground(PRIMARY);
        questionNumberLabel.setOpaque(true);
        questionNumberLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        questionNumberLabel.setFont(questionNumberLabel.getFont().deriveFont(Font.BOLD, 13f));
        questionTextLabel.setAlignmentX(LEFT_ALIGNMENT);
        questionTextLabel.setForeground(TEXT);
        questionTextLabel.setFont(questionTextLabel.getFont().deriveFont(Font.BOLD, 18f));
        optionsPanel.setOpaque(false);
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setAlignmentX(LEFT_ALIGNMENT);
        answerStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
        answerStatusLabel.setForeground(MUTED_TEXT);
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
        panel.add(createHeader(new JLabel("Current question"),
                "Choose one answer, then submit it before time runs out."), BorderLayout.NORTH);
        JScrollPane questionScrollPane = new JScrollPane(content);
        questionScrollPane.setBorder(BorderFactory.createEmptyBorder());
        questionScrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        questionScrollPane.getViewport().setBackground(SURFACE);
        panel.add(createContentCard(questionScrollPane), BorderLayout.CENTER);
        panel.add(createFooter("Your answer is sent once and confirmed by the server."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createResultsPanel() {
        JPanel panel = createScreenPanel();
        panel.add(createHeader(resultsTitleLabel,
                "Your final ranking will appear here when the quiz ends."), BorderLayout.NORTH);
        JLabel placeholder = new JLabel("Results will appear when the quiz ends.");
        placeholder.setForeground(MUTED_TEXT);
        panel.add(createContentCard(placeholder), BorderLayout.CENTER);
        panel.add(createResultsFooter(true), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createResultsFooter(boolean finalResults) {
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.add(createFooter("The server owns the authoritative leaderboard."), BorderLayout.WEST);
        if (finalResults) {
            footer.add(joinAnotherRoomButton, BorderLayout.EAST);
        }
        return footer;
    }

    private JPanel createScreenPanel() {
        JPanel panel = new JPanel(new BorderLayout(18, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(30, 52, 30, 52));
        panel.setBackground(BACKGROUND);
        return panel;
    }

    /** Creates the white, bordered content surface shared by each screen. */
    private JPanel createContentCard(java.awt.Component content) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(24, 28, 24, 28)));
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JPanel createHeader(JLabel title) {
        return createHeader(title, null);
    }

    private JPanel createHeader(JLabel title, String subtitle) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        title.setForeground(PRIMARY);
        title.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(title);
        if (subtitle != null && !subtitle.isBlank()) {
            JLabel subtitleLabel = new JLabel(subtitle);
            subtitleLabel.setForeground(MUTED_TEXT);
            subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(13f));
            subtitleLabel.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(Box.createVerticalStrut(5));
            panel.add(subtitleLabel);
        }
        return panel;
    }

    private JPanel createFooter(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED_TEXT);
        label.setFont(label.getFont().deriveFont(12f));
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
        button.setFont(button.getFont().deriveFont(Font.BOLD, 14f));
        button.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setOpaque(true);
        button.setRolloverEnabled(true);
        button.addChangeListener(event -> button.setBackground(
                button.getModel().isRollover() ? PRIMARY_HOVER : PRIMARY));
        return button;
    }

    private JButton createSecondaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(SURFACE);
        button.setForeground(PRIMARY);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(9, 16, 9, 16)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private void configureTextField(JTextField field, String tooltip) {
        field.setFont(field.getFont().deriveFont(14f));
        field.setToolTipText(tooltip);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    /** Adds keyboard shortcuts and screen-reader descriptions for common student actions. */
    private void configureAccessibility() {
        serverHostField.getAccessibleContext().setAccessibleName("Server address");
        roomCodeField.getAccessibleContext().setAccessibleName("Room code");
        displayNameField.getAccessibleContext().setAccessibleName("Display name");
        joinButton.setMnemonic('J');
        joinButton.getAccessibleContext().setAccessibleDescription(
                "Connect to the specified QuizLink room.");
        submitAnswerButton.setMnemonic('S');
        submitAnswerButton.getAccessibleContext().setAccessibleDescription(
                "Submit the selected answer to the quiz server.");
        leaveRoomButton.setMnemonic('L');
        leaveRoomButton.getAccessibleContext().setAccessibleDescription(
                "Leave the current quiz room and return to the join screen.");
        joinAnotherRoomButton.setMnemonic('A');
        joinAnotherRoomButton.getAccessibleContext().setAccessibleDescription(
                "Return to the join screen after viewing final results.");
    }

    /** Styles an answer as a full-width card and highlights the selected option. */
    private void styleAnswerOption(JRadioButton option) {
        boolean selected = option.getModel().isSelected();
        option.setBackground(selected ? OPTION_SELECTED : SURFACE);
        option.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? PRIMARY : BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        option.setOpaque(true);
    }

    private void addFormRow(JPanel form, GridBagConstraints constraints, int row, String label, JTextField field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        JLabel formLabel = new JLabel(label + ":");
        formLabel.setForeground(TEXT);
        formLabel.setFont(formLabel.getFont().deriveFont(Font.BOLD));
        formLabel.setLabelFor(field);
        form.add(formLabel, constraints);
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
