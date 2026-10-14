package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.*;
import com.vtnexus.notebook.store.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class NotebookService {

    private final NotebookRepository notebookRepository;
    private final NotebookPermissionRepository permissionRepository;
    private final NotebookRevisionRepository revisionRepository;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    private final int maxCells;
    private final int maxPageSize;
    private final int maxRevisions;

    public NotebookService(
            NotebookRepository notebookRepository,
            NotebookPermissionRepository permissionRepository,
            NotebookRevisionRepository revisionRepository,
            ObjectMapper objectMapper,
            jakarta.persistence.EntityManager entityManager,
            @Value("${notebook.max.cells:500}") int maxCells,
            @Value("${notebook.max.page-size:200}") int maxPageSize,
            @Value("${notebook.revision.max-per-notebook:100}") int maxRevisions) {
        this.notebookRepository = notebookRepository;
        this.permissionRepository = permissionRepository;
        this.revisionRepository = revisionRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.maxCells = maxCells;
        this.maxPageSize = maxPageSize;
        this.maxRevisions = maxRevisions;
    }

    public NotebookRepository getNotebookRepository() {
        return notebookRepository;
    }


    public String checkAccess(Notebook notebook, String user, String requiredRole) {
        if (notebook.getOwner().equals(user)) {
            return "OWNER";
        }
        Optional<NotebookPermission> perm = permissionRepository.findByNotebookIdAndPrincipalId(notebook.getId(), user);
        if (perm.isEmpty()) {
            throw new NotebookApiException("NOTEBOOK_NOT_FOUND", "Notebook not found", HttpStatus.NOT_FOUND);
        }

        String userRole = perm.get().getRole();
        int userLevel = getRoleLevel(userRole);
        int requiredLevel = getRoleLevel(requiredRole);

        if (userLevel < requiredLevel) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires " + requiredRole + " role or higher", HttpStatus.FORBIDDEN);
        }
        return userRole;
    }

    public int getRoleLevel(String role) {
        return switch (role.toUpperCase()) {
            case "OWNER" -> 4;
            case "EDITOR" -> 3;
            case "RUNNER" -> 2;
            case "VIEWER" -> 1;
            default -> 0;
        };
    }

    public Notebook createNotebook(String name, String folderId, String description,
                                   String defaultCatalog, String defaultSchema,
                                   List<Map<String, Object>> initialCells, String owner) {
        if (name == null || name.isBlank()) {
            throw new NotebookApiException("INVALID_REQUEST", "Notebook name is required", HttpStatus.BAD_REQUEST);
        }

        if (initialCells != null && initialCells.size() > maxCells) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Number of cells exceeds maximum limit of " + maxCells, HttpStatus.PAYLOAD_TOO_LARGE);
        }

        String notebookId = UUID.randomUUID().toString();

        NotebookDocument doc = NotebookDocument.builder()
                .schemaVersion(1)
                .defaultLanguage("SQL")
                .metadata(new LinkedHashMap<>())
                .parameters(new ArrayList<>())
                .cells(new ArrayList<>())
                .build();

        doc.getMetadata().put("name", name.trim());
        if (description != null) doc.getMetadata().put("description", description);
        if (defaultCatalog != null) doc.getMetadata().put("defaultCatalog", defaultCatalog);
        if (defaultSchema != null) doc.getMetadata().put("defaultSchema", defaultSchema);

        if (initialCells != null && !initialCells.isEmpty()) {
            for (Map<String, Object> cellData : initialCells) {
                String cellType = (String) cellData.getOrDefault("cellType", "CODE");
                String language = (String) cellData.getOrDefault("language", "SQL");
                String source = (String) cellData.getOrDefault("source", "");

                if (source != null && source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576) {
                    throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Cell source size exceeds limit of 1048576 bytes", HttpStatus.PAYLOAD_TOO_LARGE);
                }

                NotebookDocument.CellItem item = NotebookDocument.CellItem.builder()
                        .id("cell-" + UUID.randomUUID())
                        .type(cellType != null ? cellType.toUpperCase() : "CODE")
                        .language(language != null ? language.toUpperCase() : "SQL")
                        .source(source != null ? source : "")
                        .metadata(new LinkedHashMap<>())
                        .build();
                doc.getCells().add(item);
            }
        } else {
            NotebookDocument.CellItem defaultCell = NotebookDocument.CellItem.builder()
                    .id("cell-" + UUID.randomUUID())
                    .type("CODE")
                    .language("SQL")
                    .source("SELECT 1")
                    .metadata(new LinkedHashMap<>())
                    .build();
            doc.getCells().add(defaultCell);
        }

        String documentJson;
        try {
            documentJson = objectMapper.writeValueAsString(doc);
            validateDocumentSize(documentJson);
        } catch (NotebookApiException nae) {
            throw nae;
        } catch (Exception e) {
            throw new NotebookApiException("INTERNAL_ERROR", "Failed to serialize notebook document: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Notebook notebook = Notebook.builder()
                .id(notebookId)
                .name(name.trim())
                .folderId(folderId)
                .description(description)
                .owner(owner)
                .notebookFormat("SQLLAB_JSON_V1")
                .defaultLanguage("SQL")
                .documentJson(documentJson)
                .currentRevision(1L)
                .lockVersion(0L)
                .build();

        notebook = notebookRepository.save(notebook);
        snapshotRevision(notebookId, "Initial creation", owner, "MANUAL");
        return notebook;
    }

    public Notebook getNotebook(String notebookId, String user) {
        Notebook notebook = notebookRepository.findByIdAndDeletedAtIsNull(notebookId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_NOT_FOUND", "Notebook not found: " + notebookId, HttpStatus.NOT_FOUND));
        checkAccess(notebook, user, "VIEWER");
        return notebook;
    }

    public List<Notebook> listNotebooks(String folderId, int limit, String user) {
        PageRequest page = PageRequest.of(0, Math.min(limit > 0 ? limit : 50, maxPageSize));
        if (folderId != null) {
            return notebookRepository.findAccessibleNotebooksByFolder(user, folderId, page);
        }
        return notebookRepository.findAccessibleNotebooks(user, page);
    }

    public Notebook updateNotebook(String notebookId, String name, String description,
                                   String defaultCatalog, String defaultSchema,
                                   Long version, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "EDITOR");

        if (version != null && !notebook.getLockVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        if (name != null && !name.isBlank()) notebook.setName(name.trim());
        if (description != null) notebook.setDescription(description);

        // C23: Cập nhật metadata trong documentJson. Lỗi parse/serialize -> 400 INVALID_DOCUMENT, không tăng revision, không lưu
        NotebookDocument doc;
        try {
            doc = parseDocument(notebook.getDocumentJson());
            if (name != null && !name.isBlank()) doc.getMetadata().put("name", name.trim());
            if (description != null) doc.getMetadata().put("description", description);
            if (defaultCatalog != null) doc.getMetadata().put("defaultCatalog", defaultCatalog);
            if (defaultSchema != null) doc.getMetadata().put("defaultSchema", defaultSchema);
            String newDocJson = objectMapper.writeValueAsString(doc);
            validateDocumentSize(newDocJson);
            notebook.setDocumentJson(newDocJson);
        } catch (NotebookApiException nae) {
            throw nae;
        } catch (Exception e) {
            throw new NotebookApiException("INVALID_DOCUMENT", "Failed to update notebook document: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        notebook.setCurrentRevision(notebook.getCurrentRevision() + 1);
        Notebook saved = notebookRepository.save(notebook);
        snapshotRevision(notebookId, "Update notebook metadata", user, "MANUAL");
        return saved;
    }

    public void deleteNotebook(String notebookId, Long version, String user, boolean purge) {
        Notebook notebook = notebookRepository.findById(notebookId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_NOT_FOUND", "Notebook not found: " + notebookId, HttpStatus.NOT_FOUND));

        checkAccess(notebook, user, "OWNER");

        if (version != null && !notebook.getLockVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        if (purge) {
            // C21 Purge: xóa thật trong một transaction: logs -> cell-runs -> runs -> kyuubi-sessions -> revisions -> permissions -> notebook
            entityManager.createNativeQuery(
                    "DELETE l FROM notebook_execution_logs l " +
                    "JOIN notebook_cell_runs cr ON l.cell_run_id = cr.id " +
                    "JOIN notebook_runs r ON cr.notebook_run_id = r.id " +
                    "WHERE r.notebook_id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE cr FROM notebook_cell_runs cr " +
                    "JOIN notebook_runs r ON cr.notebook_run_id = r.id " +
                    "WHERE r.notebook_id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE FROM notebook_runs WHERE notebook_id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE FROM notebook_kyuubi_sessions WHERE notebook_id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE FROM notebook_revisions WHERE notebook_id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE FROM notebook_permissions WHERE notebook_id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE FROM notebooks WHERE id = :nbId")
                    .setParameter("nbId", notebookId)
                    .executeUpdate();
        } else {
            // Xóa mềm: đánh dấu deleted_at và thu hồi permissions chia sẻ để biến mất khỏi danh sách người khác
            notebook.setDeletedAt(Instant.now());
            notebookRepository.save(notebook);
            permissionRepository.deleteByNotebookId(notebookId);
        }
    }

    public void deleteNotebook(String notebookId, Long version, String user) {
        deleteNotebook(notebookId, version, user, false);
    }

    public void snapshotRevision(String notebookId, String reason, String user, String revisionType) {
        try {
            Notebook notebook = notebookRepository.findById(notebookId).orElse(null);
            if (notebook == null) return;

            Long maxRev = revisionRepository.findMaxRevisionNumberByNotebookId(notebookId);
            long nextRev = (maxRev == null ? 1L : maxRev + 1L);

            NotebookRevision revision = NotebookRevision.builder()
                    .id(UUID.randomUUID().toString())
                    .notebookId(notebookId)
                    .revisionNumber(nextRev)
                    .revisionType(revisionType != null ? revisionType : "AUTO")
                    .documentJson(notebook.getDocumentJson())
                    .createdBy(user != null ? user : "system")
                    .build();
            revisionRepository.save(revision);

            // C24: Đếm và xóa CHỈ revision_type='AUTO', giữ MANUAL/IMPORT
            long autoCount = revisionRepository.countAutoRevisions(notebookId);
            if (autoCount > maxRevisions) {
                int toDelete = (int) (autoCount - maxRevisions);
                List<NotebookRevision> oldAutoRevs = revisionRepository.findAutoRevisionsOrderByRevisionNumberAsc(
                        notebookId, PageRequest.of(0, toDelete));
                for (NotebookRevision r : oldAutoRevs) {
                    revisionRepository.delete(r);
                }
            }

        } catch (NotebookApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NotebookApiException("INTERNAL_ERROR", "Failed to create notebook revision snapshot: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    public List<NotebookPermission> getPermissions(String notebookId, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "OWNER");
        return permissionRepository.findByNotebookId(notebookId);
    }

    public List<NotebookPermission> updatePermissions(String notebookId, List<Map<String, String>> grants, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "OWNER");

        if (grants != null) {
            for (Map<String, String> grant : grants) {
                String pId = grant.get("principalId");
                String role = grant.get("role");
                if (pId == null || pId.isBlank()) {
                    throw new NotebookApiException("INVALID_REQUEST", "principalId cannot be empty", HttpStatus.BAD_REQUEST);
                }
                if (role == null || (!"EDITOR".equalsIgnoreCase(role) && !"RUNNER".equalsIgnoreCase(role) && !"VIEWER".equalsIgnoreCase(role))) {
                    throw new NotebookApiException("INVALID_REQUEST", "role must be EDITOR, RUNNER, or VIEWER", HttpStatus.BAD_REQUEST);
                }
            }
        }

        permissionRepository.deleteByNotebookId(notebookId);
        List<NotebookPermission> savedList = new ArrayList<>();

        if (grants != null) {
            for (Map<String, String> grant : grants) {
                String pType = grant.getOrDefault("principalType", "USER");
                String pId = grant.get("principalId");
                String role = grant.get("role");

                if (!"USER".equalsIgnoreCase(pType)) {
                    throw new NotebookApiException("INVALID_REQUEST", "GROUP principals are not supported", HttpStatus.BAD_REQUEST);
                }

                NotebookPermission p = NotebookPermission.builder()
                        .notebookId(notebookId)
                        .principalType("USER")
                        .principalId(pId.trim())
                        .role(role.toUpperCase())
                        .build();
                savedList.add(permissionRepository.save(p));
            }
        }
        return savedList;
    }

    private void validateDocumentSize(String documentJson) {
        if (documentJson != null && documentJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Notebook document size exceeds 16 MB limit", HttpStatus.PAYLOAD_TOO_LARGE);
        }
    }

    public NotebookDocument parseDocument(String documentJson) {
        if (documentJson == null || documentJson.isBlank()) {
            return NotebookDocument.builder()
                    .schemaVersion(1)
                    .defaultLanguage("SQL")
                    .cells(new ArrayList<>())
                    .build();
        }
        NotebookDocument doc;
        try {
            doc = objectMapper.readValue(documentJson, NotebookDocument.class);
        } catch (Exception e) {
            throw new NotebookApiException("UNSUPPORTED_FORMAT", "Failed to parse document JSON: " + e.getMessage(), HttpStatus.CONFLICT);
        }
        if (doc.getSchemaVersion() != 1) {
            throw new NotebookApiException("UNSUPPORTED_FORMAT", "Unsupported schemaVersion: " + doc.getSchemaVersion() + ". Expected 1.", HttpStatus.CONFLICT);
        }
        return doc;
    }
}
