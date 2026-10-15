package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookDocument;
import com.vtnexus.notebook.store.model.NotebookPermission;
import com.vtnexus.notebook.store.model.NotebookRevision;
import com.vtnexus.notebook.store.repository.NotebookPermissionRepository;
import com.vtnexus.notebook.store.repository.NotebookRepository;
import com.vtnexus.notebook.store.repository.NotebookRevisionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotebookStorageAndRevisionTest {

    private NotebookRepository notebookRepository;
    private NotebookPermissionRepository permissionRepository;
    private NotebookRevisionRepository revisionRepository;
    private EntityManager entityManager;
    private ObjectMapper objectMapper;
    private NotebookService notebookService;

    @BeforeEach
    void setUp() {
        notebookRepository = mock(NotebookRepository.class);
        permissionRepository = mock(NotebookPermissionRepository.class);
        revisionRepository = mock(NotebookRevisionRepository.class);
        entityManager = mock(EntityManager.class);
        objectMapper = new ObjectMapper();
        com.vtnexus.notebook.common.config.NotebookProperties properties = new com.vtnexus.notebook.common.config.NotebookProperties();
        properties.getMax().setCells(500);
        properties.getMax().setPageSize(200);
        properties.getRevision().setMaxPerNotebook(100);
        notebookService = new NotebookService(
                notebookRepository,
                permissionRepository,
                revisionRepository,
                objectMapper,
                entityManager,
                properties
        );
    }

    @Test
    void testSchemaVersionMismatchThrows409() {
        String invalidSchemaJson = "{\"schemaVersion\":2,\"defaultLanguage\":\"SQL\",\"cells\":[]}";
        assertThatThrownBy(() -> notebookService.parseDocument(invalidSchemaJson))
                .isInstanceOf(NotebookApiException.class)
                .satisfies(e -> {
                    NotebookApiException nae = (NotebookApiException) e;
                    assertThat(nae.getCode()).isEqualTo("UNSUPPORTED_FORMAT");
                    assertThat(nae.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void testDocumentSizeExceeds16MBThrows413() {
        // Document lớn hơn 16MB
        StringBuilder hugeText = new StringBuilder();
        for (int i = 0; i < 17 * 1024; i++) {
            hugeText.append("0123456789abcdef".repeat(64)); // 1KB per line
        }
        NotebookDocument doc = NotebookDocument.builder()
                .schemaVersion(1)
                .defaultLanguage("SQL")
                .cells(List.of(NotebookDocument.CellItem.builder().source(hugeText.toString()).build()))
                .build();

        assertThatThrownBy(() -> {
            String json = objectMapper.writeValueAsString(doc);
            notebookService.parseDocument(json);
            // test createNotebook with large cell source
            notebookService.createNotebook("test", null, null, null, null,
                    List.of(java.util.Map.of("source", hugeText.toString())), "owner");
        }).isInstanceOf(NotebookApiException.class)
          .satisfies(e -> {
              NotebookApiException nae = (NotebookApiException) e;
              assertThat(nae.getCode()).isEqualTo("PAYLOAD_TOO_LARGE");
              assertThat(nae.getStatus().value()).isEqualTo(413);
          });
    }

    @Test
    void testUpdateNotebookParseErrorDoesNotIncrementRevisionOrSave() {
        Notebook notebook = Notebook.builder()
                .id("nb-1")
                .name("Old Name")
                .owner("owner1")
                .documentJson("INVALID_CORRUPTED_JSON")
                .currentRevision(5L)
                .lockVersion(0L)
                .build();
        when(notebookRepository.findByIdAndDeletedAtIsNull("nb-1")).thenReturn(Optional.of(notebook));

        assertThatThrownBy(() -> notebookService.updateNotebook("nb-1", "New Name", null, null, null, null, "owner1"))
                .isInstanceOf(NotebookApiException.class)
                .satisfies(e -> {
                    NotebookApiException nae = (NotebookApiException) e;
                    assertThat(nae.getCode()).isIn("UNSUPPORTED_FORMAT", "INVALID_DOCUMENT");
                });

        assertThat(notebook.getCurrentRevision()).isEqualTo(5L);
        verify(notebookRepository, never()).save(any());
    }

    @Test
    void testSoftDeleteRevokesPermissionsAndSetsDeletedAt() {
        Notebook notebook = Notebook.builder()
                .id("nb-1")
                .name("Notebook 1")
                .owner("owner1")
                .lockVersion(1L)
                .build();
        when(notebookRepository.findById("nb-1")).thenReturn(Optional.of(notebook));

        notebookService.deleteNotebook("nb-1", 1L, "owner1", false);

        assertThat(notebook.getDeletedAt()).isNotNull();
        verify(notebookRepository).save(notebook);
        verify(permissionRepository).deleteByNotebookId("nb-1");
        verifyNoInteractions(entityManager);
    }

    @Test
    void testPurgeDeleteCascadesViaEntityManager() {
        Notebook notebook = Notebook.builder()
                .id("nb-1")
                .name("Notebook 1")
                .owner("owner1")
                .lockVersion(1L)
                .build();
        when(notebookRepository.findById("nb-1")).thenReturn(Optional.of(notebook));

        jakarta.persistence.Query queryMock = mock(jakarta.persistence.Query.class);
        when(queryMock.setParameter(anyString(), any())).thenReturn(queryMock);
        when(queryMock.executeUpdate()).thenReturn(1);
        when(entityManager.createNativeQuery(anyString())).thenReturn(queryMock);

        notebookService.deleteNotebook("nb-1", 1L, "owner1", true);

        // 7 queries: logs, cell_runs, runs, kyuubi_sessions, revisions, permissions, notebooks
        verify(entityManager, times(7)).createNativeQuery(anyString());
        verify(notebookRepository, never()).save(notebook);
    }

    @Test
    void testRevisionCleanupDeletesOnlyAutoRevisionsExceedingMax() {
        Notebook notebook = Notebook.builder()
                .id("nb-1")
                .name("Notebook 1")
                .owner("owner1")
                .documentJson("{\"schemaVersion\":1,\"cells\":[]}")
                .currentRevision(108L)
                .build();
        when(notebookRepository.findById("nb-1")).thenReturn(Optional.of(notebook));

        // Giả sử có 105 AUTO revision (> 100)
        when(revisionRepository.countAutoRevisions("nb-1")).thenReturn(105L);

        List<NotebookRevision> autoToPrune = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            autoToPrune.add(NotebookRevision.builder().id("rev-" + i).revisionType("AUTO").revisionNumber((long) i).build());
        }
        when(revisionRepository.findAutoRevisionsOrderByRevisionNumberAsc(eq("nb-1"), any(Pageable.class)))
                .thenReturn(autoToPrune);

        notebookService.snapshotRevision("nb-1", "Auto checkpoint", "owner1", "AUTO");

        // Đảm bảo 5 bản ghi AUTO cũ nhất bị xóa
        verify(revisionRepository, times(5)).delete(any(NotebookRevision.class));
    }
}
