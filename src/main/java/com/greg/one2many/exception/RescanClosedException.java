package com.greg.one2many.exception;

public class RescanClosedException extends RuntimeException {
    public RescanClosedException(String messageKey) {
        super(messageKey);
    }
}
