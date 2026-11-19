package com.greg.one2many.exception;

public class InvalidTokenException extends RuntimeException {
    // "message" here is a messages.properties KEY, not literal text —
    // resolved to the right language by GlobalExceptionHandler
    public InvalidTokenException(String messageKey) {
        super(messageKey);
    }
}
