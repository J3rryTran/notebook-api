package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.dto.NotebookCellDto;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.service.NotebookCellService;
import com.vtnexus.notebook.store.service.NotebookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoints nội bộ chỉ phục vụ giao tiếp giữa các microservices (execution-service).
 * Chỉ chấp nhận header X-Internal-Token khớp secret cấu hình chung.
 */
@RestController
@RequestMapping("/internal")
public class InternalAccessController {

    private final NotebookService notebookService;
    private final NotebookCellService cellService;
    private final String internalToken;

    public InternalAccessController(
            NotebookService notebookService,
            NotebookCellService cellService,
            @Value("${notebook.internalToken:${notebook.internal-token:}}") String internalToken) {
        this.notebookService = notebookService;
        this.cellService = cellService;
        this.internalToken = internalToken;
    }

    private void verifyInternalToken(String token) {
        if (token == null || internalToken == null || !token.equals(internalToken)) {
            throw new NotebookApiException("ACCESS_DENIED", "Invalid internal access token", HttpStatus.UNAUTHORIZED);
        }
    }

    @GetMapping("/notebooks/{id}/access")
    public ResponseEntity<Map<String, Object>> getAccess(
            @PathVariable("id") String notebookId,
            @RequestParam("user") String user,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        verifyInternalToken(token);
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        String role = notebookService.checkAccess(notebook, user, "VIEWER");

        Map<String, Object> resp = new HashMap<>();
        resp.put("notebookId", notebookId);
        resp.put("user", user);
        resp.put("role", role);
        resp.put("canExecute", notebookService.getRoleLevel(role) >= notebookService.getRoleLevel("RUNNER"));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/notebooks/{id}/cells/{cellId}")
    public ResponseEntity<Map<String, Object>> getCellInternal(
            @PathVariable("id") String notebookId,
            @PathVariable("cellId") String cellId,
            @RequestParam("user") String user,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        verifyInternalToken(token);
        NotebookCellDto cell = cellService.getCell(notebookId, cellId, user);

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", cell.getId());
        resp.put("notebookId", notebookId);
        resp.put("cellType", cell.getCellType());
        resp.put("language", cell.getLanguage());
        resp.put("source", cell.getSource());
        return ResponseEntity.ok(resp);
    }
}
