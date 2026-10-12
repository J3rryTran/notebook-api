package com.vtnexus.notebook.dto;

import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorEnvelope {
    private ErrorDetail error;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ErrorDetail {
        private String code;
        private String message;
        private String requestId;
        private boolean retryable;
        private Map<String, Object> details;
    }
}
