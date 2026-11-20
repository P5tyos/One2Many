package com.greg.one2many.exception;

public class InappropriateContentException extends RuntimeException {
    public InappropriateContentException(String messageKey) {
        super(messageKey);
    }
}