package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.execution.client.DocumentServiceClient;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.model.*;
import com.vtnexus.notebook.execution.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class ExecutionService {

    private final NotebookKyuubiSessionRepository sessionRepository;
    private final NotebookRunRepository runRepository;
    private final NotebookCellRunRepository cellRunRepository;
    private final NotebookExecutionLogRepository logRepository;
    private final KyuubiRestClient kyuubiRestClient;
    private final DocumentServiceClient documentServiceClient;
    private final ObjectMapper objectMapper;
    private final int maxRows;

    public ExecutionService(
            NotebookKyuubiSessionRepository sessionRepository,
            NotebookRunRepository runRepository,
            NotebookCellRunRepository cellRunRepository,
            NotebookExecutionLogRepository logRepository,
            KyuubiRestClient kyuubiRestClient,
            DocumentServiceClient documentServiceClient,
            ObjectMapper objectMapper,
            @Value("${notebook.results.max-rows:1000}") int maxRows) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.cellRunRepository = cellRunRepository;
        this.logRepository = logRepository;
        this.kyuubiRestClient = kyuubiRestClient;
        this.documentServiceClient = documentServiceClient;
        this.objectMapper = objectMapper;
        this.maxRows = maxRows;
    }

    public NotebookKyuubiSession getOrCreateSession(String notebookId, String user, String authHeader) {
        if (!documentServiceClient.canExecute(notebookId, user)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires RUNNER role or higher to open execution session", HttpStatus.FORBIDDEN);
        }

        return sessionRepository.findByNotebookIdAndUserIdAndStatusNot(notebookId, user, "CLOSED")
                .orElseGet(() -> {
                    Map<String, String> kSession = kyuubiRestClient.openSession(authHeader, null);
                    NotebookKyuubiSession session = NotebookKyuubiSession.builder()
                            .id(UUID.randomUUID().toString())
                            .notebookId(notebookId)
                            .kyuubiSessionId(kSession.get("sessionId"))
                            .kyuubiInstance(kSession.get("instance"))
                            .userId(user)
                            .status("READY")
                            .executionCountSeq(0)
                            .lastUsedAt(Instant.now())
                            .build();
                    return sessionRepository.save(session);
                });
    }

    public NotebookExecutionDto submitExecution(String sessionId, String cellId, String user, String authHeader) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId)
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
        session.setLastUsedAt(Instant.now());
        sessionRepository.save(session);

        String opId = kyuubiRestClient.submitStatement(
                session.getKyuubiSessionId(), session.getKyuubiInstance(), source, language, authHeader);

        String runId = UUID.randomUUID().toString();
        NotebookRun run = NotebookRun.builder()
                .id(runId)
                .notebookId(session.getNotebookId())
                .runType("CELL")
                .status("RUNNING")
                .triggeredBy(user)
                .startedAt(Instant.now())
                .cellsTotal(1)
                .cellsDone(0)
                .currentCellId(cellId)
                .currentExecutionId(opId)
                .kyuubiSessionRef(sessionId)
                .build();
        runRepository.save(run);

        String cellRunId = UUID.randomUUID().toString();
        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId(runId)
                .cellId(cellId)
                .executionOrder(0)
                .language(language)
                .status("RUNNING")
                .startedAt(Instant.now())
                .executionId(opId)
                .source(source)
                .executionCount(count)
                .build();
        cellRunRepository.save(cellRun);

        return toDto(cellRun, session.getNotebookId(), sessionId);
    }

    public NotebookExecutionDto syncAndGetExecution(String cellRunId, String user, String authHeader) {
        NotebookCellRun cellRun = cellRunRepository.findById(cellRunId)
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND));

        NotebookRun run = runRepository.findById(cellRun.getNotebookRunId())
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution run not found", HttpStatus.NOT_FOUND));

        if (!run.getTriggeredBy().equals(user)) {
            throw new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND);
        }

        if ("RUNNING".equals(cellRun.getStatus()) && cellRun.getExecutionId() != null) {
            NotebookKyuubiSession session = run.getKyuubiSessionRef() != null
                    ? sessionRepository.findById(run.getKyuubiSessionRef()).orElse(null)
                    : null;
            String instance = session != null ? session.getKyuubiInstance() : null;

            try {
                JsonNode event = kyuubiRestClient.getOperationEvent(cellRun.getExecutionId(), instance, authHeader);
                if (event != null && event.has("state")) {
                    String state = event.get("state").asText().toUpperCase();

                    if ("FINISHED".equals(state) || "ERROR".equals(state) || "TIMEOUT".equals(state) || "CANCELED".equals(state)) {
                        cellRun = cellRunRepository.findByIdForUpdate(cellRunId).orElse(cellRun);
                        if ("RUNNING".equals(cellRun.getStatus())) {
                            cellRun.setFinishedAt(Instant.now());
                            run.setFinishedAt(Instant.now());

                            if ("FINISHED".equals(state)) {
                                try {
                                    JsonNode rowset = kyuubiRestClient.getOperationRowset(cellRun.getExecutionId(), instance, maxRows, authHeader);
                                    cellRun.setStatus("SUCCEEDED");
                                    run.setStatus("SUCCEEDED");
                                    run.setCellsDone(1);

                                    // Xử lý output AUTO theo Mục 3:
                                    buildAutoOutput(cellRun, rowset);

                                    kyuubiRestClient.closeOperation(cellRun.getExecutionId(), instance, authHeader);
                                } catch (Exception rowsetEx) {
                                    cellRun.setStatus("FAILED");
                                    run.setStatus("FAILED");
                                    cellRun.setErrorMessage("Failed to fetch rowset: " + rowsetEx.getMessage());
                                    run.setErrorMessage(cellRun.getErrorMessage());
                                }
                            } else if ("CANCELED".equals(state)) {
                                cellRun.setStatus("CANCELED");
                                run.setStatus("CANCELED");
                                kyuubiRestClient.closeOperation(cellRun.getExecutionId(), instance, authHeader);
                            } else {
                                cellRun.setStatus("FAILED");
                                run.setStatus("FAILED");
                                String errMsg = "Execution failed on Kyuubi";
                                if (event.has("exception")) {
                                    JsonNode exNode = event.get("exception");
                                    errMsg = exNode.isObject() && exNode.has("message") ? exNode.get("message").asText() : exNode.asText();
                                }
                                cellRun.setErrorMessage(errMsg);
                                run.setErrorMessage(errMsg);

                                // Tạo error output JSON dạng nbformat
                                ObjectNode errOutput = objectMapper.createObjectNode();
                                errOutput.put("output_type", "error");
                                errOutput.put("ename", "ExecutionError");
                                errOutput.put("evalue", errMsg);
                                ArrayNode tb = errOutput.putArray("traceback");
                                tb.add(errMsg);
                                cellRun.setOutputType("ERROR");
                                cellRun.setOutputJson(errOutput.toString());

                                kyuubiRestClient.closeOperation(cellRun.getExecutionId(), instance, authHeader);
                            }

                            // Fetch logs và lưu vào notebook_execution_logs
                            List<String> logs = kyuubiRestClient.getOperationLog(cellRun.getExecutionId(), instance, 500, authHeader);
                            long seq = 0;
                            for (String line : logs) {
                                NotebookExecutionLog logEntry = NotebookExecutionLog.builder()
                                        .cellRunId(cellRunId)
                                        .sequence(seq++)
                                        .line(line)
                                        .build();
                                logRepository.save(logEntry);
                            }

                            cellRunRepository.save(cellRun);
                            runRepository.save(run);
                        }
                    }
                }
            } catch (NotebookApiException ex) {
                if ("RUNTIME_LOST".equals(ex.getCode())) {
                    cellRun.setStatus("LOST");
                    run.setStatus("LOST");
                    cellRun.setErrorMessage("Runtime lost or operation closed on engine");
                    cellRunRepository.save(cellRun);
                    runRepository.save(run);
                } else {
                    throw ex;
                }
            }
        }
        return toDto(cellRun, run.getNotebookId(), run.getKyuubiSessionRef());
    }

    private void buildAutoOutput(NotebookCellRun cellRun, JsonNode rowset) {
        if ("PYTHON".equalsIgnoreCase(cellRun.getLanguage())) {
            // Python stdout trong AUTO mode: 1 cột text
            StringBuilder stdout = new StringBuilder();
            if (rowset != null && rowset.isArray()) {
                for (JsonNode row : rowset) {
                    if (row.isArray() && row.size() > 0) {
                        stdout.append(row.get(0).asText()).append("\n");
                    } else {
                        stdout.append(row.asText()).append("\n");
                    }
                }
            }
            ObjectNode streamOut = objectMapper.createObjectNode();
            streamOut.put("output_type", "stream");
            streamOut.put("name", "stdout");
            streamOut.put("text", stdout.toString());

            cellRun.setOutputType("STREAM");
            cellRun.setOutputJson(streamOut.toString());
        } else {
            // SQL: table format
            ObjectNode execResult = objectMapper.createObjectNode();
            execResult.put("output_type", "execute_result");
            ObjectNode data = execResult.putObject("data");
            ObjectNode tableJson = data.putObject("application/vnd.notebook.table+json");

            ArrayNode cols = tableJson.putArray("columns");
            ArrayNode rows = tableJson.putArray("rows");
            tableJson.put("truncated", false);

            if (rowset != null && rowset.isArray()) {
                for (JsonNode row : rowset) {
                    rows.add(row);
                }
                cellRun.setRowCount((long) rowset.size());
            } else {
                cellRun.setRowCount(0L);
            }

            cellRun.setOutputType("TABLE");
            cellRun.setOutputJson(execResult.toString());
        }
    }

    public void cancelExecution(String cellRunId, String user, String authHeader) {
        NotebookCellRun cellRun = cellRunRepository.findById(cellRunId)
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND));

        NotebookRun run = runRepository.findById(cellRun.getNotebookRunId())
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Run not found", HttpStatus.NOT_FOUND));

        if (!run.getTriggeredBy().equals(user)) {
            throw new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND);
        }

        NotebookKyuubiSession session = run.getKyuubiSessionRef() != null
                ? sessionRepository.findById(run.getKyuubiSessionRef()).orElse(null)
                : null;
        String instance = session != null ? session.getKyuubiInstance() : null;

        kyuubiRestClient.cancelOperation(cellRun.getExecutionId(), instance, authHeader);
        cellRun.setStatus("CANCELED");
        run.setStatus("CANCELED");
        cellRunRepository.save(cellRun);
        runRepository.save(run);
    }

    public void restartRuntime(String sessionId, String user, String authHeader) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId)
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
        session.setExecutionCountSeq(0);
        session.setLastUsedAt(Instant.now());
        sessionRepository.save(session);
    }

    public List<NotebookExecutionDto> getLatestExecutions(String notebookId, String cellId, String user) {
        List<NotebookCellRun> runs = cellRunRepository.findLatestByNotebookAndCellAndUser(
                notebookId, cellId, user, PageRequest.of(0, 10));
        List<NotebookExecutionDto> dtos = new ArrayList<>();
        for (NotebookCellRun r : runs) {
            dtos.add(toDto(r, notebookId, null));
        }
        return dtos;
    }

    public String getExecutionLogs(String cellRunId) {
        List<NotebookExecutionLog> logs = logRepository.findByCellRunIdOrderBySequenceAsc(cellRunId);
        StringBuilder sb = new StringBuilder();
        for (NotebookExecutionLog l : logs) {
            sb.append(l.getLine()).append("\n");
        }
        return sb.toString();
    }

    private NotebookExecutionDto toDto(NotebookCellRun cellRun, String notebookId, String sessionId) {
        return NotebookExecutionDto.builder()
                .id(cellRun.getId())
                .sessionId(sessionId)
                .notebookId(notebookId)
                .cellId(cellRun.getCellId())
                .status(cellRun.getStatus())
                .language(cellRun.getLanguage())
                .statement(cellRun.getSource())
                .executionCount(cellRun.getExecutionCount())
                .resultOutput(cellRun.getOutputJson())
                .errorMessage(cellRun.getErrorMessage())
                .createdAt(cellRun.getStartedAt())
                .build();
    }
}
