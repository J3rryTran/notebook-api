package com.vtnexus.notebook.common.dto;

import lombok.*;

import java.util.Collections;
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

    public static ErrorEnvelope of(String code, String message) {
        return of(code, message, null, false, Collections.emptyMap());
    }

    public static ErrorEnvelope of(String code, String message, String requestId, boolean retryable, Map<String, Object> details) {
        return ErrorEnvelope.builder()
                .error(ErrorDetail.builder()
                        .code(code)
                        .message(message)
                        .requestId(requestId)
                        .retryable(retryable)
                        .details(details != null ? details : Collections.emptyMap())
                        .build())
                .build();
    }
}
