package com.vtnexus.notebook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vtnexus.notebook.exception.NotebookApiException;
import com.vtnexus.notebook.model.Notebook;
import com.vtnexus.notebook.model.NotebookCell;
import com.vtnexus.notebook.repository.NotebookCellRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IpynbConversionService {

    private final ObjectMapper objectMapper;
    private final NotebookService notebookService;
    private final NotebookCellRepository cellRepository;

    public IpynbConversionService(ObjectMapper objectMapper, NotebookService notebookService, NotebookCellRepository cellRepository) {
        this.objectMapper = objectMapper;
        this.notebookService = notebookService;
        this.cellRepository = cellRepository;
    }

    public Notebook importNotebook(String format, String name, String folderId, String contentJson, String user) {
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            String nbName = (name != null && !name.isBlank()) ? name : "imported-notebook";

            List<Map<String, Object>> cells = new ArrayList<>();
            JsonNode cellsNode = root.get("cells");
            if (cellsNode != null && cellsNode.isArray()) {
                for (JsonNode cNode : cellsNode) {
                    String cellType = cNode.path("cell_type").asText("code").toUpperCase();
                    if (!"MARKDOWN".equals(cellType) && !"RAW".equals(cellType)) {
                        cellType = "CODE";
                    }

                    // Source can be string or array of strings
                    String source = "";
                    JsonNode srcNode = cNode.get("source");
                    if (srcNode != null) {
                        if (srcNode.isArray()) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode line : srcNode) sb.append(line.asText());
                            source = sb.toString();
                        } else {
                            source = srcNode.asText();
                        }
                    }

                    // Infer language and strip magic line
                    String language = "PYTHON";
                    if ("MARKDOWN".equals(cellType)) {
                        language = "MARKDOWN";
                    } else {
                        String firstLine = source.lines().findFirst().orElse("").trim();
                        if (firstLine.startsWith("%%sql") || firstLine.startsWith("%sql")) {
                            language = "SQL";
                            source = source.substring(firstLine.length()).stripLeading();
                        } else if (firstLine.startsWith("%%pyspark") || firstLine.startsWith("%python")) {
                            language = "PYTHON";
                            source = source.substring(firstLine.length()).stripLeading();
                        }
                    }

                    Map<String, Object> cellMap = new HashMap<>();
                    cellMap.put("cellType", cellType);
                    cellMap.put("language", language);
                    cellMap.put("source", source);
                    cells.add(cellMap);
                }
            }

            return notebookService.createNotebook(nbName, folderId, null, "spark_catalog", "default", cells, user);

        } catch (Exception e) {
            throw new NotebookApiException("INVALID_REQUEST", "Failed to parse .ipynb file: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    public String exportNotebook(String notebookId, boolean includeOutputs, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        List<NotebookCell> cells = cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("nbformat", 4);
        root.put("nbformat_minor", 5);

        // Metadata
        ObjectNode metadata = root.putObject("metadata");
        ObjectNode kernelSpec = metadata.putObject("kernelspec");
        kernelSpec.put("name", "python3");
        kernelSpec.put("display_name", "Python 3 (PySpark / Kyuubi)");
        kernelSpec.put("language", "python");

        ObjectNode languageInfo = metadata.putObject("language_info");
        languageInfo.put("name", "python");

        // Cells
        ArrayNode cellsNode = root.putArray("cells");
        for (NotebookCell cell : cells) {
            ObjectNode c = cellsNode.addObject();
            c.put("id", cell.getId());
            c.put("cell_type", cell.getCellType().toLowerCase());

            ObjectNode cMeta = c.putObject("metadata");
            ObjectNode kyuubiMeta = cMeta.putObject("kyuubi");
            kyuubiMeta.put("language", cell.getLanguage().toLowerCase());

            // Source lines
            ArrayNode srcArr = c.putArray("source");
            String[] lines = cell.getSource().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (i < lines.length - 1) line += "\n";
                srcArr.add(line);
            }

            if ("CODE".equals(cell.getCellType())) {
                c.put("execution_count", cell.getExecutionCount());
                ArrayNode outArr = c.putArray("outputs");
                if (includeOutputs && cell.getOutputsJson() != null && !cell.getOutputsJson().isBlank()) {
                    try {
                        JsonNode parsedOut = objectMapper.readTree(cell.getOutputsJson());
                        if (parsedOut.isArray()) {
                            outArr.addAll((ArrayNode) parsedOut);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new NotebookApiException("INTERNAL_ERROR", "Failed to serialize .ipynb", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
