package com.greg.one2many.web;

import com.greg.one2many.exception.InvalidTokenException;
import com.greg.one2many.exception.TokenAlreadyUsedException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidToken(InvalidTokenException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", resolve(ex.getMessage())));
    }

    @ExceptionHandler(TokenAlreadyUsedException.class)
    public ResponseEntity<Map<String, String>> handleTokenAlreadyUsed(TokenAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", resolve(ex.getMessage())));
    }

    private String resolve(String messageKey) {
        // translates the key into whatever language the visitor's
        // browser/phone asked for (via the Accept-Language header)
        return messageSource.getMessage(messageKey, null, LocaleContextHolder.getLocale());
    }
}
