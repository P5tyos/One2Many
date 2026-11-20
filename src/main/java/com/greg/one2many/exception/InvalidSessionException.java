package com.greg.one2many.exception;

public class InvalidSessionException extends RuntimeException {
    public InvalidSessionException(String messageKey) {
        super(messageKey);
    }
}