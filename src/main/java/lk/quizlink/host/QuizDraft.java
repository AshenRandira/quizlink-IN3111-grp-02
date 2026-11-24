package lk.quizlink.host;

import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable draft representation of a {@link Quiz} for Swing form editing.
 * <p>
 * Manages mutable fields during quiz composition and converts to an immutable
 * {@link Quiz} record upon saving. Automatically generates valid, unique identifiers
 * when needed.
 */
public final class QuizDraft {

    private String id;
    private String title;
    private List<QuestionDraft> questions;

    public QuizDraft() {
        this(null, "", new ArrayList<>());
    }

    public QuizDraft(String id, String title, List<QuestionDraft> questions) {
        this.id = id;
        this.title = title != null ? title : "";
        this.questions = questions != null ? new ArrayList<>(questions) : new ArrayList<>();
    }

    public static QuizDraft fromQuiz(Quiz quiz) {
        if (quiz == null) {
            throw new IllegalArgumentException("Quiz must not be null");
        }
        List<QuestionDraft> draftQuestions = new ArrayList<>();
        for (Question q : quiz.questions()) {
            draftQuestions.add(QuestionDraft.fromQuestion(q));
        }
        return new QuizDraft(quiz.id(), quiz.title(), draftQuestions);
    }

    public QuizDraft copy() {
        List<QuestionDraft> copiedQuestions = new ArrayList<>();
        for (QuestionDraft qd : questions) {
            copiedQuestions.add(qd.copy());
        }
        return new QuizDraft(id, title, copiedQuestions);
    }

    /**
     * Builds and validates an immutable {@link Quiz} record.
     *
     * @return the constructed Quiz
     * @throws IllegalArgumentException if validation rules are violated
     */
    public Quiz toQuiz() {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Quiz title is required and must not be blank");
        }

        String effectiveId = id;
        if (effectiveId == null || effectiveId.isBlank()) {
            effectiveId = generateSlugId(title);
            this.id = effectiveId;
        }

        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("Quiz must contain at least one question");
        }

        // Ensure questions have unique IDs (q1, q2, ...)
        Set<String> usedIds = new HashSet<>();
        List<Question> builtQuestions = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            QuestionDraft draft = questions.get(i);
            String qId = draft.getId();
            if (qId == null || qId.isBlank() || usedIds.contains(qId)) {
                qId = "q" + (i + 1);
                draft.setId(qId);
            }
            usedIds.add(qId);
            builtQuestions.add(draft.toQuestion());
        }

        return new Quiz(effectiveId, title, builtQuestions);
    }

    /**
     * Generates a safe, filesystem-compatible slug identifier matching {@code ^[A-Za-z0-9_-]{1,64}$}.
     *
     * @param inputTitle the human-readable quiz title
     * @return a valid quiz ID
     */
    public static String generateSlugId(String inputTitle) {
        if (inputTitle == null || inputTitle.isBlank()) {
            return "quiz-" + System.currentTimeMillis() % 10000;
        }

        String slug = inputTitle.trim().toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");

        if (slug.isEmpty()) {
            slug = "quiz";
        }

        if (slug.length() > 50) {
            slug = slug.substring(0, 50);
        }

        int suffix = Math.abs(inputTitle.hashCode() % 10000);
        String result = slug + "-" + String.format("%04d", suffix);
        return result.length() > 64 ? result.substring(0, 64) : result;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<QuestionDraft> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDraft> questions) {
        this.questions = questions != null ? new ArrayList<>(questions) : new ArrayList<>();
    }
}
