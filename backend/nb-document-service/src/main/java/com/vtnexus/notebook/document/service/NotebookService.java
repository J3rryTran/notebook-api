package com.vtnexus.notebook.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.document.model.*;
import com.vtnexus.notebook.document.repository.*;
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
    private final NotebookCellRepository cellRepository;
    private final NotebookPermissionRepository permissionRepository;
    private final NotebookRevisionRepository revisionRepository;
    private final ObjectMapper objectMapper;

    private final int maxCells;
    private final int maxPageSize;
    private final int maxRevisions;

    public NotebookService(
            NotebookRepository notebookRepository,
            NotebookCellRepository cellRepository,
            NotebookPermissionRepository permissionRepository,
            NotebookRevisionRepository revisionRepository,
            ObjectMapper objectMapper,
            @Value("${notebook.max.cells:500}") int maxCells,
            @Value("${notebook.max.page-size:200}") int maxPageSize,
            @Value("${notebook.revision.max-per-notebook:100}") int maxRevisions) {
        this.notebookRepository = notebookRepository;
        this.cellRepository = cellRepository;
        this.permissionRepository = permissionRepository;
        this.revisionRepository = revisionRepository;
        this.objectMapper = objectMapper;
        this.maxCells = maxCells;
        this.maxPageSize = maxPageSize;
        this.maxRevisions = maxRevisions;
    }

    /**
     * D-1: Kiểm tra quyền theo thứ bậc chuẩn: VIEWER (1) < RUNNER (2) < EDITOR (3) < OWNER (4)
     */
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
        Notebook notebook = notebookRepository.findByIdAndDeletedAtIsNull(notebookId)
                .orElseThrow(() -> new NotebookApiException("NOTEBOOK_NOT_FOUND", "Notebook not found: " + notebookId, HttpStatus.NOT_FOUND));
        checkAccess(notebook, user, "VIEWER");
        return notebook;
    }

    // D-2: Bỏ tham số owner, trả về notebook user sở hữu HOẶC được cấp quyền
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

    // D-4: Xóa mềm - đánh dấu deletedAt để giải phóng path/tên
    public void deleteNotebook(String notebookId, Long version, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "OWNER");

        if (version != null && !notebook.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        notebook.setDeletedAt(Instant.now());
        notebookRepository.save(notebook);
    }

    // D-9: Snapshot revision không nuốt lỗi, ném lỗi rõ ràng nếu thất bại
    public void snapshotRevision(String notebookId, String reason, String user, boolean isProtected) {
        try {
            Long maxRev = revisionRepository.findMaxRevisionNumberByNotebookId(notebookId);
            long nextRev = (maxRev == null ? 1L : maxRev + 1L);

            List<NotebookCell> cells = cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);
            List<Map<String, Object>> cellDtos = new ArrayList<>();
            for (NotebookCell c : cells) {
                cellDtos.add(Map.of(
                        "id", c.getId(),
                        "cellType", c.getCellType(),
                        "language", c.getLanguage(),
                        "source", c.getSource(),
                        "position", c.getPosition()
                ));
            }

            Map<String, Object> doc = Map.of("revision", nextRev, "cells", cellDtos);
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

        } catch (Exception e) {
            throw new NotebookApiException("INTERNAL_ERROR", "Failed to create notebook revision snapshot: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // D-3: Permissions API (Chỉ OWNER được thao tác)
    public List<NotebookPermission> getPermissions(String notebookId, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "OWNER");
        return permissionRepository.findByNotebookId(notebookId);
    }

    public List<NotebookPermission> updatePermissions(String notebookId, List<Map<String, String>> grants, String user) {
        Notebook notebook = getNotebook(notebookId, user);
        checkAccess(notebook, user, "OWNER");

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
                if ("OWNER".equalsIgnoreCase(role)) {
                    throw new NotebookApiException("INVALID_REQUEST", "OWNER role cannot be granted", HttpStatus.BAD_REQUEST);
                }

                NotebookPermission p = NotebookPermission.builder()
                        .notebookId(notebookId)
                        .principalType("USER")
                        .principalId(pId)
                        .role(role.toUpperCase())
                        .build();
                savedList.add(permissionRepository.save(p));
            }
        }
        return savedList;
    }
}
