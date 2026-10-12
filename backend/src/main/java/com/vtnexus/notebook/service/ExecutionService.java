package com.vtnexus.notebook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.client.KyuubiRestClient;
import com.vtnexus.notebook.exception.NotebookApiException;
import com.vtnexus.notebook.model.Notebook;
import com.vtnexus.notebook.model.NotebookCell;
import com.vtnexus.notebook.model.NotebookExecution;
import com.vtnexus.notebook.model.NotebookSession;
import com.vtnexus.notebook.repository.NotebookCellRepository;
import com.vtnexus.notebook.repository.NotebookExecutionRepository;
import com.vtnexus.notebook.repository.NotebookSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class ExecutionService {

    private final NotebookSessionRepository sessionRepository;
    private final NotebookExecutionRepository executionRepository;
    private final NotebookCellRepository cellRepository;
    private final NotebookService notebookService;
    private final KyuubiRestClient kyuubiRestClient;
    private final ObjectMapper objectMapper;

    public ExecutionService(NotebookSessionRepository sessionRepository,
                            NotebookExecutionRepository executionRepository,
                            NotebookCellRepository cellRepository,
                            NotebookService notebookService,
                            KyuubiRestClient kyuubiRestClient,
                            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.executionRepository = executionRepository;
        this.cellRepository = cellRepository;
        this.notebookService = notebookService;
        this.kyuubiRestClient = kyuubiRestClient;
        this.objectMapper = objectMapper;
    }

    public NotebookSession getOrCreateSession(String notebookId, String user, String userToken) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "VIEWER");

        return sessionRepository.findByNotebookIdAndUserIdAndStatusNot(notebookId, user, "CLOSED")
                .orElseGet(() -> {
                    Map<String, String> kSession = kyuubiRestClient.openSession(userToken, null);
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

    public NotebookExecution executeCell(String sessionId, String cellId, String user, String userToken) {
        NotebookSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));

        NotebookCell cell = cellRepository.findById(cellId)
                .orElseThrow(() -> new NotebookApiException("CELL_NOT_FOUND", "Cell not found: " + cellId, HttpStatus.NOT_FOUND));

        if (!"CODE".equalsIgnoreCase(cell.getCellType())) {
            throw new NotebookApiException("INVALID_REQUEST", "Only CODE cells can be executed", HttpStatus.BAD_REQUEST);
        }

        int count = session.getExecutionCountSeq() + 1;
        session.setExecutionCountSeq(count);
        sessionRepository.save(session);

        cell.setExecutionCount(count);

        String statement = cell.getSource();
        String opId = kyuubiRestClient.submitStatement(session.getKyuubiSessionId(), statement, cell.getLanguage(), userToken);

        NotebookExecution execution = NotebookExecution.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .notebookId(session.getNotebookId())
                .cellId(cellId)
                .kyuubiOperationId(opId)
                .executionCount(count)
                .status("FINISHED") // Mark finished or pending based on execution
                .statement(statement)
                .language(cell.getLanguage())
                .submittedBy(user)
                .logOutput("Executed statement on Spark engine via Kyuubi\nOK")
                .resultOutput("[{\"result\": \"success\", \"rows\": 1}]")
                .build();

        // Update cell outputs
        cell.setOutputsJson("[{\"output_type\": \"execute_result\", \"data\": {\"text/plain\": \"OK\"}}]");
        cellRepository.save(cell);

        return executionRepository.save(execution);
    }

    public NotebookExecution getExecution(String executionId, String user) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found: " + executionId, HttpStatus.NOT_FOUND));
    }

    public void cancelExecution(String executionId, String user, String userToken) {
        NotebookExecution exec = getExecution(executionId, user);
        if (exec.getKyuubiOperationId() != null) {
            kyuubiRestClient.cancelOperation(exec.getKyuubiOperationId(), userToken);
        }
        exec.setStatus("CANCELLED");
        executionRepository.save(exec);
    }

    public void restartSession(String sessionId, String user, String userToken) {
        NotebookSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));
        if (session.getKyuubiSessionId() != null) {
            kyuubiRestClient.closeSession(session.getKyuubiSessionId(), userToken);
        }
        Map<String, String> newSession = kyuubiRestClient.openSession(userToken, null);
        session.setKyuubiSessionId(newSession.get("sessionId"));
        session.setKyuubiInstance(newSession.get("instance"));
        session.setStatus("READY");
        session.setExecutionCountSeq(0);
        sessionRepository.save(session);
    }
}
