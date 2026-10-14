package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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

        executionService = new ExecutionService(
                sessionRepository,
                runRepository,
                cellRunRepository,
                logRepository,
                kyuubiRestClient,
                documentServiceClient,
                objectMapper,
                1000
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

        // Kyuubi trả về event FINISHED
        JsonNode eventNode = objectMapper.readTree("{\"state\": \"FINISHED\"}");
        when(kyuubiRestClient.getOperationEvent(eq("op-sql-1"), any(), any())).thenReturn(eventNode);

        // Kyuubi trả về rowset
        JsonNode rowsetNode = objectMapper.readTree("[[\"Alice\", 30], [\"Bob\", 25]]");
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

        JsonNode eventNode = objectMapper.readTree("{\"state\": \"FINISHED\"}");
        when(kyuubiRestClient.getOperationEvent(eq("op-py-1"), any(), any())).thenReturn(eventNode);

        // Kyuubi trả về 1 cột text dạng stdout
        JsonNode rowsetNode = objectMapper.readTree("[[\"Hello Python 1\"], [\"Hello Python 2\"]]");
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

        // Kyuubi trả về state ERROR kèm exception
        JsonNode eventNode = objectMapper.readTree("{\"state\": \"ERROR\", \"exception\": {\"message\": \"ZeroDivisionError: division by zero\"}}");
        when(kyuubiRestClient.getOperationEvent(eq("op-err-1"), any(), any())).thenReturn(eventNode);
        when(kyuubiRestClient.getOperationLog(eq("op-err-1"), any(), anyInt(), any())).thenReturn(Collections.emptyList());

        NotebookExecutionDto result = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer dummy");

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(cellRun.getOutputType()).isEqualTo("ERROR");

        JsonNode outJson = objectMapper.readTree(cellRun.getOutputJson());
        assertThat(outJson.get("output_type").asText()).isEqualTo("error");
        assertThat(outJson.get("ename").asText()).isEqualTo("ExecutionError");
        assertThat(outJson.get("evalue").asText()).contains("ZeroDivisionError: division by zero");
        assertThat(outJson.get("traceback").get(0).asText()).contains("ZeroDivisionError");
    }
}
