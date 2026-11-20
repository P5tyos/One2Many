package com.greg.one2many.exception;

public class SessionReplacedException extends RuntimeException {
    public SessionReplacedException(String messageKey) {
        super(messageKey);
    }
}
