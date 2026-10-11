package edu.vkParser.exception;

public class DownloadingException extends Exception {
    public DownloadingException(String message) {
        super(message);
    }

    public DownloadingException(String message, Throwable cause) {
        super(message, cause);
    }

    public DownloadingException(Throwable cause) {
        super(cause);
    }
}
