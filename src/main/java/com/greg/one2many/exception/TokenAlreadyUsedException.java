package com.greg.one2many.exception;

public class TokenAlreadyUsedException extends RuntimeException {
    public TokenAlreadyUsedException(String messageKey) {
        super(messageKey);
    }
}
