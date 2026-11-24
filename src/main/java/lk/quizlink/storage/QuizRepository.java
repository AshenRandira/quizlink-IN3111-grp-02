package lk.quizlink.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lk.quizlink.common.model.Quiz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Repository for persistent storage and retrieval of quiz definitions as JSON files.
 * <p>
 * Ensures thread-safe/process-safe atomic writes, strict ID validation to prevent
 * path traversal attacks, and safe error handling when encountering corrupt quiz files.
 */
public final class QuizRepository {

    private static final Logger LOGGER = Logger.getLogger(QuizRepository.class.getName());
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Path directory;
    private final Gson gson;

    /**
     * Creates a QuizRepository that stores quizzes in the specified directory.
     * The directory is created if it does not already exist.
     *
     * @param directory the directory to store quiz files
     */
    public QuizRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new StorageException("Failed to create storage directory: " + directory, e);
        }
    }

    /**
     * Creates a QuizRepository using the default directory ("data/quizzes").
     */
    public QuizRepository() {
        this(Path.of("data", "quizzes"));
    }

    /**
     * Saves a quiz definition as a pretty-printed JSON file named {@code <id>.json}.
     * Overwrites any existing file with the same ID.
     * The write is performed atomically via a temporary file in the same directory.
     *
     * @param quiz the quiz to save
     * @throws IllegalArgumentException if quiz is null or id fails validation
     * @throws StorageException if an I/O error occurs while writing or moving
     */
    public void save(Quiz quiz) {
        if (quiz == null) {
            throw new IllegalArgumentException("quiz must not be null");
        }
        validateId(quiz.id());

        Path targetFile = directory.resolve(quiz.id() + ".json");
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(directory, quiz.id() + "-", ".tmp");
            String json = gson.toJson(quiz);
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);

            try {
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
            throw new StorageException("Failed to save quiz: " + quiz.id(), e);
        }
    }

    /**
     * Loads a quiz definition by ID.
     *
     * @param id the quiz ID
     * @return an Optional containing the quiz, or empty if the file does not exist
     * @throws IllegalArgumentException if the ID does not match the allowed pattern
     * @throws StorageException if the file exists but cannot be read or parsed
     */
    public Optional<Quiz> load(String id) {
        validateId(id);
        Path targetFile = directory.resolve(id + ".json");
        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            return Optional.empty();
        }

        String content;
        try {
            content = Files.readString(targetFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Failed to read quiz file: " + targetFile, e);
        }

        try {
            Quiz quiz = gson.fromJson(content, Quiz.class);
            if (quiz == null) {
                throw new StorageException("Quiz file is empty: " + targetFile);
            }
            return Optional.of(quiz);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StorageException("Failed to parse quiz from file: " + targetFile, e);
        }
    }

    /**
     * Lists all valid quizzes in the repository directory, sorted alphabetically by title.
     * Any corrupt or invalid files are skipped and logged rather than failing the whole list.
     *
     * @return an unmodifiable list of valid quizzes sorted by title
     * @throws StorageException if the directory cannot be listed
     */
    public List<Quiz> list() {
        List<Quiz> quizzes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Quiz quiz = gson.fromJson(content, Quiz.class);
                    if (quiz == null) {
                        LOGGER.log(Level.WARNING, "Skipping empty quiz file: {0}", file);
                        continue;
                    }
                    quizzes.add(quiz);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Skipping invalid quiz file: " + file + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            throw new StorageException("Failed to list quizzes in directory: " + directory, e);
        }

        quizzes.sort(Comparator.comparing(Quiz::title));
        return List.copyOf(quizzes);
    }

    /**
     * Deletes the quiz file corresponding to the specified ID.
     *
     * @param id the quiz ID
     * @return true if a file was deleted, false if the file did not exist
     * @throws IllegalArgumentException if the ID does not match the allowed pattern
     * @throws StorageException if an I/O error occurs while deleting the file
     */
    public boolean delete(String id) {
        validateId(id);
        Path targetFile = directory.resolve(id + ".json");
        try {
            return Files.deleteIfExists(targetFile);
        } catch (IOException e) {
            throw new StorageException("Failed to delete quiz file: " + targetFile, e);
        }
    }

    /**
     * Checks if a quiz file exists for the specified ID.
     *
     * @param id the quiz ID
     * @return true if the file exists and is a regular file, false otherwise
     * @throws IllegalArgumentException if the ID does not match the allowed pattern
     */
    public boolean exists(String id) {
        validateId(id);
        Path targetFile = directory.resolve(id + ".json");
        return Files.isRegularFile(targetFile);
    }

    private void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid quiz ID: " + id);
        }
    }
}
