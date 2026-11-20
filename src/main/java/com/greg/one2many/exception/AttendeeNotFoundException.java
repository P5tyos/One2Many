package com.greg.one2many.exception;

public class AttendeeNotFoundException extends RuntimeException {
    public AttendeeNotFoundException(String messageKey) {
        super(messageKey);
    }
}