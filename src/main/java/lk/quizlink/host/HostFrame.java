package lk.quizlink.host;

import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.ProtocolConstants;
import lk.quizlink.multicast.Announcement;
import lk.quizlink.multicast.AnnouncementSender;
import lk.quizlink.storage.QuizRepository;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Main administration and dashboard window for the QuizLink host.
 * <p>
 * Integrates quiz authoring and persistence (via {@link QuizRepository}),
 * server communication (via {@link HostGateway}), and multicast room
 * announcements (via {@link AnnouncementSender}).
 */
public final class HostFrame extends JFrame {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final QuizRepository repository;
    private HostGateway gateway;
    private AnnouncementSender announcementSender;

    private QuizDraft currentDraft = new QuizDraft();
    private String currentRoomCode = null;
    private boolean quizStarted = false;

    // Connection components
    private final JTextField hostField = new JTextField("localhost", 10);
    private final JTextField portField = new JTextField(String.valueOf(ProtocolConstants.TCP_PORT), 5);
    private final JButton connectButton = new JButton("Connect");
    private final JCheckBox offlineDemoCheckBox = new JCheckBox("Offline demo mode");
    private final JLabel statusLabel = new JLabel("Disconnected");

    // Catalogue components
    private final DefaultListModel<Quiz> catalogueListModel = new DefaultListModel<>();
    private final JList<Quiz> catalogueList = new JList<>(catalogueListModel);
    private final JButton newQuizButton = new JButton("New");
    private final JButton deleteQuizButton = new JButton("Delete");
    private final JButton reloadCatalogueButton = new JButton("Reload");

