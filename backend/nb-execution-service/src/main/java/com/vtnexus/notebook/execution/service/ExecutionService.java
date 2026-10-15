package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.execution.client.DocumentServiceClient;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.model.NotebookCellRun;
import com.vtnexus.notebook.execution.model.NotebookExecutionLog;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import com.vtnexus.notebook.execution.model.NotebookRun;
import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookExecutionLogRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import com.vtnexus.notebook.execution.repository.NotebookRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final NotebookKyuubiSessionRepository sessionRepository;
    private final NotebookRunRepository runRepository;
    private final NotebookCellRunRepository cellRunRepository;
    private final NotebookExecutionLogRepository logRepository;
    private final KyuubiRestClient kyuubiRestClient;
    private final DocumentServiceClient documentServiceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ServiceAccountTokenProvider serviceAccountTokenProvider;
    private final String authMode;
    private final KyuubiResultParser resultParser;
    private final int maxRows;

    public ExecutionService(
            NotebookKyuubiSessionRepository sessionRepository,
            NotebookRunRepository runRepository,
            NotebookCellRunRepository cellRunRepository,
            NotebookExecutionLogRepository logRepository,
            KyuubiRestClient kyuubiRestClient,
            DocumentServiceClient documentServiceClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            ServiceAccountTokenProvider serviceAccountTokenProvider,
            com.vtnexus.notebook.common.auth.AuthProperties authProperties,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.cellRunRepository = cellRunRepository;
        this.logRepository = logRepository;
        this.kyuubiRestClient = kyuubiRestClient;
        this.documentServiceClient = documentServiceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.serviceAccountTokenProvider = serviceAccountTokenProvider;
        String mode = authProperties.getMode();
        this.authMode = mode != null ? mode.toLowerCase() : "oidc";
        this.resultParser = new KyuubiResultParser();
        this.maxRows = notebookProperties.getResults().getMaxRows();
    }

    public record SessionCreationResult(NotebookKyuubiSession session, List<String> warnings) {}

    public SessionCreationResult getOrCreateSession(String notebookId, String user, String authHeader, Map<String, String> configs) {
        if (!documentServiceClient.canExecute(notebookId, user)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires RUNNER role or higher to open execution session", HttpStatus.FORBIDDEN);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, String> validConfigs = new HashMap<>();
        if (configs != null) {
            List<String> allowedKeys = kyuubiRestClient.getAllowedSessionConfigKeys();
            for (Map.Entry<String, String> e : configs.entrySet()) {
                if (allowedKeys.contains(e.getKey())) {
                    validConfigs.put(e.getKey(), e.getValue());
                } else {
                    warnings.add("Config key '" + e.getKey() + "' is not allowed and has been ignored");
                }
            }
        }

        // C9: Chỉ dùng lại session READY/BUSY
        Optional<NotebookKyuubiSession> existing = sessionRepository
                .findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(notebookId, user, List.of("READY", "BUSY"));
        if (existing.isPresent()) {
            return new SessionCreationResult(existing.get(), warnings);
        }

        // B4: Tra ranger_username qua documentServiceClient (cache 5 phút)
        String rangerUser = documentServiceClient.getRangerUsername(user);
        boolean isProxied = false;
        String effectiveAuthHeader = authHeader;

        if ("oidc".equals(authMode)) {
            if (rangerUser != null && !rangerUser.equals(user)) {
                isProxied = true;
                String serviceToken = serviceAccountTokenProvider.getServiceAccountToken();
                effectiveAuthHeader = "Bearer " + serviceToken;
            }
        } else {
            if (rangerUser != null && !rangerUser.equals(user)) {
                log.warn("Proxy user for user {} (ranger={}) in non-OIDC mode is not supported; proceeding as direct user", user, rangerUser);
            }
        }

        // Mở session mới trên Kyuubi (ngoài transaction)
        Map<String, String> kSession = kyuubiRestClient.openSession(effectiveAuthHeader, validConfigs, isProxied ? rangerUser : null);

        NotebookKyuubiSession session = NotebookKyuubiSession.builder()
                .id(UUID.randomUUID().toString())
                .notebookId(notebookId)
                .kyuubiSessionId(kSession.get("sessionId"))
                .kyuubiInstance(kSession.get("instance"))
                .userId(user)
                .status("READY")
                .executionCountSeq(0)
                .lastUsedAt(Instant.now())
                .proxied(isProxied)
                .effectiveUser(isProxied ? rangerUser : user)
                .build();

        NotebookKyuubiSession saved = transactionTemplate.execute(status -> sessionRepository.save(session));
        return new SessionCreationResult(saved, warnings);
    }

    public NotebookKyuubiSession getOrCreateSession(String notebookId, String user, String authHeader) {
        return getOrCreateSession(notebookId, user, authHeader, null).session();
    }


    /**
     * Vòng đời submit 2 pha (B6, C10):
     * 1. Transaction 1: khóa session bằng findByIdForUpdate, tăng executionCountSeq, tạo run (PENDING) và cell_run (PENDING).
     * 2. Ngoài transaction: gọi Kyuubi submitStatement.
     * 3. Transaction 2: thành công -> cập nhật RUNNING, execution_id, started_at. Thất bại -> cập nhật FAILED, error_code, error_message.
     */
    public NotebookExecutionDto submitExecution(String sessionId, String cellId, String user, String authHeader) {
        // Kiểm tra quyền
        NotebookKyuubiSession initialSession = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));

        if (!initialSession.getUserId().equals(user)) {
            throw new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND);
        }

        if (!documentServiceClient.canExecute(initialSession.getNotebookId(), user)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires RUNNER role to execute cell", HttpStatus.FORBIDDEN);
        }

        JsonNode cellData = documentServiceClient.getCell(initialSession.getNotebookId(), cellId, user);
        String cellType = cellData.path("cellType").asText("CODE");
        String language = cellData.path("language").asText("SQL");
        String source = cellData.path("source").asText("");

        if (!"CODE".equalsIgnoreCase(cellType)) {
            throw new NotebookApiException("INVALID_REQUEST", "Only CODE cells can be executed", HttpStatus.BAD_REQUEST);
        }

        final String runId = UUID.randomUUID().toString();
        final String cellRunId = UUID.randomUUID().toString();

        // Pha 1: Transaction 1 (khóa session, tạo PENDING)
        SubmitPreparation prep = transactionTemplate.execute(status -> {
            NotebookKyuubiSession lockedSession = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));
            if (!"READY".equals(lockedSession.getStatus()) && !"BUSY".equals(lockedSession.getStatus())) {
                throw new NotebookApiException("NOTEBOOK_SESSION_NOT_READY",
                        "Session is " + lockedSession.getStatus() + "; open a new session", HttpStatus.CONFLICT);
            }

            int count = (lockedSession.getExecutionCountSeq() != null ? lockedSession.getExecutionCountSeq() : 0) + 1;
            lockedSession.setExecutionCountSeq(count);
            lockedSession.setLastUsedAt(Instant.now());
            sessionRepository.save(lockedSession);

            NotebookRun run = NotebookRun.builder()
                    .id(runId)
                    .notebookId(lockedSession.getNotebookId())
                    .runType("CELL")
                    .status("PENDING")
                    .triggeredBy(user)
                    .cellsTotal(1)
                    .cellsDone(0)
                    .currentCellId(cellId)
                    .kyuubiSessionRef(sessionId)
                    .build();
            runRepository.save(run);

            NotebookCellRun cellRun = NotebookCellRun.builder()
                    .id(cellRunId)
                    .notebookRunId(runId)
                    .cellId(cellId)
                    .executionOrder(0)
                    .language(language)
                    .status("PENDING")
                    .source(source)
                    .executionCount(count)
                    .build();
            cellRunRepository.save(cellRun);

            boolean isProx = Boolean.TRUE.equals(lockedSession.getProxied());
            return new SubmitPreparation(lockedSession.getKyuubiSessionId(), lockedSession.getKyuubiInstance(), count, isProx);
        });

        // Pha 2: Ngoài transaction, gọi Kyuubi submitStatement
        String opId = null;
        Exception submitEx = null;
        try {
            String submitAuth = prep.proxied()
                    ? ("Bearer " + serviceAccountTokenProvider.getServiceAccountToken())
                    : authHeader;
            opId = kyuubiRestClient.submitStatement(prep.kyuubiSessionId(), prep.kyuubiInstance(), source, language, submitAuth);
        } catch (Exception ex) {
            submitEx = ex;
        }

        final String finalOpId = opId;
        final Exception finalSubmitEx = submitEx;

        // Pha 3: Transaction 2 (cập nhật kết quả submit). Chỉ ghi khi cell còn PENDING để không đè lệnh hủy
        // đến trong lúc submit. Lỗi submit được lưu FAILED và commit trước, rồi mới ném ra (B8).
        NotebookExecutionDto dto = transactionTemplate.execute(status -> {
            NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElseThrow();
            NotebookRun r = runRepository.findByIdForUpdate(runId).orElseThrow();

            if (!"PENDING".equals(cr.getStatus())) {
                if (finalOpId != null) {
                    cr.setExecutionId(finalOpId);
                    cellRunRepository.save(cr);
                }
                return toDto(cr, initialSession.getNotebookId(), sessionId, r.getErrorCode(), false);
            }

            Instant now = Instant.now();
            if (finalSubmitEx == null) {
                cr.setStatus("RUNNING");
                cr.setExecutionId(finalOpId);
                cr.setStartedAt(now);

                r.setStatus("RUNNING");
                r.setCurrentExecutionId(finalOpId);
                r.setStartedAt(now);
            } else {
                String errorCode = finalSubmitEx instanceof NotebookApiException nae ? nae.getCode() : "KYUUBI_SUBMIT_FAILED";
                cr.setStatus("FAILED");
                cr.setFinishedAt(now);
                cr.setErrorMessage("Failed to submit statement to Kyuubi");

                r.setStatus("FAILED");
                r.setFinishedAt(now);
                r.setErrorCode(errorCode);
                r.setErrorMessage("Failed to submit statement to Kyuubi");
            }
            cellRunRepository.save(cr);
            runRepository.save(r);
            return toDto(cr, initialSession.getNotebookId(), sessionId, r.getErrorCode(), false);
        });

        if (finalSubmitEx != null) {
            log.warn("Submit statement failed for cellRunId {}: {}", cellRunId, finalSubmitEx.getMessage());
            if (finalSubmitEx instanceof NotebookApiException nae) {
                if ("RUNTIME_LOST".equals(nae.getCode())) {
                    markSessionLost(sessionId);
                }
                throw nae;
            }
            throw new NotebookApiException("KYUUBI_ERROR", "Failed to submit statement to Kyuubi", HttpStatus.BAD_GATEWAY);
        }

        // Người dùng đã hủy trong lúc submit: operation vừa tạo trên Kyuubi phải hủy theo.
        if ("CANCELED".equals(dto.getStatus()) && finalOpId != null) {
            try {
                String cancelAuth = prep.proxied()
                        ? ("Bearer " + serviceAccountTokenProvider.getServiceAccountToken())
                        : authHeader;
                kyuubiRestClient.cancelOperation(finalOpId, prep.kyuubiInstance(), cancelAuth);
            } catch (Exception ex) {
                log.warn("Failed to cancel Kyuubi operation {} after late cancel: {}", finalOpId, ex.getMessage());
            }
        }
        return dto;
    }

    private void markSessionLost(String sessionId) {
        transactionTemplate.executeWithoutResult(status ->
                sessionRepository.findByIdForUpdate(sessionId).ifPresent(sess -> {
                    sess.setStatus("LOST");
                    sessionRepository.save(sess);
                }));
    }

    private record SubmitPreparation(String kyuubiSessionId, String kyuubiInstance, int executionCount, boolean proxied) {}

    /**
     * B6 syncCellRun: Ánh xạ đầy đủ trạng thái Kyuubi và xử lý timeout / connection error.
     */
    public NotebookExecutionDto syncAndGetExecution(String cellRunId, String user, String authHeader) {
        NotebookCellRun cellRun = cellRunRepository.findById(cellRunId)
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND));

        NotebookRun run = runRepository.findById(cellRun.getNotebookRunId())
                .orElseThrow(() -> new NotebookApiException("EXECUTION_NOT_FOUND", "Execution run not found", HttpStatus.NOT_FOUND));

        if (!run.getTriggeredBy().equals(user)) {
            throw new NotebookApiException("EXECUTION_NOT_FOUND", "Execution not found", HttpStatus.NOT_FOUND);
        }

        if (!"RUNNING".equals(cellRun.getStatus()) || cellRun.getExecutionId() == null) {
            return toDto(cellRun, run.getNotebookId(), run.getKyuubiSessionRef(), run.getErrorCode(), false);
        }

        NotebookKyuubiSession session = run.getKyuubiSessionRef() != null
                ? sessionRepository.findById(run.getKyuubiSessionRef()).orElse(null)
                : null;
        String instance = session != null ? session.getKyuubiInstance() : null;

        String effectiveAuth = (session != null && Boolean.TRUE.equals(session.getProxied()))
                ? ("Bearer " + serviceAccountTokenProvider.getServiceAccountToken())
                : authHeader;

        // Gọi Kyuubi NGOÀI transaction
        JsonNode event = null;
        boolean kyuubiUnavailable = false;
        boolean opNotFound = false;

        try {
            event = kyuubiRestClient.getOperationEvent(cellRun.getExecutionId(), instance, effectiveAuth);
        } catch (NotebookApiException ex) {
            if ("RUNTIME_LOST".equals(ex.getCode())) {
                opNotFound = true;
            } else if ("KYUUBI_UNAVAILABLE".equals(ex.getCode()) || ex.isRetryable()) {
                kyuubiUnavailable = true;
            } else {
                throw ex;
            }
        } catch (Exception ex) {
            kyuubiUnavailable = true;
        }

        // Nếu Kyuubi không trả lời -> giữ RUNNING, trả DTO kèm stale = true (B6)
        if (kyuubiUnavailable) {
            return toDto(cellRun, run.getNotebookId(), run.getKyuubiSessionRef(), run.getErrorCode(), true);
        }

        // Nếu HTTP 404 operation -> LOST
        if (opNotFound) {
            transactionTemplate.executeWithoutResult(status -> {
                NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(cellRun);
                NotebookRun r = runRepository.findByIdForUpdate(run.getId()).orElse(run);
                cr.setStatus("LOST");
                cr.setFinishedAt(Instant.now());
                cr.setErrorMessage("Operation not found on Kyuubi engine");

                r.setStatus("LOST");
                r.setFinishedAt(Instant.now());
                r.setErrorCode("OPERATION_NOT_FOUND");
                r.setErrorMessage("Operation not found on Kyuubi engine");

                cellRunRepository.save(cr);
                runRepository.save(r);
            });
            return toDto(cellRunRepository.findById(cellRunId).orElse(cellRun), run.getNotebookId(), run.getKyuubiSessionRef(), "OPERATION_NOT_FOUND", false);
        }

        String rawState = event != null && event.has("state") ? event.get("state").asText().toUpperCase() : "UNKNOWN";

        // Đồng bộ log khi còn RUNNING (B5.5)
        if ("INITIALIZED".equals(rawState) || "PENDING".equals(rawState) || "RUNNING".equals(rawState)) {
            syncLogsAndHeartbeat(cellRunId, run.getId(), cellRun.getExecutionId(), instance, effectiveAuth);
            return toDto(cellRunRepository.findById(cellRunId).orElse(cellRun), run.getNotebookId(), run.getKyuubiSessionRef(), run.getErrorCode(), false);
        }

        // Xử lý các trạng thái kết thúc hoặc LOST
        handleOperationTerminalOrLost(cellRunId, run.getId(), rawState, event, instance, effectiveAuth);

        NotebookCellRun updatedCell = cellRunRepository.findById(cellRunId).orElse(cellRun);
        NotebookRun updatedRun = runRepository.findById(run.getId()).orElse(run);
        return toDto(updatedCell, updatedRun.getNotebookId(), updatedRun.getKyuubiSessionRef(), updatedRun.getErrorCode(), false);
    }

    private void syncLogsAndHeartbeat(String cellRunId, String runId, String opId, String instance, String authHeader) {
        List<String> logs = Collections.emptyList();
        try {
            logs = kyuubiRestClient.getOperationLog(opId, instance, 500, authHeader);
        } catch (Exception ex) {
            log.warn("Failed to fetch running log: {}", ex.getMessage());
        }

        final List<String> finalLogs = logs;
        transactionTemplate.executeWithoutResult(status -> {
            NotebookRun r = runRepository.findById(runId).orElse(null);
            if (r != null) {
                r.setHeartbeatAt(Instant.now());
                runRepository.save(r);
            }
            if (!finalLogs.isEmpty()) {
                appendLogs(cellRunId, finalLogs);
            }
        });
    }

    /**
     * Phải gọi trong transaction. Khóa dòng cell-run trước khi đọc max(sequence) để hai lần sync đồng thời
     * (/events và GET) không cấp trùng số thứ tự (UNIQUE cell_run_id, sequence).
     */
    private void appendLogs(String cellRunId, List<String> lines) {
        cellRunRepository.findByIdForUpdate(cellRunId);
        Long maxSeq = logRepository.findMaxSequenceByCellRunId(cellRunId);
        long startSeq = (maxSeq == null) ? 0 : maxSeq + 1;
        for (String line : lines) {
            NotebookExecutionLog logEntry = NotebookExecutionLog.builder()
                    .cellRunId(cellRunId)
                    .sequence(startSeq++)
                    .line(line)
                    .build();
            logRepository.save(logEntry);
        }
    }

    private void handleOperationTerminalOrLost(
            String cellRunId, String runId, String state, JsonNode event, String instance, String authHeader) {

        NotebookCellRun cellRun = cellRunRepository.findById(cellRunId).orElse(null);
        if (cellRun == null || !"RUNNING".equals(cellRun.getStatus())) {
            return;
        }

        String opId = cellRun.getExecutionId();

        if ("FINISHED".equals(state)) {
            // Lấy metadata và rowset ngoài transaction (B5)
            JsonNode metadata = null;
            JsonNode rowset = null;
            Exception fetchEx = null;

            try {
                if ("SQL".equalsIgnoreCase(cellRun.getLanguage())) {
                    metadata = kyuubiRestClient.getResultSetMetadata(opId, instance, authHeader);
                }
                rowset = fetchRows(opId, instance, authHeader);
            } catch (Exception ex) {
                fetchEx = ex;
            }

            final JsonNode finalMetadata = metadata;
            final JsonNode finalRowset = rowset;
            final Exception finalFetchEx = fetchEx;

            // Fetch log cuối cùng
            List<String> logs = Collections.emptyList();
            try {
                logs = kyuubiRestClient.getOperationLog(opId, instance, 500, authHeader);
            } catch (Exception ignored) {}

            final List<String> finalLogs = logs;

            transactionTemplate.executeWithoutResult(txStatus -> {
                NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(null);
                NotebookRun r = runRepository.findByIdForUpdate(runId).orElse(null);
                if (cr == null || !"RUNNING".equals(cr.getStatus()) || r == null) {
                    return;
                }

                Instant finishedAt = Instant.now();
                cr.setFinishedAt(finishedAt);
                r.setFinishedAt(finishedAt);
                if (cr.getStartedAt() != null) {
                    cr.setExecutionTimeMs(finishedAt.toEpochMilli() - cr.getStartedAt().toEpochMilli());
                }

                if (finalFetchEx != null) {
                    cr.setStatus("FAILED");
                    cr.setErrorMessage("Failed to fetch query results");
                    r.setStatus("FAILED");
                    r.setErrorCode("ROWSET_FETCH_FAILED");
                    r.setErrorMessage("Failed to fetch query results");
                } else {
                    cr.setStatus("SUCCEEDED");
                    r.setStatus("SUCCEEDED");
                    r.setCellsDone(1);
                    buildAutoOutput(cr, finalMetadata, finalRowset);
                }

                if (!finalLogs.isEmpty()) {
                    appendLogs(cellRunId, finalLogs);
                }

                cellRunRepository.save(cr);
                runRepository.save(r);
            });

            try {
                kyuubiRestClient.closeOperation(opId, instance, authHeader);
            } catch (Exception ignored) {}

        } else if ("CANCELED".equals(state)) {
            transactionTemplate.executeWithoutResult(txStatus -> {
                NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(null);
                NotebookRun r = runRepository.findByIdForUpdate(runId).orElse(null);
                if (cr != null && "RUNNING".equals(cr.getStatus()) && r != null) {
                    Instant now = Instant.now();
                    cr.setStatus("CANCELED");
                    cr.setFinishedAt(now);
                    if (cr.getStartedAt() != null) {
                        cr.setExecutionTimeMs(now.toEpochMilli() - cr.getStartedAt().toEpochMilli());
                    }
                    r.setStatus("CANCELED");
                    r.setFinishedAt(now);
                    cellRunRepository.save(cr);
                    runRepository.save(r);
                }
            });
            try {
                kyuubiRestClient.closeOperation(opId, instance, authHeader);
            } catch (Exception ignored) {}

        } else if ("ERROR".equals(state) || "TIMEOUT".equals(state)) {
            String rawErr = "Execution failed on Kyuubi";
            if (event != null && event.has("exception")) {
                JsonNode exNode = event.get("exception");
                rawErr = exNode.isObject() && exNode.has("message") ? exNode.get("message").asText() : exNode.asText();
            }

            KyuubiResultParser.ErrorInfo errorInfo = resultParser.error(rawErr);
            final String errorCode = "TIMEOUT".equals(state) ? "TIMEOUT" : "EXECUTION_ERROR";
            final String cleanMsg = "TIMEOUT".equals(state) ? "Execution timed out" : errorInfo.evalue();

            // Logs
            List<String> logs = Collections.emptyList();
            try {
                logs = kyuubiRestClient.getOperationLog(opId, instance, 500, authHeader);
            } catch (Exception ignored) {}
            final List<String> finalLogs = logs;

            transactionTemplate.executeWithoutResult(txStatus -> {
                NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(null);
                NotebookRun r = runRepository.findByIdForUpdate(runId).orElse(null);
                if (cr != null && "RUNNING".equals(cr.getStatus()) && r != null) {
                    Instant now = Instant.now();
                    cr.setStatus("FAILED");
                    cr.setFinishedAt(now);
                    if (cr.getStartedAt() != null) {
                        cr.setExecutionTimeMs(now.toEpochMilli() - cr.getStartedAt().toEpochMilli());
                    }
                    cr.setErrorMessage(cleanMsg);

                    // Tạo nbformat error output
                    ObjectNode errOutput = objectMapper.createObjectNode();
                    errOutput.put("output_type", "error");
                    errOutput.put("ename", errorInfo.ename());
                    errOutput.put("evalue", errorInfo.evalue());
                    ArrayNode tb = errOutput.putArray("traceback");
                    for (String tbLine : errorInfo.traceback()) {
                        tb.add(tbLine);
                    }
                    cr.setOutputType("ERROR");
                    cr.setOutputJson(errOutput.toString());

                    r.setStatus("FAILED");
                    r.setFinishedAt(now);
                    r.setErrorCode(errorCode);
                    r.setErrorMessage(cleanMsg);

                    if (!finalLogs.isEmpty()) {
                        appendLogs(cellRunId, finalLogs);
                    }

                    cellRunRepository.save(cr);
                    runRepository.save(r);
                }
            });
            try {
                kyuubiRestClient.closeOperation(opId, instance, authHeader);
            } catch (Exception ignored) {}

        } else {
            // CLOSED, UNKNOWN, hoặc trạng thái lạ -> LOST, error_code = "KYUUBI_" + state
            final String errorCode = "KYUUBI_" + state;
            transactionTemplate.executeWithoutResult(txStatus -> {
                NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(null);
                NotebookRun r = runRepository.findByIdForUpdate(runId).orElse(null);
                if (cr != null && "RUNNING".equals(cr.getStatus()) && r != null) {
                    Instant now = Instant.now();
                    cr.setStatus("LOST");
                    cr.setFinishedAt(now);
                    if (cr.getStartedAt() != null) {
                        cr.setExecutionTimeMs(now.toEpochMilli() - cr.getStartedAt().toEpochMilli());
                    }
                    cr.setErrorMessage("Kyuubi engine operation state: " + state);

                    r.setStatus("LOST");
                    r.setFinishedAt(now);
                    r.setErrorCode(errorCode);
                    r.setErrorMessage("Kyuubi engine operation state: " + state);

                    cellRunRepository.save(cr);
                    runRepository.save(r);
                }
            });
        }
    }

    /** Kích thước một trang FETCH_NEXT khi lấy kết quả từ Kyuubi. */
    private static final int FETCH_PAGE_SIZE = 500;

    /**
     * Lấy kết quả theo từng trang FETCH_NEXT cho tới khi Kyuubi trả trang rỗng hoặc đã có {@code maxRows + 1}
     * dòng (dòng dư chỉ để biết còn dữ liệu). Trả về {@code {"rows":[...], "truncated":bool}} cùng dạng
     * rowset của Kyuubi để {@link KyuubiResultParser} đọc như thường.
     */
    private JsonNode fetchRows(String opId, String instance, String authHeader) {
        ObjectNode merged = objectMapper.createObjectNode();
        ArrayNode allRows = merged.putArray("rows");
        while (allRows.size() <= maxRows) {
            int want = Math.min(FETCH_PAGE_SIZE, maxRows + 1 - allRows.size());
            JsonNode page = kyuubiRestClient.getOperationRowset(opId, instance, want, authHeader);
            JsonNode pageRows = page.path("rows");
            if (!pageRows.isArray() || pageRows.isEmpty()) {
                break;
            }
            pageRows.forEach(allRows::add);
            if (pageRows.size() < want) {
                break;
            }
        }
        boolean truncated = allRows.size() > maxRows;
        while (allRows.size() > maxRows) {
            allRows.remove(allRows.size() - 1);
        }
        merged.put("truncated", truncated);
        return merged;
    }

    private void buildAutoOutput(NotebookCellRun cellRun, JsonNode metadata, JsonNode rowset) {
        if ("PYTHON".equalsIgnoreCase(cellRun.getLanguage())) {
            List<List<JsonNode>> parsedRows = resultParser.rows(rowset);
            StringBuilder stdout = new StringBuilder();
            for (List<JsonNode> row : parsedRows) {
                if (!row.isEmpty()) {
                    JsonNode val = row.get(0);
                    stdout.append(val != null && !val.isNull() ? val.asText() : "").append("\n");
                }
            }

            String stdoutStr = stdout.toString();
            if (stdoutStr.isEmpty()) {
                cellRun.setOutputType("NONE");
                cellRun.setOutputJson(null);
                cellRun.setRowCount(0L);
            } else {
                ObjectNode streamOut = objectMapper.createObjectNode();
                streamOut.put("output_type", "stream");
                streamOut.put("name", "stdout");
                streamOut.put("text", stdoutStr);

                cellRun.setOutputType("STREAM");
                cellRun.setOutputJson(streamOut.toString());
                cellRun.setRowCount((long) parsedRows.size());
            }
        } else {
            // SQL: table format + plain ascii
            List<KyuubiResultParser.Column> cols = resultParser.columns(metadata);
            List<List<JsonNode>> rows = resultParser.rows(rowset);

            ObjectNode execResult = objectMapper.createObjectNode();
            execResult.put("output_type", "execute_result");
            ObjectNode data = execResult.putObject("data");

            ObjectNode tableJson = data.putObject("application/vnd.notebook.table+json");
            ArrayNode colsArray = tableJson.putArray("columns");
            for (KyuubiResultParser.Column col : cols) {
                ObjectNode c = colsArray.addObject();
                c.put("name", col.columnName());
                c.put("type", col.dataType());
            }

            ArrayNode rowsArray = tableJson.putArray("rows");
            for (List<JsonNode> row : rows) {
                ArrayNode r = rowsArray.addArray();
                for (JsonNode fieldVal : row) {
                    r.add(fieldVal);
                }
            }

            tableJson.put("truncated", rowset != null && rowset.path("truncated").asBoolean(false));

            // Plain text ascii table
            String asciiTable = KyuubiResultParser.renderPlainAsciiTable(cols, rows);
            data.put("text/plain", asciiTable);

            cellRun.setRowCount((long) rows.size());
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

        // C8: cancelExecution dùng findByIdForUpdate, chỉ đổi khi trạng thái còn PENDING/RUNNING. Đặt cancel_requested=true trước khi gọi Kyuubi.
        boolean proceedCancel = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(null);
            NotebookRun r = runRepository.findByIdForUpdate(run.getId()).orElse(null);
            if (cr == null || r == null) return false;

            if ("PENDING".equals(cr.getStatus()) || "RUNNING".equals(cr.getStatus())) {
                r.setCancelRequested(true);
                runRepository.save(r);
                return true;
            }
            return false;
        }));

        if (!proceedCancel) {
            return;
        }

        NotebookKyuubiSession session = run.getKyuubiSessionRef() != null
                ? sessionRepository.findById(run.getKyuubiSessionRef()).orElse(null)
                : null;
        String instance = session != null ? session.getKyuubiInstance() : null;
        String effectiveCancelAuth = (session != null && Boolean.TRUE.equals(session.getProxied()))
                ? ("Bearer " + serviceAccountTokenProvider.getServiceAccountToken())
                : authHeader;

        if (cellRun.getExecutionId() != null) {
            try {
                kyuubiRestClient.cancelOperation(cellRun.getExecutionId(), instance, effectiveCancelAuth);
            } catch (Exception ex) {
                log.warn("Failed to cancel operation on Kyuubi: {}", ex.getMessage());
            }
        }

        transactionTemplate.executeWithoutResult(status -> {
            NotebookCellRun cr = cellRunRepository.findByIdForUpdate(cellRunId).orElse(null);
            NotebookRun r = runRepository.findByIdForUpdate(run.getId()).orElse(null);
            if (cr != null && ("PENDING".equals(cr.getStatus()) || "RUNNING".equals(cr.getStatus()))) {
                cr.setStatus("CANCELED");
                cr.setFinishedAt(Instant.now());
                cellRunRepository.save(cr);
            }
            if (r != null && ("PENDING".equals(r.getStatus()) || "RUNNING".equals(r.getStatus()))) {
                r.setStatus("CANCELED");
                r.setFinishedAt(Instant.now());
                runRepository.save(r);
            }
        });
    }

    public void closeSession(String sessionId, String user, String authHeader) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getUserId().equals(user)) {
            if (session.getKyuubiSessionId() != null) {
                try {
                    String closeAuth = Boolean.TRUE.equals(session.getProxied())
                            ? ("Bearer " + serviceAccountTokenProvider.getServiceAccountToken())
                            : authHeader;
                    kyuubiRestClient.closeSession(session.getKyuubiSessionId(), session.getKyuubiInstance(), closeAuth);
                } catch (Exception ignored) {}
            }
            transactionTemplate.executeWithoutResult(status -> {
                NotebookKyuubiSession s = sessionRepository.findByIdForUpdate(sessionId).orElse(session);
                s.setStatus("CLOSED");
                sessionRepository.save(s);
            });
        }
    }

    public void interruptSession(String sessionId, String user, String authHeader) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));

        if (!session.getUserId().equals(user)) {
            throw new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND);
        }

        // Tìm cell-run RUNNING hoặc PENDING của session đó rồi hủy
        List<NotebookCellRun> runningCellRuns = cellRunRepository.findRunningBySessionId(sessionId);
        for (NotebookCellRun cr : runningCellRuns) {
            cancelExecution(cr.getId(), user, authHeader);
        }
    }

    public void restartRuntime(String sessionId, String user, String authHeader) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND));

        if (!session.getUserId().equals(user)) {
            throw new NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", HttpStatus.NOT_FOUND);
        }

        String rangerUser = documentServiceClient.getRangerUsername(user);
        boolean isProxied = false;
        String effectiveAuthHeader = authHeader;
        Map<String, String> configs = new HashMap<>();

        if ("oidc".equals(authMode)) {
            if (rangerUser != null && !rangerUser.equals(user)) {
                isProxied = true;
                String serviceToken = serviceAccountTokenProvider.getServiceAccountToken();
                effectiveAuthHeader = "Bearer " + serviceToken;
            }
        }

        if (session.getKyuubiSessionId() != null) {
            try {
                String closeAuth = Boolean.TRUE.equals(session.getProxied())
                        ? ("Bearer " + serviceAccountTokenProvider.getServiceAccountToken())
                        : authHeader;
                kyuubiRestClient.closeSession(session.getKyuubiSessionId(), session.getKyuubiInstance(), closeAuth);
            } catch (Exception ignored) {}
        }

        Map<String, String> newSession = kyuubiRestClient.openSession(effectiveAuthHeader, configs, isProxied ? rangerUser : null);

        final boolean finalProxied = isProxied;
        final String finalEffectiveUser = isProxied ? rangerUser : user;

        transactionTemplate.executeWithoutResult(status -> {
            NotebookKyuubiSession s = sessionRepository.findByIdForUpdate(sessionId).orElse(session);
            s.setKyuubiSessionId(newSession.get("sessionId"));
            s.setKyuubiInstance(newSession.get("instance"));
            s.setStatus("READY");
            s.setExecutionCountSeq(0);
            s.setLastUsedAt(Instant.now());
            s.setProxied(finalProxied);
            s.setEffectiveUser(finalEffectiveUser);
            sessionRepository.save(s);
        });
    }

    public List<NotebookExecutionDto> getLatestExecutions(String notebookId, String cellId, String user) {
        List<NotebookCellRun> runs = cellRunRepository.findLatestByNotebookAndCellAndUser(
                notebookId, cellId, user, PageRequest.of(0, 10));
        List<NotebookExecutionDto> dtos = new ArrayList<>();
        for (NotebookCellRun r : runs) {
            dtos.add(toDto(r, notebookId, null, null, false));
        }
        return dtos;
    }

    public String getExecutionLogs(String cellRunId) {
        return getExecutionLogs(cellRunId, null);
    }

    public String getExecutionLogs(String cellRunId, Long afterSequence) {
        List<NotebookExecutionLog> logs = (afterSequence != null && afterSequence >= 0)
                ? logRepository.findByCellRunIdAndSequenceGreaterThanOrderBySequenceAsc(cellRunId, afterSequence)
                : logRepository.findByCellRunIdOrderBySequenceAsc(cellRunId);
        StringBuilder sb = new StringBuilder();
        for (NotebookExecutionLog l : logs) {
            sb.append(l.getLine()).append("\n");
        }
        return sb.toString();
    }


    private NotebookExecutionDto toDto(NotebookCellRun cellRun, String notebookId, String sessionId, String errorCode, boolean stale) {
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
                .errorCode(errorCode)
                .stale(stale ? true : null)
                .createdAt(cellRun.getStartedAt())
                .build();
    }
}
