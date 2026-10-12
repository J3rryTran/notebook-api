package com.vtnexus.notebook.controller;

import com.vtnexus.notebook.model.Notebook;
import com.vtnexus.notebook.model.NotebookCell;
import com.vtnexus.notebook.service.IpynbConversionService;
import com.vtnexus.notebook.service.NotebookCellService;
import com.vtnexus.notebook.service.NotebookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/notebooks")
public class NotebookController {

    private final NotebookService notebookService;
    private final NotebookCellService cellService;
    private final IpynbConversionService conversionService;

    public NotebookController(NotebookService notebookService,
                              NotebookCellService cellService,
                              IpynbConversionService conversionService) {
        this.notebookService = notebookService;
        this.cellService = cellService;
        this.conversionService = conversionService;
    }

    @PostMapping
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

    @GetMapping
    public ResponseEntity<Map<String, Object>> listNotebooks(@RequestParam(value = "owner", required = false) String owner,
                                                             @RequestParam(value = "folderId", required = false) String folderId,
                                                             @RequestParam(value = "limit", defaultValue = "50") int limit,
                                                             @AuthenticationPrincipal Object principal) {
        List<Notebook> list = notebookService.listNotebooks(owner, folderId, limit, principal.toString());
        Map<String, Object> res = new HashMap<>();
        res.put("items", list);
        res.put("hasMore", false);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/{notebookId}")
    public ResponseEntity<Map<String, Object>> getNotebook(@PathVariable("notebookId") String notebookId,
                                                           @RequestParam(value = "includeCells", defaultValue = "true") boolean includeCells,
                                                           @AuthenticationPrincipal Object principal) {
        Notebook nb = notebookService.getNotebook(notebookId, principal.toString());
        Map<String, Object> res = new HashMap<>();
        res.put("id", nb.getId());
        res.put("name", nb.getName());
        res.put("folderId", nb.getFolderId());
        res.put("description", nb.getDescription());
        res.put("defaultCatalog", nb.getDefaultCatalog());
        res.put("defaultSchema", nb.getDefaultSchema());
        res.put("owner", nb.getOwner());
        res.put("version", nb.getVersion());
        res.put("role", notebookService.checkAccess(nb, principal.toString(), "VIEWER"));

        if (includeCells) {
            res.put("cells", cellService.getCells(notebookId, principal.toString()));
        }
        return ResponseEntity.ok(res);
    }

    @PatchMapping("/{notebookId}")
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

    @DeleteMapping("/{notebookId}")
    public ResponseEntity<Void> deleteNotebook(@PathVariable("notebookId") String notebookId,
                                               @RequestParam(value = "version", required = false) Long version,
                                               @AuthenticationPrincipal Object principal) {
        notebookService.deleteNotebook(notebookId, version, principal.toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{notebookId}:clone")
    public ResponseEntity<Notebook> cloneNotebook(@PathVariable("notebookId") String notebookId,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  @AuthenticationPrincipal Object principal) {
        String name = body != null ? (String) body.get("name") : null;
        String folderId = body != null ? (String) body.get("folderId") : null;
        return ResponseEntity.ok(notebookService.cloneNotebook(notebookId, name, folderId, principal.toString()));
    }

    @PostMapping("/{notebookId}:move")
    public ResponseEntity<Notebook> moveNotebook(@PathVariable("notebookId") String notebookId,
                                                 @RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal Object principal) {
        String folderId = (String) body.get("folderId");
        String name = (String) body.get("name");
        Long version = body.get("version") != null ? Long.valueOf(body.get("version").toString()) : null;
        return ResponseEntity.ok(notebookService.moveNotebook(notebookId, folderId, name, version, principal.toString()));
    }

    @PostMapping("/:import")
    public ResponseEntity<Notebook> importNotebook(@RequestBody Map<String, String> body,
                                                   @AuthenticationPrincipal Object principal) {
        String format = body.getOrDefault("format", "IPYNB");
        String name = body.get("name");
        String folderId = body.get("folderId");
        String content = body.get("content");
        return ResponseEntity.ok(conversionService.importNotebook(format, name, folderId, content, principal.toString()));
    }

    @GetMapping("/{notebookId}:export")
    public ResponseEntity<String> exportNotebook(@PathVariable("notebookId") String notebookId,
                                                 @RequestParam(value = "includeOutputs", defaultValue = "false") boolean includeOutputs,
                                                 @AuthenticationPrincipal Object principal) {
        String json = conversionService.exportNotebook(notebookId, includeOutputs, principal.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + notebookId + ".ipynb\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }
}
