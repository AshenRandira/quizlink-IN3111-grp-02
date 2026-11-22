package lk.quizlink.common.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelValidationTest {
    private static final List<String> OPTIONS = List.of("UDP", "TCP");

    @Test
    void acceptsValidSharedModels() {
        Question question = validQuestion();

        assertDoesNotThrow(() -> new Quiz("quiz-1", "Networking", List.of(question)));
        assertDoesNotThrow(() -> new QuestionView("q1", "Choose one", OPTIONS, 20, 10));
        assertDoesNotThrow(() -> new Participant("p1", "Student 1"));
        assertDoesNotThrow(() -> new LeaderboardEntry("p1", "Student 1", 10, 1));
    }

    @Test
    void rejectsMissingOrBlankRequiredText() {
        assertThrows(IllegalArgumentException.class, () -> new Quiz("", "Title", List.of(validQuestion())));
        assertThrows(IllegalArgumentException.class, () -> new Quiz("q1", " ", List.of(validQuestion())));
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", null, OPTIONS, 0, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new Participant("p1", "\nStudent"));
        assertThrows(IllegalArgumentException.class, () -> new LeaderboardEntry(" ", "Student", 10, 1));
    }

    @Test
    void rejectsInvalidQuestionData() {
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", "Text", List.of(), 0, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", "Text", java.util.Arrays.asList("A", null), 0, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", "Text", OPTIONS, 2, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", "Text", OPTIONS, -1, 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", "Text", OPTIONS, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Question("q1", "Text", OPTIONS, 0, 20, -1));
    }

    @Test
    void rejectsInvalidQuizAndLeaderboardData() {
        Question first = validQuestion();
        Question duplicate = new Question("q1", "Another question", OPTIONS, 1, 20, 5);

        assertThrows(IllegalArgumentException.class, () -> new Quiz("quiz-1", "Title", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Quiz("quiz-1", "Title", List.of(first, duplicate)));
        assertThrows(IllegalArgumentException.class, () -> new LeaderboardEntry("p1", "Student", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LeaderboardEntry("p1", "Student", 10, 0));
    }

    @Test
    void copiesCollectionsAndExposesImmutableAccessors() {
        List<String> mutableOptions = new ArrayList<>(OPTIONS);
        Question question = new Question("q1", "Choose one", mutableOptions, 1, 20, 10);
        mutableOptions.set(0, "Changed");

        assertEquals(OPTIONS, question.options());
        assertThrows(UnsupportedOperationException.class, () -> question.options().add("ICMP"));

        List<Question> mutableQuestions = new ArrayList<>(List.of(question));
        Quiz quiz = new Quiz("quiz-1", "Networking", mutableQuestions);
        mutableQuestions.clear();

        assertEquals(1, quiz.questions().size());
        assertThrows(UnsupportedOperationException.class, () -> quiz.questions().clear());
    }

    @Test
    void createsAnswerKeySafeQuestionView() {
        QuestionView view = validQuestion().toView();
        JsonObject json = new Gson().toJsonTree(view).getAsJsonObject();

        assertFalse(json.has("correctOptionIndex"));
        assertEquals(2, json.getAsJsonArray("options").size());
    }

    @Test
    void preservesGsonFieldNamesAndRoundTripsValidQuiz() {
        Gson gson = new Gson();
        Quiz original = new Quiz("quiz-1", "Networking", List.of(validQuestion()));

        JsonObject json = gson.toJsonTree(original).getAsJsonObject();
        assertEquals("quiz-1", json.get("id").getAsString());
        assertEquals("Networking", json.get("title").getAsString());
        assertEquals(1, json.getAsJsonArray("questions").size());
        assertEquals(1, json.getAsJsonArray("questions").get(0)
                .getAsJsonObject().get("correctOptionIndex").getAsInt());

        assertEquals(original, gson.fromJson(gson.toJson(original), Quiz.class));
    }

    @Test
    void deserializesTrackedSampleQuizUsingValidatedModels() throws IOException {
        String sampleJson = Files.readString(Path.of("data", "quizzes", "sample-quiz.json"));

        Quiz quiz = new Gson().fromJson(sampleJson, Quiz.class);

        assertEquals("network-basics", quiz.id());
        assertEquals("Computer Networking Basics", quiz.title());
        assertEquals(1, quiz.questions().size());
        assertEquals("q1", quiz.questions().get(0).id());
        assertEquals(1, quiz.questions().get(0).correctOptionIndex());
    }

    @Test
    void rejectsInvalidQuizJsonDuringDeserialization() {
        String invalidQuizJson = """
                {
                  "id": "quiz-1",
                  "title": "Invalid quiz",
                  "questions": [
                    {
                      "id": "q1",
                      "text": "Question",
                      "options": ["Only option"],
                      "correctOptionIndex": 1,
                      "timeLimitSeconds": 20,
                      "points": 10
                    }
                  ]
                }
                """;

        assertThrows(RuntimeException.class,
                () -> new Gson().fromJson(invalidQuizJson, Quiz.class));
    }

    private static Question validQuestion() {
        return new Question("q1", "Which protocol is reliable?", OPTIONS, 1, 20, 10);
    }
}