    // Editor components
    private final JTextField quizTitleField = new JTextField(20);
    private final DefaultTableModel questionTableModel = new DefaultTableModel(
            new Object[]{"#", "Question Text", "Correct Answer", "Time (s)", "Points"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable questionTable = new JTable(questionTableModel);
    private final JButton addQuestionButton = new JButton("Add Question");
    private final JButton editQuestionButton = new JButton("Edit Question");
    private final JButton deleteQuestionButton = new JButton("Delete Question");
    private final JButton saveQuizButton = new JButton("Save Quiz");

    // Session components
    private final JButton createRoomButton = new JButton("Create Room");
    private final JLabel roomCodeLabel = new JLabel("------", SwingConstants.CENTER);
    private final JButton startQuizButton = new JButton("Start Quiz");
    private final JButton endQuizButton = new JButton("End Quiz");

    // Announcement components
    private final JTextField announcementField = new JTextField(18);
    private final JButton sendAnnouncementButton = new JButton("Send Announcement");

    // Event log
    private final JTextArea eventLogArea = new JTextArea(10, 24);

    public HostFrame() {
        super("QuizLink Host Dashboard");
        this.repository = new QuizRepository();

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 680));
        setSize(1120, 720);
        setLocationRelativeTo(null);

        initComponents();
        setupListeners();
        reloadCatalogue();
        updateSessionState();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanupResources();
            }
        });
    }

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));

        // 1. Connection bar (North)
        add(createConnectionBar(), BorderLayout.NORTH);

        // 2. Main content split into Left (Catalogue), Centre (Editor), Right (Session & Log)
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        mainPanel.add(createCataloguePanel(), BorderLayout.WEST);
        mainPanel.add(createEditorPanel(), BorderLayout.CENTER);
        mainPanel.add(createSessionPanel(), BorderLayout.EAST);

        add(mainPanel, BorderLayout.CENTER);
    }

    private JPanel createConnectionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        bar.setBorder(BorderFactory.createEtchedBorder());

        bar.add(new JLabel("Host:"));
        bar.add(hostField);
        bar.add(new JLabel("Port:"));
        bar.add(portField);
        bar.add(connectButton);
        bar.add(offlineDemoCheckBox);

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusLabel.setForeground(Color.RED.darker());
        bar.add(Box.createHorizontalStrut(15));
        bar.add(new JLabel("Status:"));
        bar.add(statusLabel);

        return bar;
    }

    private JPanel createCataloguePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setPreferredSize(new Dimension(220, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Quiz Catalogue"));

        catalogueList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        catalogueList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Quiz q) {
                    setText(q.title() + " (" + q.questions().size() + " Qs)");
                }
                return this;
            }
        });

        panel.add(new JScrollPane(catalogueList), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        buttonPanel.add(newQuizButton);
        buttonPanel.add(deleteQuizButton);
        buttonPanel.add(reloadCatalogueButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Quiz Editor"));

        // Title row
        JPanel titlePanel = new JPanel(new BorderLayout(6, 6));
        titlePanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        titlePanel.add(new JLabel("Quiz Title:"), BorderLayout.WEST);
        titlePanel.add(quizTitleField, BorderLayout.CENTER);
        panel.add(titlePanel, BorderLayout.NORTH);

        // Questions table
        questionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        questionTable.getColumnModel().getColumn(0).setPreferredWidth(35);
        questionTable.getColumnModel().getColumn(1).setPreferredWidth(250);
        questionTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        questionTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        questionTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        panel.add(new JScrollPane(questionTable), BorderLayout.CENTER);

        // Buttons under table
        JPanel footerPanel = new JPanel(new BorderLayout());
        JPanel editButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        editButtons.add(addQuestionButton);
        editButtons.add(editQuestionButton);
        editButtons.add(deleteQuestionButton);

        JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        saveQuizButton.setFont(saveQuizButton.getFont().deriveFont(Font.BOLD));
        savePanel.add(saveQuizButton);

        footerPanel.add(editButtons, BorderLayout.WEST);
        footerPanel.add(savePanel, BorderLayout.EAST);
        panel.add(footerPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createSessionPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setPreferredSize(new Dimension(320, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Session & Events"));

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        // Room Code block
        JPanel roomCodeBox = new JPanel(new BorderLayout(4, 4));
        roomCodeBox.setBorder(BorderFactory.createTitledBorder("Active Room Code"));
        roomCodeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 26));
        roomCodeLabel.setForeground(new Color(24, 76, 150));
        roomCodeBox.add(roomCodeLabel, BorderLayout.CENTER);
        controls.add(roomCodeBox);
        controls.add(Box.createVerticalStrut(6));

        // Room and quiz buttons
        JPanel buttonBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        buttonBox.add(createRoomButton);
        buttonBox.add(startQuizButton);
        buttonBox.add(endQuizButton);
        controls.add(buttonBox);
        controls.add(Box.createVerticalStrut(6));

        // Multicast announcements
        JPanel announcementBox = new JPanel(new BorderLayout(4, 4));
        announcementBox.setBorder(BorderFactory.createTitledBorder("LAN Announcement (Multicast)"));
        announcementBox.add(announcementField, BorderLayout.CENTER);
        announcementBox.add(sendAnnouncementButton, BorderLayout.SOUTH);
        controls.add(announcementBox);
        controls.add(Box.createVerticalStrut(8));

        panel.add(controls, BorderLayout.NORTH);

        // Event log
        JPanel logPanel = new JPanel(new BorderLayout(4, 4));
        logPanel.setBorder(BorderFactory.createTitledBorder("Event Log"));
        eventLogArea.setEditable(false);
        eventLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        eventLogArea.setLineWrap(true);
        eventLogArea.setWrapStyleWord(true);
        logPanel.add(new JScrollPane(eventLogArea), BorderLayout.CENTER);
        panel.add(logPanel, BorderLayout.CENTER);

        return panel;
    }

    private void setupListeners() {
        // Connection & Demo mode
        offlineDemoCheckBox.addActionListener(e -> {
            boolean demo = offlineDemoCheckBox.isSelected();
            hostField.setEnabled(!demo);
            portField.setEnabled(!demo);
            if (demo) {
                connectOfflineDemo();
            } else if (gateway instanceof MockHostGateway) {
                disconnectGateway();
            }
        });

        connectButton.addActionListener(e -> {
            if (gateway != null) {
                disconnectGateway();
            } else if (offlineDemoCheckBox.isSelected()) {
                connectOfflineDemo();
            } else {
                connectTcpGateway();
            }
        });

        // Catalogue listeners
        catalogueList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Quiz selected = catalogueList.getSelectedValue();
                if (selected != null) {
                    loadQuizIntoEditor(selected);
                }
            }
        });

        newQuizButton.addActionListener(e -> {
            catalogueList.clearSelection();
            currentDraft = new QuizDraft();
            quizTitleField.setText("");
            refreshQuestionTable();
            logEvent("Started new empty quiz draft.");
        });

        deleteQuizButton.addActionListener(e -> {
            Quiz selected = catalogueList.getSelectedValue();
            if (selected == null) {
                JOptionPane.showMessageDialog(this, "Select a quiz to delete.", "Information", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int opt = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to delete quiz \"" + selected.title() + "\"?",
                    "Confirm Deletion", JOptionPane.YES_NO_OPTION);
            if (opt == JOptionPane.YES_OPTION) {
                boolean deleted = repository.delete(selected.id());
                if (deleted) {
                    logEvent("Deleted quiz: " + selected.title());
                    reloadCatalogue();
                    newQuizButton.doClick();
                } else {
                    JOptionPane.showMessageDialog(this, "Could not delete quiz file.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        reloadCatalogueButton.addActionListener(e -> reloadCatalogue());

        // Editor listeners
        addQuestionButton.addActionListener(e -> {
            QuestionDraft newQuestion = new QuestionDraft();
            newQuestion.setId("q" + (currentDraft.getQuestions().size() + 1));
            QuestionEditorDialog dialog = new QuestionEditorDialog(this, newQuestion);
            dialog.setVisible(true);
            if (dialog.isSaved()) {
                currentDraft.getQuestions().add(newQuestion);
                refreshQuestionTable();
                logEvent("Added question: " + newQuestion.getText());
            }
        });

        editQuestionButton.addActionListener(e -> {
            int selectedRow = questionTable.getSelectedRow();
            if (selectedRow < 0 || selectedRow >= currentDraft.getQuestions().size()) {
                JOptionPane.showMessageDialog(this, "Select a question to edit.", "Information", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            QuestionDraft target = currentDraft.getQuestions().get(selectedRow);
            QuestionDraft copy = target.copy();
            QuestionEditorDialog dialog = new QuestionEditorDialog(this, copy);
            dialog.setVisible(true);
            if (dialog.isSaved()) {
                currentDraft.getQuestions().set(selectedRow, copy);
                refreshQuestionTable();
                logEvent("Updated question #" + (selectedRow + 1));
            }
        });

        deleteQuestionButton.addActionListener(e -> {
            int selectedRow = questionTable.getSelectedRow();
            if (selectedRow < 0 || selectedRow >= currentDraft.getQuestions().size()) {
                JOptionPane.showMessageDialog(this, "Select a question to delete.", "Information", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            currentDraft.getQuestions().remove(selectedRow);
            refreshQuestionTable();
            logEvent("Removed question #" + (selectedRow + 1));
        });

        saveQuizButton.addActionListener(e -> saveCurrentQuiz());

        // Session controls
        createRoomButton.addActionListener(e -> onCreateRoom());
        startQuizButton.addActionListener(e -> onStartQuiz());
        endQuizButton.addActionListener(e -> onEndQuiz());

        // Announcements
        sendAnnouncementButton.addActionListener(e -> onSendAnnouncement());
    }

    private void connectOfflineDemo() {
        disconnectGateway();
        MockHostGateway mock = new MockHostGateway();
        setGateway(mock, "Offline demo");
        logEvent("Enabled offline demo mode using MockHostGateway.");
    }

    private void connectTcpGateway() {
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port number.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        connectButton.setEnabled(false);
        statusLabel.setText("Connecting...");
        statusLabel.setForeground(Color.ORANGE.darker());
        logEvent("Connecting to server at " + host + ":" + port + "...");

        CompletableFuture.runAsync(() -> {
            try {
                TcpHostGateway tcpGateway = new TcpHostGateway(host, port);
                SwingUtilities.invokeLater(() -> {
                    setGateway(tcpGateway, "Connected (" + host + ":" + port + ")");
                    logEvent("Connected to server successfully.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    disconnectGateway();
                    JOptionPane.showMessageDialog(HostFrame.this,
                            "Failed to connect to " + host + ":" + port + "\n" + ex.getMessage(),
                            "Connection Error", JOptionPane.ERROR_MESSAGE);
                    logEvent("Connection failed: " + ex.getMessage());
                });
            }
        });
    }

    private void setGateway(HostGateway newGateway, String statusText) {
        this.gateway = newGateway;
        this.gateway.setEventListener(this::onServerEvent);
        this.gateway.setDisconnectListener(() -> SwingUtilities.invokeLater(() -> {
            logEvent("Disconnected from server.");
            disconnectGateway();
        }));

        connectButton.setText("Disconnect");
        connectButton.setEnabled(true);
        statusLabel.setText(statusText);
        statusLabel.setForeground(new Color(20, 130, 40));
        updateSessionState();
    }

    private void disconnectGateway() {
        if (gateway != null) {
            try {
                gateway.close();
            } catch (Exception ignored) {
            }
            gateway = null;
        }

        currentRoomCode = null;
        quizStarted = false;
        roomCodeLabel.setText("------");

        connectButton.setText("Connect");
        connectButton.setEnabled(true);
        statusLabel.setText("Disconnected");
        statusLabel.setForeground(Color.RED.darker());
        updateSessionState();
    }

    private void onServerEvent(Message message) {
        SwingUtilities.invokeLater(() -> {
            String eventInfo = message.type() + (message.payload() != null ? ": " + message.payload() : "");
            logEvent("Server Event -> " + eventInfo);
        });
    }

    private void saveCurrentQuiz() {
        currentDraft.setTitle(quizTitleField.getText().trim());
        try {
            Quiz quiz = currentDraft.toQuiz();
            repository.save(quiz);
            reloadCatalogue();
            logEvent("Saved quiz: \"" + quiz.title() + "\" [ID: " + quiz.id() + "]");
            JOptionPane.showMessageDialog(this, "Quiz saved successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Validation Error", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to save quiz: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onCreateRoom() {
        if (gateway == null) {
            JOptionPane.showMessageDialog(this, "Connect to a server or enable offline demo mode first.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        currentDraft.setTitle(quizTitleField.getText().trim());
        Quiz quiz;
        try {
            quiz = currentDraft.toQuiz();
            repository.save(quiz);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Please fix quiz errors before creating a room:\n" + ex.getMessage(),
                    "Validation Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        createRoomButton.setEnabled(false);
        logEvent("Requesting room creation for \"" + quiz.title() + "\"...");

        gateway.createRoom(quiz).whenComplete((code, error) -> SwingUtilities.invokeLater(() -> {
            createRoomButton.setEnabled(true);
            if (error != null) {
                handleGatewayError("Create Room Failed", error);
            } else {
                currentRoomCode = code;
                quizStarted = false;
                roomCodeLabel.setText(code);
                logEvent("Room created! Room Code: " + code);
                updateSessionState();
            }
        }));
    }

    private void onStartQuiz() {
        if (gateway == null || currentRoomCode == null) {
            return;
        }

        startQuizButton.setEnabled(false);
        logEvent("Starting quiz in room " + currentRoomCode + "...");

        gateway.startQuiz(currentRoomCode).whenComplete((res, error) -> SwingUtilities.invokeLater(() -> {
            startQuizButton.setEnabled(true);
            if (error != null) {
                handleGatewayError("Start Quiz Failed", error);
            } else {
                quizStarted = true;
                logEvent("Quiz is now RUNNING in room: " + currentRoomCode);
                updateSessionState();
            }
        }));
    }

    private void onEndQuiz() {
        if (gateway == null || currentRoomCode == null) {
            return;
        }

        endQuizButton.setEnabled(false);
        logEvent("Ending quiz in room " + currentRoomCode + "...");

        gateway.endQuiz(currentRoomCode).whenComplete((res, error) -> SwingUtilities.invokeLater(() -> {
            endQuizButton.setEnabled(true);
            if (error != null) {
                handleGatewayError("End Quiz Failed", error);
            } else {
                logEvent("Quiz ended in room: " + currentRoomCode);
                currentRoomCode = null;
                quizStarted = false;
                roomCodeLabel.setText("------");
                updateSessionState();
            }
        }));
    }

    private void onSendAnnouncement() {
        if (currentRoomCode == null) {
            JOptionPane.showMessageDialog(this, "Cannot send announcement without an active room.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String text = announcementField.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        try {
            Announcement announcement = new Announcement(currentRoomCode, text);
            if (announcementSender == null) {
                announcementSender = new AnnouncementSender();
            }
            announcementSender.send(announcement);
            logEvent("Multicast Announcement sent: \"" + text + "\"");
            announcementField.setText("");
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Announcement Validation Error", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to send announcement: " + ex.getMessage(), "Network Error", JOptionPane.ERROR_MESSAGE);
            logEvent("Announcement error: " + ex.getMessage());
        }
    }

    private void handleGatewayError(String title, Throwable error) {
        String message = error.getMessage();
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            message = error.getCause().getMessage();
        }
        logEvent(title + ": " + message);
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private void updateSessionState() {
        boolean connected = gateway != null;
        boolean hasRoom = currentRoomCode != null;

        createRoomButton.setEnabled(connected && !hasRoom);
        startQuizButton.setEnabled(connected && hasRoom && !quizStarted);
        endQuizButton.setEnabled(connected && hasRoom && quizStarted);
        sendAnnouncementButton.setEnabled(hasRoom);
    }

    private void loadQuizIntoEditor(Quiz quiz) {
        currentDraft = QuizDraft.fromQuiz(quiz);
        quizTitleField.setText(quiz.title());
        refreshQuestionTable();
        logEvent("Loaded quiz: " + quiz.title() + " (" + quiz.questions().size() + " questions)");
    }

    private void refreshQuestionTable() {
        questionTableModel.setRowCount(0);
        List<QuestionDraft> questions = currentDraft.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            QuestionDraft q = questions.get(i);
            String correctOptionText = "";
            if (q.getCorrectOptionIndex() >= 0 && q.getCorrectOptionIndex() < q.getOptions().size()) {
                correctOptionText = q.getOptions().get(q.getCorrectOptionIndex());
            }
            questionTableModel.addRow(new Object[]{
                    (i + 1),
                    q.getText(),
                    correctOptionText,
                    q.getTimeLimitSeconds(),
                    q.getPoints()
            });
        }
    }

    private void reloadCatalogue() {
        catalogueListModel.clear();
        List<Quiz> quizzes = repository.list();
        for (Quiz q : quizzes) {
            catalogueListModel.addElement(q);
        }
        logEvent("Catalogue refreshed (" + quizzes.size() + " quizzes available).");
    }

    private void logEvent(String message) {
        String timestamp = LocalTime.now().format(TIME_FMT);
        eventLogArea.append("[" + timestamp + "] " + message + "\n");
        eventLogArea.setCaretPosition(eventLogArea.getDocument().getLength());
    }

    private void cleanupResources() {
        if (gateway != null) {
            try {
                gateway.close();
            } catch (Exception ignored) {
            }
            gateway = null;
        }
        if (announcementSender != null) {
            try {
                announcementSender.close();
            } catch (Exception ignored) {
            }
            announcementSender = null;
        }
    }
}
