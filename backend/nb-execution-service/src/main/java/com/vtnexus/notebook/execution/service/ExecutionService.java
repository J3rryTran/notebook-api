package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.execution.client.DocumentServiceClient;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.model.NotebookExecution;
import com.vtnexus.notebook.execution.model.NotebookSession;
import com.vtnexus.notebook.execution.repository.NotebookExecutionRepository;
import com.vtnexus.notebook.execution.repository.NotebookSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class ExecutionService {

    private final NotebookSessionRepository sessionRepository;
    private final NotebookExecutionRepository executionRepository;
    private final KyuubiRestClient kyuubiRestClient;
    private final DocumentServiceClient documentServiceClient;
    private final ObjectMapper objectMapper;
    private final int maxRows;

    public ExecutionService(
            NotebookSessionRepository sessionRepository,
            NotebookExecutionRepository executionRepository,
            KyuubiRestClient kyuubiRestClient,
            DocumentServiceClient documentServiceClient,
            ObjectMapper objectMapper,
            @Value("${notebook.results.max-rows:1000}") int maxRows) {
        this.sessionRepository = sessionRepository;
        this.executionRepository = executionRepository;
        this.kyuubiRestClient = kyuubiRestClient;
        this.documentServiceClient = documentServiceClient;
        this.objectMapper = objectMapper;
        this.maxRows = maxRows;
    }

    public NotebookSession getOrCreateSession(String notebookId, String user, String authHeader) {
        if (!documentServiceClient.canExecute(notebookId, user)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires RUNNER role or higher to open execution session", HttpStatus.FORBIDDEN);
        }

        return sessionRepository.findByNotebookIdAndUserIdAndStatusNot(notebookId, user, "CLOSED")
                .orElseGet(() -> {
                    Map<String, String> kSession = kyuubiRestClient.openSession(authHeader, null);
                    NotebookSession session = NotebookSession.builder()
                            .id(UUID.randomUUID().toString())
                            .notebookId(notebookId)
                            .kyuubiSessionId(kSession.get("sessionId"))
                            .kyuubiInstance(kSession.get("instance"))
                            .userId(user)
                            .status("READY")
                            .executionCountSeq(0)
                            .build();
                    return sessionRepository.save(session);
                });
    }

    public NotebookExecution submitExecution(String sessionId, String cellId, String user, String authHeader) {
        NotebookSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));

        if (!session.getUserId().equals(user)) {
            throw new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND);
        }

        if (!documentServiceClient.canExecute(session.getNotebookId(), user)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires RUNNER role to execute cell", HttpStatus.FORBIDDEN);
        }

        JsonNode cellData = documentServiceClient.getCell(session.getNotebookId(), cellId, user);
        String cellType = cellData.path("cellType").asText("CODE");
        String language = cellData.path("language").asText("SQL");
        String source = cellData.path("source").asText("");

        if (!"CODE".equalsIgnoreCase(cellType)) {
            throw new NotebookApiException("INVALID_REQUEST", "Only CODE cells can be executed", HttpStatus.BAD_REQUEST);
        }

        int count = session.getExecutionCountSeq() + 1;
        session.setExecutionCountSeq(count);
        sessionRepository.save(session);

        String opId = kyuubiRestClient.submitStatement(
                session.getKyuubiSessionId(), session.getKyuubiInstance(), source, language, authHeader);

        NotebookExecution execution = NotebookExecution.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .notebookId(session.getNotebookId())
                .cellId(cellId)
                .kyuubiOperationId(opId)
                .executionCount(count)
                .status("RUNNING") // Chuyển ngay sang RUNNING sau khi submit thành công
                .statement(source)
                .language(language)
                .submittedBy(user)
                .build();

        return executionRepository.save(execution);
    }

    /**
     * Đồng bộ trạng thái execution theo request của người dùng (không poller nền).
     * Khi hoàn thành: SELECT FOR UPDATE và fetch rowset 1 lần để tránh mất cursor.
     */
    public NotebookExecution syncAndGetExecution(String executionId, String user, String authHeader) {
        // Q5: Chỉ submittedBy mới đọc được execution
        NotebookExecution exec = executionRepository.findByIdAndSubmittedBy(executionId, user)
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND));

        if ("RUNNING".equals(exec.getStatus()) && exec.getKyuubiOperationId() != null) {
            NotebookSession session = sessionRepository.findById(exec.getSessionId()).orElse(null);
            String instance = session != null ? session.getKyuubiInstance() : null;

            try {
                JsonNode event = kyuubiRestClient.getOperationEvent(exec.getKyuubiOperationId(), instance, authHeader);
                if (event != null && event.has("state")) {
                    String state = event.get("state").asText().toUpperCase();

                    if ("FINISHED".equals(state) || "ERROR".equals(state) || "TIMEOUT".equals(state) || "CANCELED".equals(state)) {
                        exec = executionRepository.findByIdForUpdate(executionId).orElse(exec);
                        if ("RUNNING".equals(exec.getStatus())) {
                            if ("FINISHED".equals(state)) {
                                try {
                                    JsonNode rowset = kyuubiRestClient.getOperationRowset(exec.getKyuubiOperationId(), instance, maxRows, authHeader);
                                    exec.setStatus("SUCCEEDED");
                                    exec.setResultOutput(rowset != null ? rowset.toString() : "[]");
                                    kyuubiRestClient.closeOperation(exec.getKyuubiOperationId(), instance, authHeader);
                                } catch (Exception rowsetEx) {
                                    exec.setStatus("FAILED");
                                    exec.setErrorMessage("Failed to fetch rowset: " + rowsetEx.getMessage());
                                }
                            } else if ("CANCELED".equals(state)) {
                                exec.setStatus("CANCELED");
                                kyuubiRestClient.closeOperation(exec.getKyuubiOperationId(), instance, authHeader);
                            } else {
                                exec.setStatus("FAILED");
                                String errMsg = "Execution failed on Kyuubi";
                                if (event.has("exception")) {
                                    JsonNode exNode = event.get("exception");
                                    errMsg = exNode.isObject() && exNode.has("message") ? exNode.get("message").asText() : exNode.asText();
                                }
                                exec.setErrorMessage(errMsg);
                                kyuubiRestClient.closeOperation(exec.getKyuubiOperationId(), instance, authHeader);
                            }

                            // Fetch logs
                            List<String> logs = kyuubiRestClient.getOperationLog(exec.getKyuubiOperationId(), instance, 500, authHeader);
                            if (!logs.isEmpty()) {
                                exec.setLogOutput(String.join("\n", logs));
                            }

                            exec = executionRepository.save(exec);
                        }
                    }
                }
            } catch (NotebookApiException ex) {
                if ("RUNTIME_LOST".equals(ex.getCode())) {
                    exec.setStatus("LOST");
                    exec.setErrorMessage("Runtime lost or operation closed on engine");
                    exec = executionRepository.save(exec);
                } else {
                    throw ex;
                }
            }
        }
        return exec;
    }

    public void cancelExecution(String executionId, String user, String authHeader) {
        NotebookExecution exec = executionRepository.findByIdAndSubmittedBy(executionId, user)
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND));

        NotebookSession session = sessionRepository.findById(exec.getSessionId()).orElse(null);
        String instance = session != null ? session.getKyuubiInstance() : null;

        kyuubiRestClient.cancelOperation(exec.getKyuubiOperationId(), instance, authHeader);
        exec.setStatus("CANCELED");
        executionRepository.save(exec);
    }

    public void restartRuntime(String sessionId, String user, String authHeader) {
        NotebookSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));

        if (!session.getUserId().equals(user)) {
            throw new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND);
        }

        if (session.getKyuubiSessionId() != null) {
            kyuubiRestClient.closeSession(session.getKyuubiSessionId(), session.getKyuubiInstance(), authHeader);
        }

        Map<String, String> newSession = kyuubiRestClient.openSession(authHeader, null);
        session.setKyuubiSessionId(newSession.get("sessionId"));
        session.setKyuubiInstance(newSession.get("instance"));
        session.setStatus("READY");
        session.setExecutionCountSeq(0); // Reset execution count
        sessionRepository.save(session);
    }

    public List<NotebookExecution> getLatestExecutions(String notebookId, String cellId, String user) {
        return executionRepository.findByNotebookIdAndCellIdAndSubmittedByOrderByCreatedAtDesc(
                notebookId, cellId, user, PageRequest.of(0, 10));
    }
}
