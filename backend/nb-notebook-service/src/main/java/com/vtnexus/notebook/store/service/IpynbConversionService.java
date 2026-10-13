package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookCell;
import com.vtnexus.notebook.store.repository.NotebookCellRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IpynbConversionService {

    private final ObjectMapper objectMapper;
    private final NotebookService notebookService;
    private final NotebookCellRepository cellRepository;
    private final int maxImportSize;

    public IpynbConversionService(
            ObjectMapper objectMapper,
            NotebookService notebookService,
            NotebookCellRepository cellRepository,
            @Value("${notebook.import.max-size:16777216}") int maxImportSize) {
        this.objectMapper = objectMapper;
        this.notebookService = notebookService;
        this.cellRepository = cellRepository;
        this.maxImportSize = maxImportSize;
    }

    // D-6 & D-8: Import đọc metadata.kyuubi.language TRƯỚC, áp giới hạn dung lượng import
    public Notebook importNotebook(String format, String name, String folderId, String contentJson, String user) {
        if (contentJson != null && contentJson.getBytes().length > maxImportSize) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Import payload exceeds size limit of " + maxImportSize + " bytes", HttpStatus.PAYLOAD_TOO_LARGE);
        }

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

                    // Source
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

                    // D-6: Đọc metadata.kyuubi.language TRƯỚC, magic dòng đầu là fallback
                    String language = null;
                    JsonNode metaNode = cNode.get("metadata");
                    if (metaNode != null && metaNode.has("kyuubi") && metaNode.get("kyuubi").has("language")) {
                        language = metaNode.get("kyuubi").get("language").asText().toUpperCase();
                    }

                    if ("MARKDOWN".equals(cellType)) {
                        language = "MARKDOWN";
                    } else if (language == null) {
                        String firstLine = source.lines().findFirst().orElse("").trim();
                        if (firstLine.startsWith("%%sql") || firstLine.startsWith("%sql")) {
                            language = "SQL";
                            source = source.substring(firstLine.length()).stripLeading();
                        } else if (firstLine.startsWith("%%pyspark") || firstLine.startsWith("%python")) {
                            language = "PYTHON";
                            source = source.substring(firstLine.length()).stripLeading();
                        } else {
                            language = "PYTHON"; // Default
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

    // D-7: includeOutputs=true chỉ cho phép OWNER
    public String exportNotebook(String notebookId, boolean includeOutputs, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        if (includeOutputs) {
            String role = notebookService.checkAccess(notebook, user, "VIEWER");
            if (!"OWNER".equals(role)) {
                throw new NotebookApiException("ACCESS_DENIED", "Only OWNER is permitted to export outputs", HttpStatus.FORBIDDEN);
            }
        }

        List<NotebookCell> cells = cellRepository.findByNotebookIdOrderByPositionAsc(notebookId);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("nbformat", 4);
        root.put("nbformat_minor", 5);

        ObjectNode metadata = root.putObject("metadata");
        ObjectNode kernelSpec = metadata.putObject("kernelspec");
        kernelSpec.put("name", "python3");
        kernelSpec.put("display_name", "Python 3 (PySpark / Kyuubi)");
        kernelSpec.put("language", "python");

        ObjectNode languageInfo = metadata.putObject("language_info");
        languageInfo.put("name", "python");

        ArrayNode cellsNode = root.putArray("cells");
        for (NotebookCell cell : cells) {
            ObjectNode c = cellsNode.addObject();
            c.put("id", cell.getId());
            c.put("cell_type", cell.getCellType().toLowerCase());

            ObjectNode cMeta = c.putObject("metadata");
            ObjectNode kyuubiMeta = cMeta.putObject("kyuubi");
            kyuubiMeta.put("language", cell.getLanguage().toLowerCase());

            ArrayNode srcArr = c.putArray("source");
            String[] lines = cell.getSource().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (i < lines.length - 1) line += "\n";
                srcArr.add(line);
            }

            if ("CODE".equals(cell.getCellType())) {
                c.put("execution_count", cell.getExecutionCount());
                c.putArray("outputs"); // Outputs are not stored in document cell per decoupled model
            }
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new NotebookApiException("INTERNAL_ERROR", "Failed to serialize .ipynb", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
