package com.vtnexus.notebook.execution.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotebookExecutionDto {
    private String id;
    private String sessionId;
    private String notebookId;
    private String cellId;
    private String status;
    private String language;
    private String statement;
    private Integer executionCount;
    private Long sequenceNumber;
    private String resultOutput;
    private String logOutput;
    private String errorMessage;
    private String errorCode;
    private Boolean stale;
    private Instant createdAt;
    private String message;
}
