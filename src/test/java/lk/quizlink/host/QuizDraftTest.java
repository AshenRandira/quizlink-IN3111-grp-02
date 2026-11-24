package lk.quizlink.host;

import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizDraftTest {

    private static QuestionDraft createSampleQuestionDraft(String id, String text, int correctIndex) {
        return new QuestionDraft(id, text, List.of("Option A", "Option B", "Option C"), correctIndex, 25, 15);
    }

    @Test
    void validDraftBecomesAQuiz() {
        QuestionDraft q1 = createSampleQuestionDraft("q1", "First Question", 0);
        QuestionDraft q2 = createSampleQuestionDraft("q2", "Second Question", 2);

        QuizDraft draft = new QuizDraft("my-quiz", "Computer Science 101", List.of(q1, q2));
        Quiz quiz = draft.toQuiz();

        assertNotNull(quiz);
        assertEquals("my-quiz", quiz.id());
        assertEquals("Computer Science 101", quiz.title());
        assertEquals(2, quiz.questions().size());
        assertEquals("First Question", quiz.questions().get(0).text());
        assertEquals(0, quiz.questions().get(0).correctOptionIndex());
        assertEquals("Second Question", quiz.questions().get(1).text());
        assertEquals(2, quiz.questions().get(1).correctOptionIndex());
    }

    @Test
    void invalidDraftFailsWithClearMessage() {
        // Blank title
        QuizDraft blankTitleDraft = new QuizDraft("id", "   ", List.of(createSampleQuestionDraft("q1", "Q1", 0)));
        IllegalArgumentException titleEx = assertThrows(IllegalArgumentException.class, blankTitleDraft::toQuiz);
        assertTrue(titleEx.getMessage().toLowerCase().contains("title"));

        // No questions
        QuizDraft noQuestionsDraft = new QuizDraft("id", "Valid Title", List.of());
        IllegalArgumentException noQEx = assertThrows(IllegalArgumentException.class, noQuestionsDraft::toQuiz);
        assertTrue(noQEx.getMessage().toLowerCase().contains("question"));

        // Invalid question option index out of bounds
        QuestionDraft invalidQ = new QuestionDraft("q1", "Text", List.of("A", "B"), 5, 20, 10);
        QuizDraft invalidQDraft = new QuizDraft("id", "Valid Title", List.of(invalidQ));
        assertThrows(IllegalArgumentException.class, invalidQDraft::toQuiz);

        // Blank option in question
        QuestionDraft blankOptionQ = new QuestionDraft("q1", "Text", List.of("A", "   "), 0, 20, 10);
        QuizDraft blankOptionDraft = new QuizDraft("id", "Valid Title", List.of(blankOptionQ));
        assertThrows(IllegalArgumentException.class, blankOptionDraft::toQuiz);
    }

    @Test
    void idsAreUniqueAndGeneratedAutomatically() {
        // Null id in quiz draft: should auto-generate valid slug matching ^[A-Za-z0-9_-]{1,64}$
        QuestionDraft q = createSampleQuestionDraft(null, "Question Text", 0);
        QuizDraft draft = new QuizDraft(null, "Distributed Systems: Intro & Overview!", List.of(q));
        Quiz quiz = draft.toQuiz();

        assertNotNull(quiz.id());
        assertTrue(quiz.id().matches("^[A-Za-z0-9_-]{1,64}$"), "Generated ID must match allowed regex: " + quiz.id());
        assertTrue(quiz.id().contains("distributed-systems"), "Generated ID should contain slug of title");

        // Question without ID receives auto-generated ID
        assertEquals("q1", quiz.questions().get(0).id());

        // Duplicate question IDs are resolved to unique values
        QuestionDraft qA = createSampleQuestionDraft("dup", "Question A", 0);
        QuestionDraft qB = createSampleQuestionDraft("dup", "Question B", 1);
        QuizDraft dupDraft = new QuizDraft("quiz-dup", "Duplicate Test", List.of(qA, qB));
        Quiz dupQuiz = dupDraft.toQuiz();

        assertEquals(2, dupQuiz.questions().size());
        assertNotEquals(dupQuiz.questions().get(0).id(), dupQuiz.questions().get(1).id());
    }

    @Test
    void roundTripFromQuizToDraftAndBack() {
        Question q1 = new Question("q1", "Protocol?", List.of("TCP", "UDP"), 0, 15, 10);
        Quiz original = new Quiz("round-trip-quiz", "Networking 101", List.of(q1));

        QuizDraft draft = QuizDraft.fromQuiz(original);
        assertEquals(original.id(), draft.getId());
        assertEquals(original.title(), draft.getTitle());
        assertEquals(1, draft.getQuestions().size());

        Quiz rebuilt = draft.toQuiz();
        assertEquals(original, rebuilt);
    }

    @Test
    void questionDraftCopyModificationsAreIndependent() {
        QuestionDraft original = createSampleQuestionDraft("q1", "Original Text", 0);
        QuestionDraft copy = original.copy();

        copy.setText("Modified Text");
        copy.setCorrectOptionIndex(2);

        assertEquals("Original Text", original.getText());
        assertEquals(0, original.getCorrectOptionIndex());
        assertEquals("Modified Text", copy.getText());
        assertEquals(2, copy.getCorrectOptionIndex());
    }
}
