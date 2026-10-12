package com.vtnexus.notebook.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.Map;

@Getter
public class NotebookApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Map<String, Object> details;

    public NotebookApiException(String code, String message, HttpStatus status) {
        this(code, message, status, false, Collections.emptyMap());
    }

    public NotebookApiException(String code, String message, HttpStatus status, boolean retryable, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.details = details != null ? details : Collections.emptyMap();
    }
}
