package com.greg.one2many.web;

import com.greg.one2many.exception.InappropriateContentException;
import com.greg.one2many.exception.InvalidTokenException;
import com.greg.one2many.exception.TokenAlreadyUsedException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.greg.one2many.exception.InvalidSessionException;
import com.greg.one2many.exception.AttendeeNotFoundException;
import com.greg.one2many.exception.RescanClosedException;
import com.greg.one2many.exception.SessionReplacedException;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    // 410 Gone: "this existed, but not any more" — the old page after a re-scan
    // an address that doesn't exist: a plain 404, not a 500, and not an error in the log
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", resolve("error.notFound")));
    }

    @ExceptionHandler(SessionReplacedException.class)
    public ResponseEntity<Map<String, String>> handleSessionReplaced(SessionReplacedException ex) {
        // "reason" = the message key (session.replaced | event.ended): the phone picks its title from it
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", resolve(ex.getMessage()), "reason", ex.getMessage()));
    }

    // 409 Conflict: confirming/rejecting a re-scan that was already answered or expired
    @ExceptionHandler(RescanClosedException.class)
    public ResponseEntity<Map<String, String>> handleRescanClosed(RescanClosedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", resolve(ex.getMessage())));
    }

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

    @ExceptionHandler(InappropriateContentException.class)
    public ResponseEntity<Map<String, String>> handleInappropriateContent(InappropriateContentException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", resolve(ex.getMessage())));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        String firstMessage = fieldErrors.values().iterator().next();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", firstMessage, "fields", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", resolve("request.malformed")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleEverythingElse(Exception ex) {
        // Spring's own "you asked wrongly" errors (wrong method 405, wrong content type 415,
        // can't answer in the format you want 406...): the client's mistake, not ours.
        // Answer with the right status, no body, and NO log entry: otherwise anyone could fill
        // the log file with stack traces by sending odd requests in a loop (review, 21 Nov).
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(errorResponse.getStatusCode()).build();
        }
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", resolve("error.generic")));
    }

    @ExceptionHandler(InvalidSessionException.class)
    public ResponseEntity<Map<String, String>> handleInvalidSession(InvalidSessionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", resolve(ex.getMessage())));
    }

    @ExceptionHandler(AttendeeNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleAttendeeNotFound(AttendeeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", resolve(ex.getMessage())));
    }
}
