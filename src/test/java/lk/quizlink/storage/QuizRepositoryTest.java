package lk.quizlink.storage;

import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizRepositoryTest {

    private static final List<String> OPTIONS = List.of("UDP", "TCP", "IP", "ICMP");

    private Question createQuestion(String id, String text, int correctIndex) {
        return new Question(id, text, OPTIONS, correctIndex, 20, 10);
    }

    private Quiz createQuiz(String id, String title) {
        return new Quiz(id, title, List.of(createQuestion("q1", "Which transport protocol is reliable?", 1)));
    }

    @Test
    void createsDirectoryIfMissing(@TempDir Path tempDir) {
        Path nestedDir = tempDir.resolve("nested").resolve("quizzes");
        assertFalse(Files.exists(nestedDir));

        QuizRepository repo = new QuizRepository(nestedDir);
        assertTrue(Files.isDirectory(nestedDir));

        Quiz quiz = createQuiz("net-quiz", "Networking Quiz");
        repo.save(quiz);
        assertTrue(repo.exists("net-quiz"));
    }

    @Test
    void saveThenLoadReturnsEqualQuiz(@TempDir Path tempDir) throws IOException {
        QuizRepository repo = new QuizRepository(tempDir);
        Quiz quiz = createQuiz("network-quiz", "Computer Networking");

        repo.save(quiz);

        assertTrue(repo.exists("network-quiz"));
        Optional<Quiz> loaded = repo.load("network-quiz");
        assertTrue(loaded.isPresent());
        assertEquals(quiz, loaded.get());

        // Verify pretty printing in the saved JSON file
        Path savedFile = tempDir.resolve("network-quiz.json");
        String content = Files.readString(savedFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("\n"), "Saved JSON should be formatted with newlines (pretty printing)");
        assertTrue(content.contains("  \"id\": \"network-quiz\""), "Saved JSON should have indentation");
    }

    @Test
    void overwriteExistingQuiz(@TempDir Path tempDir) {
        QuizRepository repo = new QuizRepository(tempDir);
        Quiz version1 = new Quiz("quiz-1", "Version 1 Title", List.of(
                createQuestion("q1", "Question 1", 0)
        ));
        repo.save(version1);

        Optional<Quiz> loadedV1 = repo.load("quiz-1");
        assertTrue(loadedV1.isPresent());
        assertEquals("Version 1 Title", loadedV1.get().title());

        Quiz version2 = new Quiz("quiz-1", "Version 2 Updated Title", List.of(
                createQuestion("q1", "Question 1 Updated", 1),
                createQuestion("q2", "Question 2 New", 2)
        ));
        repo.save(version2);

        Optional<Quiz> loadedV2 = repo.load("quiz-1");
        assertTrue(loadedV2.isPresent());
        assertEquals("Version 2 Updated Title", loadedV2.get().title());
        assertEquals(2, loadedV2.get().questions().size());
        assertEquals(version2, loadedV2.get());
        assertEquals(1, repo.list().size());
    }

    @Test
    void listReturnsMultipleQuizzesSortedByTitle(@TempDir Path tempDir) {
        QuizRepository repo = new QuizRepository(tempDir);
        Quiz quizC = createQuiz("quiz-c", "Zebra Networking");
        Quiz quizA = createQuiz("quiz-a", "Algorithms");
        Quiz quizB = createQuiz("quiz-b", "Data Structures");

        repo.save(quizC);
        repo.save(quizA);
        repo.save(quizB);

        List<Quiz> quizzes = repo.list();
        assertEquals(3, quizzes.size());
        assertEquals("Algorithms", quizzes.get(0).title());
        assertEquals("Data Structures", quizzes.get(1).title());
        assertEquals("Zebra Networking", quizzes.get(2).title());
    }

    @Test
    void corruptJsonFileSkippedByListAndThrowsOnLoad(@TempDir Path tempDir) throws IOException {
        QuizRepository repo = new QuizRepository(tempDir);
        Quiz validQuiz = createQuiz("valid-quiz", "Valid Quiz");
        repo.save(validQuiz);

        // Create corrupt JSON file
        Path corruptFile = tempDir.resolve("corrupt-quiz.json");
        Files.writeString(corruptFile, "{\"id\": \"corrupt-quiz\", \"title\": [unclosed json", StandardCharsets.UTF_8);

        // list() should skip corrupt file and return valid quizzes
        List<Quiz> quizzes = repo.list();
        assertEquals(1, quizzes.size());
        assertEquals("valid-quiz", quizzes.get(0).id());

        // load() on the corrupt file must throw StorageException
        assertThrows(StorageException.class, () -> repo.load("corrupt-quiz"));
    }

    @Test
    void jsonFileWithInvalidQuestionRejected(@TempDir Path tempDir) throws IOException {
        QuizRepository repo = new QuizRepository(tempDir);

        // Question with correctOptionIndex = 5 when only 2 options exist
        String invalidQuestionJson = """
                {
                  "id": "invalid-q-quiz",
                  "title": "Invalid Question Quiz",
                  "questions": [
                    {
                      "id": "q1",
                      "text": "What is IP?",
                      "options": ["Protocol", "Hardware"],
                      "correctOptionIndex": 5,
                      "timeLimitSeconds": 20,
                      "points": 10
                    }
                  ]
                }
                """;
        Path invalidFile = tempDir.resolve("invalid-q-quiz.json");
        Files.writeString(invalidFile, invalidQuestionJson, StandardCharsets.UTF_8);

        // load() must throw StorageException because ModelValidation fails
        assertThrows(StorageException.class, () -> repo.load("invalid-q-quiz"));

        // list() should skip invalid quiz
        assertTrue(repo.list().isEmpty());
    }

    @Test
    void deleteWorksAndReturnsFalseForMissingId(@TempDir Path tempDir) {
        QuizRepository repo = new QuizRepository(tempDir);
        Quiz quiz = createQuiz("quiz-to-delete", "Delete Me");

        repo.save(quiz);
        assertTrue(repo.exists("quiz-to-delete"));

        // First deletion should return true
        assertTrue(repo.delete("quiz-to-delete"));
        assertFalse(repo.exists("quiz-to-delete"));
        assertTrue(repo.load("quiz-to-delete").isEmpty());

        // Second deletion of the same ID should return false
        assertFalse(repo.delete("quiz-to-delete"));

        // Deleting non-existent ID returns false
        assertFalse(repo.delete("never-existed"));
    }

    @Test
    void pathTraversalIdsRejectedOnSaveLoadDelete(@TempDir Path tempDir) {
        QuizRepository repo = new QuizRepository(tempDir);
        List<String> invalidIds = List.of(
                "../escape",
                "..\\escape",
                "a/b",
                "a\\b",
                "",
                "   ",
                "quiz with spaces",
                "quiz@name!",
                "a".repeat(65)
        );

        for (String invalidId : invalidIds) {
            // Test load
            assertThrows(IllegalArgumentException.class, () -> repo.load(invalidId),
                    "load should reject: " + invalidId);

            // Test delete
            assertThrows(IllegalArgumentException.class, () -> repo.delete(invalidId),
                    "delete should reject: " + invalidId);

            // Test exists
            assertThrows(IllegalArgumentException.class, () -> repo.exists(invalidId),
                    "exists should reject: " + invalidId);
        }

        // Test null id
        assertThrows(IllegalArgumentException.class, () -> repo.load(null));
        assertThrows(IllegalArgumentException.class, () -> repo.delete(null));
        assertThrows(IllegalArgumentException.class, () -> repo.exists(null));

        // Test save with null quiz
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));

        // Test save with malicious quiz id
        Quiz traversalQuiz = new Quiz("../malicious", "Malicious Title",
                List.of(createQuestion("q1", "Question", 0)));
        assertThrows(IllegalArgumentException.class, () -> repo.save(traversalQuiz));
    }

    @Test
    void realSampleQuizLoadsSuccessfully() {
        QuizRepository repo = new QuizRepository(Path.of("data", "quizzes"));

        Optional<Quiz> loaded = repo.load("sample-quiz");
        assertTrue(loaded.isPresent(), "data/quizzes/sample-quiz.json should be loaded");

        Quiz quiz = loaded.get();
        assertEquals("network-basics", quiz.id());
        assertEquals("Computer Networking Basics", quiz.title());
        assertEquals(1, quiz.questions().size());

        Question q = quiz.questions().get(0);
        assertEquals("q1", q.id());
        assertEquals("Which transport protocol provides a reliable, ordered byte stream?", q.text());
        assertEquals(List.of("UDP", "TCP", "IP", "ICMP"), q.options());
        assertEquals(1, q.correctOptionIndex());
        assertEquals("TCP", q.options().get(q.correctOptionIndex()));
        assertEquals(20, q.timeLimitSeconds());
        assertEquals(10, q.points());

        assertTrue(repo.exists("sample-quiz"));

        List<Quiz> quizzes = repo.list();
        assertFalse(quizzes.isEmpty());
        assertTrue(quizzes.stream().anyMatch(qz -> "Computer Networking Basics".equals(qz.title())));
    }
}
