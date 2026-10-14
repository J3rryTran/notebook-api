package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.dto.NotebookCellDto;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookDocument;
import com.vtnexus.notebook.store.repository.NotebookRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class NotebookCellService {

    private final NotebookRepository notebookRepository;
    private final NotebookService notebookService;
    private final ObjectMapper objectMapper;
    private final int maxSourceSize;
    private final int maxCells;

    public NotebookCellService(
            NotebookRepository notebookRepository,
            NotebookService notebookService,
            ObjectMapper objectMapper,
            @Value("${notebook.cell.source.max-size:1048576}") int maxSourceSize,
            @Value("${notebook.max.cells:500}") int maxCells) {
        this.notebookRepository = notebookRepository;
        this.notebookService = notebookService;
        this.objectMapper = objectMapper;
        this.maxSourceSize = maxSourceSize;
        this.maxCells = maxCells;
    }

    public List<NotebookCellDto> getCells(String notebookId, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());
        List<NotebookCellDto> dtos = new ArrayList<>();
        int pos = 0;
        for (NotebookDocument.CellItem item : doc.getCells()) {
            dtos.add(toDto(item, pos++));
        }
        return dtos;
    }

    public NotebookCellDto getCell(String notebookId, String cellId, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());
        int pos = 0;
        for (NotebookDocument.CellItem item : doc.getCells()) {
            if (Objects.equals(item.getId(), cellId)) {
                return toDto(item, pos);
            }
            pos++;
        }
        throw new NotebookApiException("CELL_NOT_FOUND", "Cell not found: " + cellId, HttpStatus.NOT_FOUND);
    }

    public NotebookCellDto createCell(String notebookId, String cellType, String language, String source, Integer position, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());
        if (doc.getCells().size() >= maxCells) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Notebook already reached max cell limit of " + maxCells, HttpStatus.PAYLOAD_TOO_LARGE);
        }

        if (source != null && source.getBytes().length > maxSourceSize) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Cell source size exceeds limit of " + maxSourceSize + " bytes", HttpStatus.PAYLOAD_TOO_LARGE);
        }

        String type = (cellType != null) ? cellType.toUpperCase() : "CODE";
        String lang = (language != null) ? language.toUpperCase() : ("MARKDOWN".equals(type) ? "MARKDOWN" : "SQL");

        if ("MARKDOWN".equals(type) && !"MARKDOWN".equals(lang)) {
            throw new NotebookApiException("UNSUPPORTED_LANGUAGE", "Markdown cells must use language MARKDOWN", HttpStatus.BAD_REQUEST);
        }

        String cellId = "cell-" + UUID.randomUUID();
        NotebookDocument.CellItem item = NotebookDocument.CellItem.builder()
                .id(cellId)
                .type(type)
                .language(lang)
                .source(source != null ? source : "")
                .metadata(new LinkedHashMap<>())
                .build();

        int targetPos = (position != null && position >= 0 && position <= doc.getCells().size())
                ? position
                : doc.getCells().size();

        doc.getCells().add(targetPos, item);

        saveNotebookDocument(notebook, doc);
        notebookService.snapshotRevision(notebookId, "Add cell " + cellId, user, "AUTO");
        return toDto(item, targetPos);
    }

    public NotebookCellDto updateCell(String notebookId, String cellId, String cellType, String language, String source, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());

        if (source != null && source.getBytes().length > maxSourceSize) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Cell source size exceeds limit of " + maxSourceSize + " bytes", HttpStatus.PAYLOAD_TOO_LARGE);
        }

        int targetIndex = -1;
        for (int i = 0; i < doc.getCells().size(); i++) {
            if (Objects.equals(doc.getCells().get(i).getId(), cellId)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex == -1) {
            throw new NotebookApiException("CELL_NOT_FOUND", "Cell not found: " + cellId, HttpStatus.NOT_FOUND);
        }

        NotebookDocument.CellItem item = doc.getCells().get(targetIndex);
        if (cellType != null) item.setType(cellType.toUpperCase());
        if (language != null) item.setLanguage(language.toUpperCase());
        if (source != null) item.setSource(source);

        saveNotebookDocument(notebook, doc);
        notebookService.snapshotRevision(notebookId, "Update cell " + cellId, user, "AUTO");
        return toDto(item, targetIndex);
    }

    public void deleteCell(String notebookId, String cellId, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());
        boolean removed = doc.getCells().removeIf(c -> Objects.equals(c.getId(), cellId));
        if (!removed) {
            throw new NotebookApiException("CELL_NOT_FOUND", "Cell not found: " + cellId, HttpStatus.NOT_FOUND);
        }

        saveNotebookDocument(notebook, doc);
        notebookService.snapshotRevision(notebookId, "Delete cell " + cellId, user, "AUTO");
    }

    public void reorderCells(String notebookId, List<String> cellIds, Long version, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        notebookService.checkAccess(notebook, user, "EDITOR");

        if (version != null && !notebook.getLockVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Notebook was modified concurrently", HttpStatus.CONFLICT);
        }

        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());
        if (cellIds == null || cellIds.size() != doc.getCells().size()) {
            throw new NotebookApiException("INVALID_REQUEST", "Reorder list must contain all cell IDs exactly once", HttpStatus.BAD_REQUEST);
        }

        Map<String, NotebookDocument.CellItem> cellMap = new HashMap<>();
        for (NotebookDocument.CellItem c : doc.getCells()) cellMap.put(c.getId(), c);

        List<NotebookDocument.CellItem> reordered = new ArrayList<>();
        for (String cid : cellIds) {
            NotebookDocument.CellItem item = cellMap.remove(cid);
            if (item == null) {
                throw new NotebookApiException("INVALID_REQUEST", "Unknown or duplicate cell ID in reorder: " + cid, HttpStatus.BAD_REQUEST);
            }
            reordered.add(item);
        }

        doc.setCells(reordered);
        saveNotebookDocument(notebook, doc);
        notebookService.snapshotRevision(notebookId, "Reorder cells", user, "AUTO");
    }

    private void saveNotebookDocument(Notebook notebook, NotebookDocument doc) {
        try {
            notebook.setDocumentJson(objectMapper.writeValueAsString(doc));
            notebook.setCurrentRevision(notebook.getCurrentRevision() + 1);
            notebookRepository.save(notebook);
        } catch (Exception e) {
            throw new NotebookApiException("INTERNAL_ERROR", "Failed to serialize document: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private NotebookCellDto toDto(NotebookDocument.CellItem item, int position) {
        return NotebookCellDto.builder()
                .id(item.getId())
                .cellType(item.getType())
                .language(item.getLanguage())
                .source(item.getSource())
                .position(position)
                .metadata(item.getMetadata())
                .build();
    }
}
