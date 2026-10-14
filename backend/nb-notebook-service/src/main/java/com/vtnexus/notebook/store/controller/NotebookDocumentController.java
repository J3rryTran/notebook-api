package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookDocument;
import com.vtnexus.notebook.store.model.NotebookPermission;
import com.vtnexus.notebook.store.service.IpynbConversionService;
import com.vtnexus.notebook.store.service.NotebookCellService;
import com.vtnexus.notebook.store.service.NotebookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class NotebookDocumentController {

    private final NotebookService notebookService;
    private final NotebookCellService cellService;
    private final IpynbConversionService conversionService;

    public NotebookDocumentController(
            NotebookService notebookService,
            NotebookCellService cellService,
            IpynbConversionService conversionService) {
        this.notebookService = notebookService;
        this.cellService = cellService;
        this.conversionService = conversionService;
    }

    @PostMapping("/notebooks")
    public ResponseEntity<Notebook> createNotebook(@RequestBody Map<String, Object> body,
                                                   @AuthenticationPrincipal Object principal) {
        String name = (String) body.get("name");
        String folderId = (String) body.get("folderId");
        String description = (String) body.get("description");
        String defaultCatalog = (String) body.get("defaultCatalog");
        String defaultSchema = (String) body.get("defaultSchema");
        List<Map<String, Object>> cells = (List<Map<String, Object>>) body.get("cells");

        Notebook nb = notebookService.createNotebook(name, folderId, description, defaultCatalog, defaultSchema, cells, principal.toString());
        return ResponseEntity.ok(nb);
    }

    // D-2: Bỏ tham số owner
    @GetMapping("/notebooks")
    public ResponseEntity<Map<String, Object>> listNotebooks(
            @RequestParam(value = "folderId", required = false) String folderId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @AuthenticationPrincipal Object principal) {
        List<Notebook> list = notebookService.listNotebooks(folderId, limit, principal.toString());
        Map<String, Object> res = new HashMap<>();
        res.put("items", list);
        res.put("hasMore", false);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/notebooks/{notebookId}")
    public ResponseEntity<Map<String, Object>> getNotebook(@PathVariable("notebookId") String notebookId,
                                                           @RequestParam(value = "includeCells", defaultValue = "true") boolean includeCells,
                                                           @AuthenticationPrincipal Object principal) {
        Notebook nb = notebookService.getNotebook(notebookId, principal.toString());
        Map<String, Object> res = new HashMap<>();
        NotebookDocument doc = notebookService.parseDocument(nb.getDocumentJson());
        res.put("id", nb.getId());
        res.put("name", nb.getName());
        res.put("folderId", nb.getFolderId());
        res.put("description", nb.getDescription());
        res.put("defaultCatalog", doc.getMetadata().get("defaultCatalog"));
        res.put("defaultSchema", doc.getMetadata().get("defaultSchema"));
        res.put("owner", nb.getOwner());
        res.put("version", nb.getLockVersion());
        res.put("role", notebookService.checkAccess(nb, principal.toString(), "VIEWER"));

        if (includeCells) {
            res.put("cells", cellService.getCells(notebookId, principal.toString()));
        }
        return ResponseEntity.ok(res);
    }

    @PatchMapping("/notebooks/{notebookId}")
    public ResponseEntity<Notebook> updateNotebook(@PathVariable("notebookId") String notebookId,
                                                   @RequestBody Map<String, Object> body,
                                                   @AuthenticationPrincipal Object principal) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String defaultCatalog = (String) body.get("defaultCatalog");
        String defaultSchema = (String) body.get("defaultSchema");
        Long version = body.get("version") != null ? Long.valueOf(body.get("version").toString()) : null;

        return ResponseEntity.ok(notebookService.updateNotebook(notebookId, name, description, defaultCatalog, defaultSchema, version, principal.toString()));
    }

    @DeleteMapping("/notebooks/{notebookId}")
    public ResponseEntity<Void> deleteNotebook(@PathVariable("notebookId") String notebookId,
                                               @RequestParam(value = "version", required = false) Long version,
                                               @AuthenticationPrincipal Object principal) {
        notebookService.deleteNotebook(notebookId, version, principal.toString());
        return ResponseEntity.noContent().build();
    }

    // D-5: URL chuẩn spec /api/v1/notebooks:import
    @PostMapping(path = "/notebooks:import")
    public ResponseEntity<Notebook> importNotebook(@RequestBody Map<String, String> body,
                                                   @AuthenticationPrincipal Object principal) {
        String format = body.getOrDefault("format", "IPYNB");
        String name = body.get("name");
        String folderId = body.get("folderId");
        String content = body.get("content");
        return ResponseEntity.ok(conversionService.importNotebook(format, name, folderId, content, principal.toString()));
    }

    @GetMapping("/notebooks/{notebookId}:export")
    public ResponseEntity<String> exportNotebook(@PathVariable("notebookId") String notebookId,
                                                 @RequestParam(value = "includeOutputs", defaultValue = "false") boolean includeOutputs,
                                                 @AuthenticationPrincipal Object principal) {
        String json = conversionService.exportNotebook(notebookId, includeOutputs, principal.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + notebookId + ".ipynb\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    // D-3: Permissions API
    @GetMapping("/notebooks/{notebookId}/permissions")
    public ResponseEntity<Map<String, Object>> getPermissions(@PathVariable("notebookId") String notebookId,
                                                              @AuthenticationPrincipal Object principal) {
        List<NotebookPermission> perms = notebookService.getPermissions(notebookId, principal.toString());
        return ResponseEntity.ok(Map.of("permissions", perms));
    }

    @PutMapping("/notebooks/{notebookId}/permissions")
    public ResponseEntity<Map<String, Object>> updatePermissions(@PathVariable("notebookId") String notebookId,
                                                                 @RequestBody Map<String, Object> body,
                                                                 @AuthenticationPrincipal Object principal) {
        List<Map<String, String>> perms = (List<Map<String, String>>) body.get("permissions");
        List<NotebookPermission> saved = notebookService.updatePermissions(notebookId, perms, principal.toString());
        return ResponseEntity.ok(Map.of("permissions", saved));
    }
}
