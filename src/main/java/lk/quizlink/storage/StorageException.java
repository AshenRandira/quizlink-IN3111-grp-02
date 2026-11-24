package lk.quizlink.storage;

/**
 * Thrown when an error occurs during quiz storage operations such as reading,
 * writing, parsing, or directory management.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
