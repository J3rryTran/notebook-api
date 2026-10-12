package com.vtnexus.notebook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.exception.NotebookApiException;
import com.vtnexus.notebook.model.*;
import com.vtnexus.notebook.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class NotebookService {

    private final NotebookRepository notebookRepository;
    private final NotebookCellRepository cellRepository;
    private final NotebookPermissionRepository permissionRepository;
    private final NotebookRevisionRepository revisionRepository;
    private final NotebookScheduleRepository scheduleRepository;
    private final ObjectMapper objectMapper;

    public NotebookService(NotebookRepository notebookRepository,
                           NotebookCellRepository cellRepository,
                           NotebookPermissionRepository permissionRepository,
                           NotebookRevisionRepository revisionRepository,
                           NotebookScheduleRepository scheduleRepository,
                           ObjectMapper objectMapper) {
        this.notebookRepository = notebookRepository;
        this.cellRepository = cellRepository;
        this.permissionRepository = permissionRepository;
        this.revisionRepository = revisionRepository;
        this.scheduleRepository = scheduleRepository;
        this.objectMapper = objectMapper;
    }

    public String checkAccess(Notebook notebook, String user, String requiredRole) {
        if (notebook.getOwner().equals(user)) {
            return "OWNER";
        }
        Optional<NotebookPermission> perm = permissionRepository.findByNotebookIdAndPrincipalId(notebook.getId(), user);
        if (perm.isEmpty()) {
            throw new NotebookApiException("NOTEBOOK_NOT_FOUND", "Notebook not found", HttpStatus.NOT_FOUND);
        }
        String role = perm.get().getRole();
        if ("OWNER".equals(requiredRole) && !"OWNER".equals(role)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires OWNER role", HttpStatus.FORBIDDEN);
        }
        if ("EDITOR".equals(requiredRole) && "VIEWER".equals(role)) {
            throw new NotebookApiException("ACCESS_DENIED", "Requires EDITOR role", HttpStatus.FORBIDDEN);
        }
        return role;
    }

    public Notebook createNotebook(String name, String folderId, String description,
                                   String defaultCatalog, String defaultSchema,
                                   List<Map<String, Object>> initialCells, String owner) {
        if (name == null || name.isBlank()) {
            throw new NotebookApiException("INVALID_REQUEST", "Notebook name is required", HttpStatus.BAD_REQUEST);
        }

        String notebookId = UUID.randomUUID().toString();
        Notebook notebook = Notebook.builder()
                .id(notebookId)
                .name(name.trim())
                .folderId(folderId)
                .description(description)
                .defaultCatalog(defaultCatalog)
                .defaultSchema(defaultSchema)
                .owner(owner)
                .version(1L)
                .build();

        notebook = notebookRepository.save(notebook);

        if (initialCells != null && !initialCells.isEmpty()) {
            int pos = 0;
            for (Map<String, Object> cellData : initialCells) {
                String cellType = (String) cellData.getOrDefault("cellType", "CODE");
                String language = (String) cellData.getOrDefault("language", "SQL");
                String source = (String) cellData.getOrDefault("source", "");

                NotebookCell cell = NotebookCell.builder()
                        .id(UUID.randomUUID().toString())
                        .notebook(notebook)
                        .cellType(cellType)
                        .language(language)
                        .source(source)
                        .position(pos++)
                        .build();
                cellRepository.save(cell);
            }
        } else {
            // Default first cell
            NotebookCell defaultCell = NotebookCell.builder()
                    .id(UUID.randomUUID().toString())
                    .notebook(notebook)
                    .cellType("CODE")
                    .language("SQL")
                    .source("SELECT 1")
                    .position(0)
                    .build();
            cellRepository.save(defaultCell);
        }

        snapshotRevision(notebookId, "Initial creation", owner, false);
        return notebook;
    }

    public Notebook getNotebook(String notebookId, String user) {
        Notebook notebook = notebookRepository.findById(notebookId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_NOT_FOUND", "Notebook not found: " + notebookId, HttpStatus.NOT_FOUND));
        checkAccess(notebook, user, "VIEWER");
        return notebook;
    }

    public List<Notebook> listNotebooks(String owner, String folderId, int limit, String user) {
        String effectiveOwner = (owner != null && !owner.isBlank()) ? owner : user;
        PageRequest page = PageRequest.of(0, Math.min(limit > 0 ? limit : 50, 200));
        if (folderId != null) {
            return notebookRepository.findByOwnerAndFolderId(effectiveOwner, folderId, page);
        }
        return notebookRepository.findByOwner(effectiveOwner, page);
    }

    public Notebook updateNotebook(String notebookId, String name, String description,
                                   String defaultCatalog, String defaultSchema,
                                   Long version, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "EDITOR");

        if (version != null && !notebook.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        if (name != null && !name.isBlank()) notebook.setName(name.trim());
        if (description != null) notebook.setDescription(description);
        if (defaultCatalog != null) notebook.setDefaultCatalog(defaultCatalog);
        if (defaultSchema != null) notebook.setDefaultSchema(defaultSchema);

        notebook.setVersion(notebook.getVersion() + 1);
        Notebook saved = notebookRepository.save(notebook);
        snapshotRevision(notebookId, "Update notebook metadata", user, false);
        return saved;
    }

    public void deleteNotebook(String notebookId, Long version, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "OWNER");

        if (version != null && !notebook.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        permissionRepository.deleteByNotebookId(notebookId);
        revisionRepository.deleteByNotebookId(notebookId);
        scheduleRepository.deleteById(notebookId);
        notebookRepository.delete(notebook);
    }

    public Notebook cloneNotebook(String notebookId, String newName, String targetFolderId, String user) {
        Notebook original = getNotebook(notebookId, user);
        checkAccess(original, user, "VIEWER");

        List<NotebookCell> cells = cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);
        List<Map<String, Object>> cellMaps = new ArrayList<>();
        for (NotebookCell c : cells) {
            Map<String, Object> map = new HashMap<>();
            map.put("cellType", c.getCellType());
            map.put("language", c.getLanguage());
            map.put("source", c.getSource());
            cellMaps.add(map);
        }

        String name = (newName != null && !newName.isBlank()) ? newName : (original.getName() + " (Copy)");
        return createNotebook(name, targetFolderId, original.getDescription(),
                original.getDefaultCatalog(), original.getDefaultSchema(), cellMaps, user);
    }

    public Notebook moveNotebook(String notebookId, String targetFolderId, String newName, Long version, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "EDITOR");

        if (version != null && !notebook.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        if (targetFolderId != null) {
            notebook.setFolderId(targetFolderId.isBlank() ? null : targetFolderId);
        }
        if (newName != null && !newName.isBlank()) {
            notebook.setName(newName.trim());
        }

        notebook.setVersion(notebook.getVersion() + 1);
        return notebookRepository.save(notebook);
    }

    public void snapshotRevision(String notebookId, String reason, String user, boolean isProtected) {
        Long maxRev = revisionRepository.findMaxRevisionNumberByNotebookId(notebookId);
        long nextRev = (maxRev == null ? 1L : maxRev + 1L);

        List<NotebookCell> cells = cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);
        Map<String, Object> doc = new HashMap<>();
        doc.put("revision", nextRev);
        doc.put("cells", cells);

        try {
            String docJson = objectMapper.writeValueAsString(doc);
            NotebookRevision revision = NotebookRevision.builder()
                    .notebookId(notebookId)
                    .revisionNumber(nextRev)
                    .reason(reason)
                    .isProtected(isProtected)
                    .documentJson(docJson)
                    .createdBy(user)
                    .build();
            revisionRepository.save(revision);
        } catch (JsonProcessingException e) {
            // ignore revision write error
        }
    }
}
