package com.vtnexus.notebook.service;

import com.vtnexus.notebook.exception.NotebookApiException;
import com.vtnexus.notebook.model.Notebook;
import com.vtnexus.notebook.model.NotebookCell;
import com.vtnexus.notebook.repository.NotebookCellRepository;
import com.vtnexus.notebook.repository.NotebookRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class NotebookCellService {

    private final NotebookRepository notebookRepository;
    private final NotebookCellRepository cellRepository;
    private final NotebookService notebookService;

    public NotebookCellService(NotebookRepository notebookRepository,
                               NotebookCellRepository cellRepository,
                               NotebookService notebookService) {
        this.notebookRepository = notebookRepository;
        this.cellRepository = cellRepository;
        this.notebookService = notebookService;
    }

    public List<NotebookCell> getCells(String notebookId, String user) {
        notebookService.getNotebook(notebookId, user);
        return cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);
    }

    public NotebookCell getCell(String notebookId, String cellId, String user) {
        notebookService.getNotebook(notebookId, user);
        return cellRepository.findByIdAndNotebookId(cellId, notebookId)
                .orElseThrow(() -> new NotebookApiException("CELL_NOT_FOUND", "Cell not found: " + cellId, HttpStatus.NOT_FOUND));
    }

    public NotebookCell createCell(String notebookId, String cellType, String language, String source, Integer position, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        String type = (cellType != null) ? cellType.toUpperCase() : "CODE";
        String lang = (language != null) ? language.toUpperCase() : ("MARKDOWN".equals(type) ? "MARKDOWN" : "SQL");

        if ("MARKDOWN".equals(type) && !"MARKDOWN".equals(lang)) {
            throw new NotebookApiException("UNSUPPORTED_LANGUAGE", "Markdown cells must use language MARKDOWN", HttpStatus.BAD_REQUEST);
        }

        Integer maxPos = cellRepository.findMaxPositionByNotebookId(notebookId);
        int targetPos = (position != null) ? position : (maxPos != null ? maxPos + 1 : 0);

        if (position != null && maxPos != null && position <= maxPos) {
            cellRepository.shiftPositionsDown(notebookId, targetPos);
        }

        NotebookCell cell = NotebookCell.builder()
                .id(UUID.randomUUID().toString())
                .notebook(notebook)
                .cellType(type)
                .language(lang)
                .source(source != null ? source : "")
                .position(targetPos)
                .build();

        notebook.setVersion(notebook.getVersion() + 1);
        notebookRepository.save(notebook);
        NotebookCell saved = cellRepository.save(cell);
        notebookService.snapshotRevision(notebookId, "Add cell " + saved.getId(), user, false);
        return saved;
    }

    public NotebookCell updateCell(String notebookId, String cellId, String cellType, String language, String source, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        NotebookCell cell = getCell(notebookId, cellId, user);

        if (cellType != null) cell.setCellType(cellType.toUpperCase());
        if (language != null) cell.setLanguage(language.toUpperCase());
        if (source != null) cell.setSource(source);

        notebook.setVersion(notebook.getVersion() + 1);
        notebookRepository.save(notebook);
        NotebookCell saved = cellRepository.save(cell);
        notebookService.snapshotRevision(notebookId, "Update cell " + cellId, user, false);
        return saved;
    }

    public void deleteCell(String notebookId, String cellId, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        NotebookCell cell = getCell(notebookId, cellId, user);
        cellRepository.delete(cell);

        notebook.setVersion(notebook.getVersion() + 1);
        notebookRepository.save(notebook);
        notebookService.snapshotRevision(notebookId, "Delete cell " + cellId, user, false);
    }

    public void reorderCells(String notebookId, List<String> cellIds, Long version, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        if (version != null && !notebook.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        List<NotebookCell> existing = cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);
        if (cellIds == null || cellIds.size() != existing.size()) {
            throw new NotebookApiException("INVALID_REQUEST", "Reorder list must contain all cell IDs exactly once", HttpStatus.BAD_REQUEST);
        }

        Map<String, NotebookCell> cellMap = new HashMap<>();
        for (NotebookCell c : existing) cellMap.put(c.getId(), c);

        for (int i = 0; i < cellIds.size(); i++) {
            String cid = cellIds.get(i);
            NotebookCell cell = cellMap.remove(cid);
            if (cell == null) {
                throw new NotebookApiException("INVALID_REQUEST", "Unknown or duplicate cell ID in reorder: " + cid, HttpStatus.BAD_REQUEST);
            }
            cell.setPosition(i);
            cellRepository.save(cell);
        }

        notebook.setVersion(notebook.getVersion() + 1);
        notebookRepository.save(notebook);
        notebookService.snapshotRevision(notebookId, "Reorder cells", user, false);
    }
}
