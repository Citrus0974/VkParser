package edu.vkParser.exception;

public class SetupException extends Exception {
    public SetupException(String message) {
        super(message);
    }

    public SetupException(Throwable cause) {
        super(cause);
    }

    public SetupException(String message, Throwable cause) {
        super(message, cause);
    }
}
