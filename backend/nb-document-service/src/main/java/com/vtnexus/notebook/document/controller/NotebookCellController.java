package com.vtnexus.notebook.document.controller;

import com.vtnexus.notebook.document.model.NotebookCell;
import com.vtnexus.notebook.document.service.NotebookCellService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notebooks/{notebookId}/cells")
public class NotebookCellController {

    private final NotebookCellService cellService;

    public NotebookCellController(NotebookCellService cellService) {
        this.cellService = cellService;
    }

    @GetMapping
    public ResponseEntity<List<NotebookCell>> getCells(@PathVariable("notebookId") String notebookId,
                                                       @AuthenticationPrincipal Object principal) {
        return ResponseEntity.ok(cellService.getCells(notebookId, principal.toString()));
    }

    @PostMapping
    public ResponseEntity<NotebookCell> createCell(@PathVariable("notebookId") String notebookId,
                                                   @RequestBody Map<String, Object> body,
                                                   @AuthenticationPrincipal Object principal) {
        String cellType = (String) body.get("cellType");
        String language = (String) body.get("language");
        String source = (String) body.get("source");
        Integer position = body.get("position") != null ? ((Number) body.get("position")).intValue() : null;

        return ResponseEntity.ok(cellService.createCell(notebookId, cellType, language, source, position, principal.toString()));
    }

    @GetMapping("/{cellId}")
    public ResponseEntity<NotebookCell> getCell(@PathVariable("notebookId") String notebookId,
                                                @PathVariable("cellId") String cellId,
                                                @AuthenticationPrincipal Object principal) {
        return ResponseEntity.ok(cellService.getCell(notebookId, cellId, principal.toString()));
    }

    @PatchMapping("/{cellId}")
    public ResponseEntity<NotebookCell> updateCell(@PathVariable("notebookId") String notebookId,
                                                   @PathVariable("cellId") String cellId,
                                                   @RequestBody Map<String, Object> body,
                                                   @AuthenticationPrincipal Object principal) {
        String cellType = (String) body.get("cellType");
        String language = (String) body.get("language");
        String source = (String) body.get("source");

        return ResponseEntity.ok(cellService.updateCell(notebookId, cellId, cellType, language, source, principal.toString()));
    }

    @DeleteMapping("/{cellId}")
    public ResponseEntity<Void> deleteCell(@PathVariable("notebookId") String notebookId,
                                           @PathVariable("cellId") String cellId,
                                           @AuthenticationPrincipal Object principal) {
        cellService.deleteCell(notebookId, cellId, principal.toString());
        return ResponseEntity.noContent().build();
    }

    // D-5: Khai báo nguyên URL spec /api/v1/notebooks/{notebookId}/cells:reorder
    @PutMapping(path = "/cells:reorder")
    public ResponseEntity<Void> reorderCells(@PathVariable("notebookId") String notebookId,
                                             @RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal Object principal) {
        List<String> cellIds = (List<String>) body.get("cellIds");
        Long version = body.get("version") != null ? Long.valueOf(body.get("version").toString()) : null;
        cellService.reorderCells(notebookId, cellIds, version, principal.toString());
        return ResponseEntity.noContent().build();
    }
}
