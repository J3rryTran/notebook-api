package com.vtnexus.notebook.exception;

import com.vtnexus.notebook.dto.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotebookApiException.class)
    public ResponseEntity<ErrorEnvelope> handleNotebookApiException(NotebookApiException ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.warn("NotebookApiException [{}]: {} - {}", requestId, ex.getCode(), ex.getMessage());

        ErrorEnvelope.ErrorDetail detail = ErrorEnvelope.ErrorDetail.builder()
                .code(ex.getCode())
                .message(ex.getMessage())
                .requestId(requestId)
                .retryable(ex.isRetryable())
                .details(ex.getDetails())
                .build();

        return ResponseEntity.status(ex.getStatus()).body(new ErrorEnvelope(detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.error("Internal server error [{}]", requestId, ex);

        ErrorEnvelope.ErrorDetail detail = ErrorEnvelope.ErrorDetail.builder()
                .code("INTERNAL_ERROR")
                .message("An unexpected error occurred")
                .requestId(requestId)
                .retryable(false)
                .details(Collections.emptyMap())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorEnvelope(detail));
    }
}
