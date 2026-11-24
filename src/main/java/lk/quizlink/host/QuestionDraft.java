package lk.quizlink.host;

import lk.quizlink.common.model.Question;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable draft representation of a {@link Question} for Swing form editing.
 * <p>
 * Allows intermediate user input in the editor dialog before constructing
 * an immutable, validated {@link Question} record.
 */
public final class QuestionDraft {

    private String id;
    private String text;
    private List<String> options;
    private int correctOptionIndex;
    private int timeLimitSeconds;
    private int points;

    public QuestionDraft() {
        this("q1", "", new ArrayList<>(List.of("", "")), 0, 20, 10);
    }

    public QuestionDraft(String id, String text, List<String> options,
                         int correctOptionIndex, int timeLimitSeconds, int points) {
        this.id = id != null ? id : "q1";
        this.text = text != null ? text : "";
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>(List.of("", ""));
        this.correctOptionIndex = correctOptionIndex;
        this.timeLimitSeconds = timeLimitSeconds > 0 ? timeLimitSeconds : 20;
        this.points = points >= 0 ? points : 10;
    }

    public static QuestionDraft fromQuestion(Question q) {
        if (q == null) {
            throw new IllegalArgumentException("Question must not be null");
        }
        return new QuestionDraft(q.id(), q.text(), q.options(),
                q.correctOptionIndex(), q.timeLimitSeconds(), q.points());
    }

    public QuestionDraft copy() {
        return new QuestionDraft(id, text, options, correctOptionIndex, timeLimitSeconds, points);
    }

    public Question toQuestion() {
        return new Question(id, text, options, correctOptionIndex, timeLimitSeconds, points);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String> options) {
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
    }

    public int getCorrectOptionIndex() {
        return correctOptionIndex;
    }

    public void setCorrectOptionIndex(int correctOptionIndex) {
        this.correctOptionIndex = correctOptionIndex;
    }

    public int getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public void setTimeLimitSeconds(int timeLimitSeconds) {
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }
}
