package lk.quizlink.host;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal dialog for composing and editing individual quiz questions.
 * <p>
 * Supports dynamic configuration of 2 to 6 options with radio-button selection
 * of the correct option, alongside time limit and point controls.
 */
public final class QuestionEditorDialog extends JDialog {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 6;

    private final QuestionDraft draft;
    private boolean saved = false;

    private final JTextArea questionTextArea = new JTextArea(3, 30);
    private final JPanel optionsListPanel = new JPanel();
    private final ButtonGroup correctOptionGroup = new ButtonGroup();
    private final List<OptionRow> optionRows = new ArrayList<>();

    private final JButton addOptionButton = new JButton("+ Add Option");
    private final JButton removeOptionButton = new JButton("- Remove Option");

    private final JSpinner timeLimitSpinner = new JSpinner(new SpinnerNumberModel(20, 5, 300, 1));
    private final JSpinner pointsSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 100, 1));

    private static class OptionRow {
        final JPanel panel = new JPanel(new BorderLayout(8, 0));
        final JRadioButton radioButton = new JRadioButton();
        final JTextField textField = new JTextField(25);

        OptionRow(int index, String text, ButtonGroup group, boolean isSelected) {
            radioButton.setSelected(isSelected);
            textField.setText(text != null ? text : "");
            group.add(radioButton);

            panel.add(radioButton, BorderLayout.WEST);
            panel.add(textField, BorderLayout.CENTER);
        }
    }

    public QuestionEditorDialog(JFrame parent, QuestionDraft draft) {
        super(parent, "Question Editor", true);
        this.draft = draft;

        setLayout(new BorderLayout(10, 10));
        setSize(520, 480);
        setLocationRelativeTo(parent);

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Question text
        JPanel textPanel = new JPanel(new BorderLayout(5, 5));
        textPanel.setBorder(BorderFactory.createTitledBorder("Question Text"));
        questionTextArea.setLineWrap(true);
        questionTextArea.setWrapStyleWord(true);
        questionTextArea.setText(draft.getText());
        textPanel.add(new JScrollPane(questionTextArea), BorderLayout.CENTER);
        contentPanel.add(textPanel);
        contentPanel.add(Box.createVerticalStrut(8));

        // Options section
        JPanel optionsContainer = new JPanel(new BorderLayout(5, 5));
        optionsContainer.setBorder(BorderFactory.createTitledBorder("Options (select correct answer)"));

        optionsListPanel.setLayout(new BoxLayout(optionsListPanel, BoxLayout.Y_AXIS));
        initOptionRows();
        optionsContainer.add(new JScrollPane(optionsListPanel), BorderLayout.CENTER);

        JPanel optionControlButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        addOptionButton.addActionListener(e -> addOptionField(""));
        removeOptionButton.addActionListener(e -> removeLastOptionField());
        optionControlButtons.add(addOptionButton);
        optionControlButtons.add(removeOptionButton);
        optionsContainer.add(optionControlButtons, BorderLayout.SOUTH);

        contentPanel.add(optionsContainer);
        contentPanel.add(Box.createVerticalStrut(8));

        // Spinners for timing and points
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBorder(BorderFactory.createTitledBorder("Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        settingsPanel.add(new JLabel("Time Limit (seconds):"), gbc);
        gbc.gridx = 1;
        timeLimitSpinner.setValue(draft.getTimeLimitSeconds() > 0 ? draft.getTimeLimitSeconds() : 20);
        settingsPanel.add(timeLimitSpinner, gbc);

        gbc.gridx = 2;
        settingsPanel.add(new JLabel("Points:"), gbc);
        gbc.gridx = 3;
        pointsSpinner.setValue(draft.getPoints() >= 0 ? draft.getPoints() : 10);
        settingsPanel.add(pointsSpinner, gbc);

        contentPanel.add(settingsPanel);
        add(contentPanel, BorderLayout.CENTER);

        // Action buttons
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        JButton saveButton = new JButton("Save Question");
        JButton cancelButton = new JButton("Cancel");

        saveButton.addActionListener(e -> onSave());
        cancelButton.addActionListener(e -> dispose());

        actionsPanel.add(cancelButton);
        actionsPanel.add(saveButton);
        add(actionsPanel, BorderLayout.SOUTH);

        updateOptionButtons();
    }

    private void initOptionRows() {
        List<String> options = draft.getOptions();
        if (options == null || options.isEmpty()) {
            options = List.of("", "");
        }

        int correctIndex = draft.getCorrectOptionIndex();
        if (correctIndex < 0 || correctIndex >= options.size()) {
            correctIndex = 0;
        }

        for (int i = 0; i < options.size(); i++) {
            OptionRow row = new OptionRow(i, options.get(i), correctOptionGroup, i == correctIndex);
            optionRows.add(row);
            optionsListPanel.add(row.panel);
            optionsListPanel.add(Box.createVerticalStrut(4));
        }
    }

    private void addOptionField(String text) {
        if (optionRows.size() >= MAX_OPTIONS) {
            return;
        }
        int index = optionRows.size();
        OptionRow row = new OptionRow(index, text, correctOptionGroup, false);
        optionRows.add(row);
        optionsListPanel.add(row.panel);
        optionsListPanel.add(Box.createVerticalStrut(4));

        optionsListPanel.revalidate();
        optionsListPanel.repaint();
        updateOptionButtons();
    }

    private void removeLastOptionField() {
        if (optionRows.size() <= MIN_OPTIONS) {
            return;
        }
        int lastIndex = optionRows.size() - 1;
        OptionRow removed = optionRows.remove(lastIndex);
        correctOptionGroup.remove(removed.radioButton);

        optionsListPanel.remove(removed.panel);
        // Also remove the spacer strut
        if (optionsListPanel.getComponentCount() > 0) {
            optionsListPanel.remove(optionsListPanel.getComponentCount() - 1);
        }

        // If the removed option was selected, select the first option
        boolean anySelected = optionRows.stream().anyMatch(r -> r.radioButton.isSelected());
        if (!anySelected && !optionRows.isEmpty()) {
            optionRows.get(0).radioButton.setSelected(true);
        }

        optionsListPanel.revalidate();
        optionsListPanel.repaint();
        updateOptionButtons();
    }

    private void updateOptionButtons() {
        addOptionButton.setEnabled(optionRows.size() < MAX_OPTIONS);
        removeOptionButton.setEnabled(optionRows.size() > MIN_OPTIONS);
    }

    private void onSave() {
        String text = questionTextArea.getText().trim();
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Question text cannot be blank.",
                    "Validation Error", JOptionPane.WARNING_MESSAGE);
            questionTextArea.requestFocusInWindow();
            return;
        }

        List<String> options = new ArrayList<>();
        int selectedIndex = -1;

        for (int i = 0; i < optionRows.size(); i++) {
            OptionRow row = optionRows.get(i);
            String optText = row.textField.getText().trim();
            if (optText.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Option " + (i + 1) + " cannot be blank.",
                        "Validation Error", JOptionPane.WARNING_MESSAGE);
                row.textField.requestFocusInWindow();
                return;
            }
            options.add(optText);
            if (row.radioButton.isSelected()) {
                selectedIndex = i;
            }
        }

        if (selectedIndex < 0) {
            JOptionPane.showMessageDialog(this, "Please select the correct option.",
                    "Validation Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        draft.setText(text);
        draft.setOptions(options);
        draft.setCorrectOptionIndex(selectedIndex);
        draft.setTimeLimitSeconds((Integer) timeLimitSpinner.getValue());
        draft.setPoints((Integer) pointsSpinner.getValue());

        saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }
}
