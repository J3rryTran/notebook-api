package com.vtnexus.notebook.controller;

import com.vtnexus.notebook.model.NotebookFolder;
import com.vtnexus.notebook.service.NotebookFolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notebook-folders")
public class NotebookFolderController {

    private final NotebookFolderService folderService;

    public NotebookFolderController(NotebookFolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    public ResponseEntity<NotebookFolder> createFolder(@RequestBody Map<String, String> body,
                                                       @AuthenticationPrincipal Object principal) {
        String name = body.get("name");
        String parentId = body.get("parentId");
        String owner = principal.toString();
        return ResponseEntity.ok(folderService.createFolder(name, parentId, owner));
    }

    @GetMapping
    public ResponseEntity<List<NotebookFolder>> listFolders(@RequestParam(value = "parentId", required = false) String parentId,
                                                            @AuthenticationPrincipal Object principal) {
        String owner = principal.toString();
        return ResponseEntity.ok(folderService.listFolders(parentId, owner));
    }

    @GetMapping("/{folderId}")
    public ResponseEntity<NotebookFolder> getFolder(@PathVariable("folderId") String folderId,
                                                    @AuthenticationPrincipal Object principal) {
        return ResponseEntity.ok(folderService.getFolder(folderId, principal.toString()));
    }

    @PatchMapping("/{folderId}")
    public ResponseEntity<NotebookFolder> updateFolder(@PathVariable("folderId") String folderId,
                                                       @RequestBody Map<String, Object> body,
                                                       @AuthenticationPrincipal Object principal) {
        String name = (String) body.get("name");
        String parentId = (String) body.get("parentId");
        Long version = body.get("version") != null ? Long.valueOf(body.get("version").toString()) : null;
        return ResponseEntity.ok(folderService.updateFolder(folderId, name, parentId, version, principal.toString()));
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> deleteFolder(@PathVariable("folderId") String folderId,
                                             @RequestParam(value = "version", required = false) Long version,
                                             @AuthenticationPrincipal Object principal) {
        folderService.deleteFolder(folderId, version, principal.toString());
        return ResponseEntity.noContent().build();
    }
}
