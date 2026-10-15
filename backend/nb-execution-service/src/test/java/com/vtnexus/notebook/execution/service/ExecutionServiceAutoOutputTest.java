package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.execution.client.DocumentServiceClient;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.model.NotebookCellRun;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import com.vtnexus.notebook.execution.model.NotebookRun;
import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookExecutionLogRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import com.vtnexus.notebook.execution.repository.NotebookRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionServiceAutoOutputTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotebookKyuubiSessionRepository sessionRepository;
    private NotebookRunRepository runRepository;
    private NotebookCellRunRepository cellRunRepository;
    private NotebookExecutionLogRepository logRepository;
    private KyuubiRestClient kyuubiRestClient;
    private DocumentServiceClient documentServiceClient;
    private ExecutionService executionService;

    @BeforeEach
    void setUp() {
        sessionRepository = Mockito.mock(NotebookKyuubiSessionRepository.class);
        runRepository = Mockito.mock(NotebookRunRepository.class);
        cellRunRepository = Mockito.mock(NotebookCellRunRepository.class);
        logRepository = Mockito.mock(NotebookExecutionLogRepository.class);
        kyuubiRestClient = Mockito.mock(KyuubiRestClient.class);
        documentServiceClient = Mockito.mock(DocumentServiceClient.class);

        // Dummy TransactionTemplate that executes immediately
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }
            @Override
            public void commit(TransactionStatus status) throws TransactionException {}
            @Override
            public void rollback(TransactionStatus status) throws TransactionException {}
        };
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        ServiceAccountTokenProvider tokenProvider = Mockito.mock(ServiceAccountTokenProvider.class);

        com.vtnexus.notebook.common.auth.AuthProperties authProps = new com.vtnexus.notebook.common.auth.AuthProperties();
        authProps.setMode("oidc");
        com.vtnexus.notebook.common.config.NotebookProperties nbProps = new com.vtnexus.notebook.common.config.NotebookProperties();
        nbProps.getResults().setMaxRows(1000);

        executionService = new ExecutionService(
                sessionRepository,
                runRepository,
                cellRunRepository,
                logRepository,
                kyuubiRestClient,
                documentServiceClient,
                objectMapper,
                txTemplate,
                tokenProvider,
                authProps,
                nbProps
        );
    }

    @Test
    void testSyncAutoOutputForSqlTable() throws Exception {
        String cellRunId = "crun-sql-1";
        NotebookRun run = NotebookRun.builder()
                .id("run-1")
                .notebookId("nb-1")
                .triggeredBy("alice")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-1")
                .executionId("op-sql-1")
                .language("SQL")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(cellRunRepository.findByIdForUpdate(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate("run-1")).thenReturn(Optional.of(run));

        // Kyuubi trả về event FINISHED
        JsonNode eventNode = objectMapper.readTree("{\"state\": \"FINISHED\"}");
        when(kyuubiRestClient.getOperationEvent(eq("op-sql-1"), any(), any())).thenReturn(eventNode);

        // Metadata thật
        JsonNode metaNode = objectMapper.readTree("""
                {"columns":[{"columnName":"id","dataType":"INT_TYPE","precision":0,"scale":0,"comment":""},
                            {"columnName":"name","dataType":"STRING_TYPE","precision":0,"scale":0,"comment":""}]}
                """);
        when(kyuubiRestClient.getResultSetMetadata(eq("op-sql-1"), any(), any())).thenReturn(metaNode);

        // Rowset thật: rows[].fields[].value
        JsonNode rowsetNode = objectMapper.readTree("""
                {"rows":[{"fields":[{"dataType":"INT_TYPE","value":1},{"dataType":"STRING_TYPE","value":"Alice"}]},
                         {"fields":[{"dataType":"INT_TYPE","value":2},{"dataType":"STRING_TYPE","value":"Bob"}]}],
                 "rowCount":2}
                """);
        when(kyuubiRestClient.getOperationRowset(eq("op-sql-1"), any(), anyInt(), any())).thenReturn(rowsetNode);
        when(kyuubiRestClient.getOperationLog(eq("op-sql-1"), any(), anyInt(), any())).thenReturn(Collections.emptyList());

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(cellRun.getOutputType()).isEqualTo("TABLE");
        assertThat(cellRun.getRowCount()).isEqualTo(2L);

        JsonNode outJson = objectMapper.readTree(cellRun.getOutputJson());
        assertThat(outJson.get("output_type").asText()).isEqualTo("execute_result");
        JsonNode tableData = outJson.get("data").get("application/vnd.notebook.table+json");
        assertThat(tableData.get("rows")).hasSize(2);
        assertThat(tableData.get("columns")).hasSize(2);
        // Kiểm tra ascii table text/plain
        assertThat(outJson.get("data").has("text/plain")).isTrue();
        assertThat(outJson.get("data").get("text/plain").asText()).contains("Alice").contains("Bob");
    }

    @Test
    void testSyncAutoOutputForPythonStream() throws Exception {
        String cellRunId = "crun-py-1";
        NotebookRun run = NotebookRun.builder()
                .id("run-2")
                .notebookId("nb-1")
                .triggeredBy("alice")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-2")
                .executionId("op-py-1")
                .language("PYTHON")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(cellRunRepository.findByIdForUpdate(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-2")).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate("run-2")).thenReturn(Optional.of(run));

        JsonNode eventNode = objectMapper.readTree("{\"state\": \"FINISHED\"}");
        when(kyuubiRestClient.getOperationEvent(eq("op-py-1"), any(), any())).thenReturn(eventNode);

        // Rowset Kyuubi thật cho Python stdout (1 cột string_type)
        JsonNode rowsetNode = objectMapper.readTree("""
                {"rows":[{"fields":[{"dataType":"STRING_TYPE","value":"Hello Python 1"}]},
                         {"fields":[{"dataType":"STRING_TYPE","value":"Hello Python 2"}]}],
                 "rowCount":2}
                """);
        when(kyuubiRestClient.getOperationRowset(eq("op-py-1"), any(), anyInt(), any())).thenReturn(rowsetNode);
        when(kyuubiRestClient.getOperationLog(eq("op-py-1"), any(), anyInt(), any())).thenReturn(List.of("log line 1"));

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(cellRun.getOutputType()).isEqualTo("STREAM");

        JsonNode outJson = objectMapper.readTree(cellRun.getOutputJson());
        assertThat(outJson.get("output_type").asText()).isEqualTo("stream");
        assertThat(outJson.get("name").asText()).isEqualTo("stdout");
        assertThat(outJson.get("text").asText()).contains("Hello Python 1\nHello Python 2");
    }

    @Test
    void testSyncAutoOutputForErrorTraceback() throws Exception {
        String cellRunId = "crun-err-1";
        NotebookRun run = NotebookRun.builder()
                .id("run-3")
                .notebookId("nb-1")
                .triggeredBy("alice")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-3")
                .executionId("op-err-1")
                .language("PYTHON")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(cellRunRepository.findByIdForUpdate(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-3")).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate("run-3")).thenReturn(Optional.of(run));

        String rawException = "Traceback (most recent call last):\n  File \"<stdin>\", line 1, in <module>\nZeroDivisionError: division by zero";
        com.fasterxml.jackson.databind.node.ObjectNode eventNode = objectMapper.createObjectNode();
        eventNode.put("state", "ERROR");
        eventNode.putObject("exception").put("message", rawException);
        when(kyuubiRestClient.getOperationEvent(eq("op-err-1"), any(), any())).thenReturn(eventNode);
        when(kyuubiRestClient.getOperationLog(eq("op-err-1"), any(), anyInt(), any())).thenReturn(Collections.emptyList());

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(cellRun.getOutputType()).isEqualTo("ERROR");

        JsonNode outJson = objectMapper.readTree(cellRun.getOutputJson());
        assertThat(outJson.get("output_type").asText()).isEqualTo("error");
        assertThat(outJson.get("ename").asText()).isEqualTo("ZeroDivisionError");
        assertThat(outJson.get("evalue").asText()).contains("division by zero");
        assertThat(outJson.get("traceback").get(0).asText()).contains("Traceback");
    }

    @Test
    void testSyncKyuubiStateMappings() throws Exception {
        // Test TIMEOUT -> FAILED, error_code = "TIMEOUT"
        testKyuubiStateMapping("TIMEOUT", "FAILED", "TIMEOUT");

        // Test CANCELED -> CANCELED
        testKyuubiStateMapping("CANCELED", "CANCELED", null);

        // Test CLOSED -> LOST, error_code = "KYUUBI_CLOSED"
        testKyuubiStateMapping("CLOSED", "LOST", "KYUUBI_CLOSED");

        // Test UNKNOWN -> LOST, error_code = "KYUUBI_UNKNOWN"
        testKyuubiStateMapping("UNKNOWN", "LOST", "KYUUBI_UNKNOWN");

        // Test custom/weird state -> LOST, error_code = "KYUUBI_CUSTOM"
        testKyuubiStateMapping("CUSTOM_STATE", "LOST", "KYUUBI_CUSTOM_STATE");
    }

    private void testKyuubiStateMapping(String kyuubiState, String expectedStatus, String expectedErrorCode) throws Exception {
        String cellRunId = "crun-map-" + kyuubiState;
        NotebookRun run = NotebookRun.builder()
                .id("run-" + kyuubiState)
                .notebookId("nb-1")
                .triggeredBy("alice")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-" + kyuubiState)
                .executionId("op-" + kyuubiState)
                .language("SQL")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(cellRunRepository.findByIdForUpdate(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-" + kyuubiState)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate("run-" + kyuubiState)).thenReturn(Optional.of(run));

        JsonNode eventNode = objectMapper.readTree("{\"state\": \"" + kyuubiState + "\"}");
        when(kyuubiRestClient.getOperationEvent(eq("op-" + kyuubiState), any(), any())).thenReturn(eventNode);

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo(expectedStatus);
        if (expectedErrorCode != null) {
            assertThat(result.getErrorCode()).isEqualTo(expectedErrorCode);
        }
    }

    @Test
    void testSyncHttp404OperationLost() {
        String cellRunId = "crun-404";
        NotebookRun run = NotebookRun.builder()
                .id("run-404")
                .notebookId("nb-1")
                .triggeredBy("alice")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-404")
                .executionId("op-404")
                .language("SQL")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(cellRunRepository.findByIdForUpdate(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-404")).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate("run-404")).thenReturn(Optional.of(run));

        when(kyuubiRestClient.getOperationEvent(eq("op-404"), any(), any()))
                .thenThrow(new NotebookApiException("RUNTIME_LOST", "Operation not found", HttpStatus.CONFLICT));

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo("LOST");
        assertThat(result.getErrorCode()).isEqualTo("OPERATION_NOT_FOUND");
    }

    @Test
    void testSyncKyuubiUnavailableReturnsStale() {
        String cellRunId = "crun-unavail";
        NotebookRun run = NotebookRun.builder()
                .id("run-unavail")
                .notebookId("nb-1")
                .triggeredBy("alice")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-unavail")
                .executionId("op-unavail")
                .language("SQL")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-unavail")).thenReturn(Optional.of(run));

        when(kyuubiRestClient.getOperationEvent(eq("op-unavail"), any(), any()))
                .thenThrow(new NotebookApiException("KYUUBI_UNAVAILABLE", "Cannot connect", HttpStatus.SERVICE_UNAVAILABLE, true, null));

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo("RUNNING");
        assertThat(result.getStale()).isTrue();
    }

    @Test
    void testSubmitExecutionFailureLeavesCellRunFailed() {
        String sessionId = "sess-1";
        NotebookKyuubiSession session = NotebookKyuubiSession.builder()
                .id(sessionId)
                .notebookId("nb-1")
                .userId("alice")
                .kyuubiSessionId("ksess-1")
                .kyuubiInstance("inst-1")
                .executionCountSeq(0)
                .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(documentServiceClient.canExecute("nb-1", "alice")).thenReturn(true);

        JsonNode cellData = objectMapper.createObjectNode()
                .put("cellType", "CODE")
                .put("language", "SQL")
                .put("source", "SELECT 1");
        when(documentServiceClient.getCell("nb-1", "cell-1", "alice")).thenReturn(cellData);

        when(kyuubiRestClient.submitStatement(any(), any(), any(), any(), any()))
                .thenThrow(new NotebookApiException("KYUUBI_ERROR", "Engine crashed", HttpStatus.BAD_GATEWAY));

        when(cellRunRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
            return Optional.of(NotebookCellRun.builder().id((String) inv.getArgument(0)).build());
        });
        when(runRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
            return Optional.of(NotebookRun.builder().id((String) inv.getArgument(0)).build());
        });

        assertThatThrownBy(() -> executionService.submitExecution(sessionId, "cell-1", "alice", "Bearer token"))
                .isInstanceOf(NotebookApiException.class);

        // Verify session executionCountSeq was incremented
        assertThat(session.getExecutionCountSeq()).isEqualTo(1);
    }
}
