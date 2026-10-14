package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class IpynbConversionAndRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotebookService notebookService;
    private IpynbConversionService conversionService;

    @BeforeEach
    void setUp() {
        notebookService = Mockito.mock(NotebookService.class);
        conversionService = new IpynbConversionService(objectMapper, notebookService, 16777216);
    }

    @Test
    void testRoundTripSqlLabJsonV1PreservesExtraFields() throws Exception {
        // Mẫu thật từ self_service.notebooks
        String rawJson = "{\"schemaVersion\":1,\"defaultLanguage\":\"SQL\",\"metadata\":{\"customMeta\":\"val123\"},\"parameters\":[{\"key\":\"p1\"}]," +
                "\"cells\":[{\"id\":\"cell-315387a0\",\"type\":\"CODE\",\"language\":\"SQL\",\"source\":\"SELECT 1\"," +
                "\"metadata\":{\"kyuubiCellId\":\"b523460e-xyz\",\"customCellMeta\":\"cellVal\"}}]}";

        NotebookDocument doc = objectMapper.readValue(rawJson, NotebookDocument.class);
        assertThat(doc.getSchemaVersion()).isEqualTo(1);
        assertThat(doc.getDefaultLanguage()).isEqualTo("SQL");
        assertThat(doc.getParameters()).hasSize(1);
        assertThat(doc.getMetadata()).containsEntry("customMeta", "val123");

        assertThat(doc.getCells()).hasSize(1);
        NotebookDocument.CellItem cell = doc.getCells().get(0);
        assertThat(cell.getId()).isEqualTo("cell-315387a0");
        assertThat(cell.getSource()).isEqualTo("SELECT 1");
        assertThat(cell.getMetadata()).containsEntry("kyuubiCellId", "b523460e-xyz");
        assertThat(cell.getMetadata()).containsEntry("customCellMeta", "cellVal");

        // Sửa 1 cell
        cell.setSource("SELECT 2");

        // Serialize lại
        String updatedJson = objectMapper.writeValueAsString(doc);
        NotebookDocument updatedDoc = objectMapper.readValue(updatedJson, NotebookDocument.class);

        assertThat(updatedDoc.getCells().get(0).getSource()).isEqualTo("SELECT 2");
        assertThat(updatedDoc.getParameters()).hasSize(1);
        assertThat(updatedDoc.getMetadata()).containsEntry("customMeta", "val123");
        assertThat(updatedDoc.getCells().get(0).getMetadata()).containsEntry("kyuubiCellId", "b523460e-xyz");
        assertThat(updatedDoc.getCells().get(0).getMetadata()).containsEntry("customCellMeta", "cellVal");
    }

    @Test
    void testIpynbExportAndImportEquivalence() throws Exception {
        Notebook notebook = new Notebook();
        notebook.setId("nb-test-1");
        notebook.setName("Test Notebook");
        notebook.setDefaultLanguage("SQL");
        notebook.setOwner("testuser");

        NotebookDocument doc = NotebookDocument.builder()
                .schemaVersion(1)
                .defaultLanguage("SQL")
                .metadata(new LinkedHashMap<>())
                .parameters(new ArrayList<>())
                .cells(new ArrayList<>())
                .build();
        doc.getMetadata().put("customField", "preserveMe");

        NotebookDocument.CellItem cell1 = NotebookDocument.CellItem.builder()
                .id("cell-1")
                .type("CODE")
                .language("SQL")
                .source("SELECT 100;")
                .metadata(new LinkedHashMap<>())
                .build();
        cell1.getMetadata().put("executionCount", 1);
        doc.getCells().add(cell1);

        NotebookDocument.CellItem cell2 = NotebookDocument.CellItem.builder()
                .id("cell-2")
                .type("MARKDOWN")
                .language("MARKDOWN")
                .source("# Title markdown")
                .metadata(new LinkedHashMap<>())
                .build();
        doc.getCells().add(cell2);

        String docJson = objectMapper.writeValueAsString(doc);
        notebook.setDocumentJson(docJson);

        when(notebookService.getNotebook("nb-test-1", "testuser")).thenReturn(notebook);
        when(notebookService.parseDocument(docJson)).thenReturn(doc);

        // Export ra ipynb
        String ipynbJson = conversionService.exportNotebook("nb-test-1", false, "testuser");
        assertThat(ipynbJson).contains("\"nbformat\" : 4");
        assertThat(ipynbJson).contains("SELECT 100;");
        assertThat(ipynbJson).contains("# Title markdown");

        // Import ngược lại từ ipynb
        when(notebookService.createNotebook(anyString(), any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    Notebook created = new Notebook();
                    created.setName(inv.getArgument(0));
                    return created;
                });

        Notebook imported = conversionService.importNotebook("IPYNB", "Imported Note", null, ipynbJson, "testuser");
        assertThat(imported.getName()).isEqualTo("Imported Note");
    }
}
