package lk.quizlink.host;

/**
 * Thrown when a host networking operation fails or when the server replies with an ERROR message.
 */
public class HostException extends RuntimeException {

    private final String code;

    public HostException(String code, String message) {
        super(message);
        this.code = code != null ? code : "ERROR";
    }

    public HostException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code != null ? code : "ERROR";
    }

    public HostException(String message) {
        this("ERROR", message);
    }

    public HostException(String message, Throwable cause) {
        this("ERROR", message, cause);
    }

    public String getCode() {
        return code;
    }

    public String code() {
        return code;
    }
}
