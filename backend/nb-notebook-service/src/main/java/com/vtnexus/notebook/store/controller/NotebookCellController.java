package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.store.dto.NotebookCellDto;
import com.vtnexus.notebook.store.service.NotebookCellService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notebooks/{notebookId}")
public class NotebookCellController {

    private final NotebookCellService cellService;

    public NotebookCellController(NotebookCellService cellService) {
        this.cellService = cellService;
    }

    @GetMapping("/cells")
    public ResponseEntity<List<NotebookCellDto>> getCells(@PathVariable("notebookId") String notebookId,
                                                          @AuthenticationPrincipal Object principal) {
        return ResponseEntity.ok(cellService.getCells(notebookId, principal.toString()));
    }

    @PostMapping("/cells")
    public ResponseEntity<NotebookCellDto> createCell(@PathVariable("notebookId") String notebookId,
                                                      @RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal Object principal) {
        String cellType = (String) body.get("cellType");
        String language = (String) body.get("language");
        String source = (String) body.get("source");
        Integer position = body.get("position") != null ? ((Number) body.get("position")).intValue() : null;

        NotebookCellDto cell = cellService.createCell(notebookId, cellType, language, source, position, principal.toString());
        cell.setMessage("Cell created successfully");
        return ResponseEntity.ok(cell);
    }

    @GetMapping("/cells/{cellId}")
    public ResponseEntity<NotebookCellDto> getCell(@PathVariable("notebookId") String notebookId,
                                                   @PathVariable("cellId") String cellId,
                                                   @AuthenticationPrincipal Object principal) {
        NotebookCellDto cell = cellService.getCell(notebookId, cellId, principal.toString());
        cell.setMessage("Cell retrieved successfully");
        return ResponseEntity.ok(cell);
    }

    @PatchMapping("/cells/{cellId}")
    public ResponseEntity<NotebookCellDto> updateCell(@PathVariable("notebookId") String notebookId,
                                                      @PathVariable("cellId") String cellId,
                                                      @RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal Object principal) {
        String cellType = (String) body.get("cellType");
        String language = (String) body.get("language");
        String source = (String) body.get("source");
        boolean hasOutputs = body.containsKey("outputs");
        @SuppressWarnings("unchecked")
        List<Object> outputs = hasOutputs ? (List<Object>) body.get("outputs") : null;
        boolean hasExecutionCount = body.containsKey("executionCount");
        Integer executionCount = hasExecutionCount && body.get("executionCount") != null
                ? ((Number) body.get("executionCount")).intValue()
                : null;

        NotebookCellDto cell = cellService.updateCell(notebookId, cellId, cellType, language, source,
                outputs, hasOutputs, executionCount, hasExecutionCount, principal.toString());
        cell.setMessage("Cell updated successfully");
        return ResponseEntity.ok(cell);
    }

    @DeleteMapping("/cells/{cellId}")
    public ResponseEntity<Map<String, Object>> deleteCell(@PathVariable("notebookId") String notebookId,
                                                          @PathVariable("cellId") String cellId,
                                                          @AuthenticationPrincipal Object principal) {
        cellService.deleteCell(notebookId, cellId, principal.toString());
        return ResponseEntity.ok(Map.of(
                "message", "Cell deleted successfully",
                "cellId", cellId
        ));
    }

    @PutMapping(path = "/cells:reorder")
    public ResponseEntity<Map<String, Object>> reorderCells(@PathVariable("notebookId") String notebookId,
                                                            @RequestBody Map<String, Object> body,
                                                            @AuthenticationPrincipal Object principal) {
        @SuppressWarnings("unchecked")
        List<String> cellIds = (List<String>) body.get("cellIds");
        Long version = body.get("version") != null ? Long.valueOf(body.get("version").toString()) : null;
        cellService.reorderCells(notebookId, cellIds, version, principal.toString());
        return ResponseEntity.ok(Map.of(
                "message", "Cells reordered successfully"
        ));
    }
}
