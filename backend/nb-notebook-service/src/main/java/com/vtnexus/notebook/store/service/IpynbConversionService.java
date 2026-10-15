package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IpynbConversionService {

    private final ObjectMapper objectMapper;
    private final NotebookService notebookService;
    private final int maxImportSize;

    public IpynbConversionService(
            ObjectMapper objectMapper,
            NotebookService notebookService,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        this.objectMapper = objectMapper;
        this.notebookService = notebookService;
        this.maxImportSize = notebookProperties.getAnImport().getMaxSize();
    }

    public Notebook importNotebook(String format, String name, String folderId, String contentJson, String user) {
        if (contentJson != null && contentJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxImportSize) {
            throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Import payload exceeds size limit of " + maxImportSize + " bytes", HttpStatus.PAYLOAD_TOO_LARGE);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(contentJson);
        } catch (Exception e) {
            throw new NotebookApiException("INVALID_REQUEST", "Failed to parse .ipynb JSON: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        try {
            String defaultLanguage = "SQL";
            JsonNode metaRoot = root.get("metadata");
            if (metaRoot != null) {
                if (metaRoot.has("language_info") && metaRoot.get("language_info").has("name")) {
                    String langName = metaRoot.get("language_info").get("name").asText();
                    if ("python".equalsIgnoreCase(langName)) defaultLanguage = "PYTHON";
                    else if ("sql".equalsIgnoreCase(langName)) defaultLanguage = "SQL";
                }
                if (metaRoot.has("language")) {
                    String langName = metaRoot.get("language").asText();
                    if ("python".equalsIgnoreCase(langName)) defaultLanguage = "PYTHON";
                    else if ("sql".equalsIgnoreCase(langName)) defaultLanguage = "SQL";
                }
            }

            String nbName = (name != null && !name.isBlank()) ? name : "imported-notebook";
            List<NotebookDocument.CellItem> cellItems = new ArrayList<>();

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

                    if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576) {
                        throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Cell source size exceeds limit of 1048576 bytes", HttpStatus.PAYLOAD_TOO_LARGE);
                    }

                    String language = null;
                    JsonNode metaNode = cNode.get("metadata");
                    if (metaNode != null && metaNode.has("language")) {
                        language = metaNode.get("language").asText().toUpperCase();
                    } else if (metaNode != null && metaNode.has("kyuubi") && metaNode.get("kyuubi").has("language")) {
                        language = metaNode.get("kyuubi").get("language").asText().toUpperCase();
                    }

                    if ("MARKDOWN".equals(cellType)) {
                        language = "MARKDOWN";
                    } else if ("RAW".equals(cellType)) {
                        language = "RAW";
                    } else if (language == null) {
                        String firstLine = source.lines().findFirst().orElse("").trim();
                        if (firstLine.startsWith("%%sql") || firstLine.startsWith("%sql")) {
                            language = "SQL";
                            source = source.substring(firstLine.length()).stripLeading();
                        } else if (firstLine.startsWith("%%python") || firstLine.startsWith("%python") || firstLine.startsWith("%%pyspark")) {
                            language = "PYTHON";
                            source = source.substring(firstLine.length()).stripLeading();
                        } else {
                            language = defaultLanguage;
                        }
                    }

                    Map<String, Object> cellMetadata = new LinkedHashMap<>();
                    if (metaNode != null && metaNode.isObject()) {
                        metaNode.fields().forEachRemaining(entry -> {
                            cellMetadata.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
                        });
                    }

                    if (cNode.has("execution_count") && !cNode.get("execution_count").isNull()) {
                        cellMetadata.put("executionCount", cNode.get("execution_count").asInt());
                    }

                    List<Object> outputs = null;
                    if (cNode.has("outputs") && cNode.get("outputs").isArray()) {
                        outputs = objectMapper.convertValue(cNode.get("outputs"), List.class);
                    }

                    String cellId = cNode.has("id") ? cNode.get("id").asText() : ("cell-" + UUID.randomUUID());
                    if (!cellId.startsWith("cell-")) {
                        cellId = "cell-" + cellId;
                    }

                    NotebookDocument.CellItem item = NotebookDocument.CellItem.builder()
                            .id(cellId)
                            .type(cellType)
                            .language(language)
                            .source(source)
                            .metadata(cellMetadata)
                            .outputs(outputs)
                            .build();
                    cellItems.add(item);
                }
            }

            NotebookDocument doc = NotebookDocument.builder()
                    .schemaVersion(1)
                    .defaultLanguage(defaultLanguage)
                    .metadata(new LinkedHashMap<>())
                    .parameters(new ArrayList<>())
                    .cells(cellItems)
                    .build();

            doc.getMetadata().put("name", nbName.trim());
            if (metaRoot != null && metaRoot.has("kernelspec")) {
                doc.getMetadata().put("kernelspec", objectMapper.convertValue(metaRoot.get("kernelspec"), Object.class));
            }
            if (metaRoot != null && metaRoot.has("language_info")) {
                doc.getMetadata().put("language_info", objectMapper.convertValue(metaRoot.get("language_info"), Object.class));
            }

            String docJson = objectMapper.writeValueAsString(doc);
            if (docJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024) {
                throw new NotebookApiException("PAYLOAD_TOO_LARGE", "Notebook document size exceeds 16 MB limit", HttpStatus.PAYLOAD_TOO_LARGE);
            }

            String notebookId = UUID.randomUUID().toString();
            Notebook notebook = Notebook.builder()
                    .id(notebookId)
                    .name(nbName.trim())
                    .folderId(folderId)
                    .description("")
                    .owner(user)
                    .notebookFormat("SQLLAB_JSON_V1")
                    .defaultLanguage(defaultLanguage)
                    .documentJson(docJson)
                    .currentRevision(1L)
                    .lockVersion(0L)
                    .build();

            Notebook saved = notebookService.getNotebookRepository().save(notebook);
            notebookService.snapshotRevision(notebookId, "Imported from .ipynb", user, "IMPORT");
            return saved;

        } catch (NotebookApiException nae) {
            throw nae;
        } catch (Exception e) {
            throw new NotebookApiException("INVALID_REQUEST", "Failed to parse .ipynb file: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    public String exportNotebook(String notebookId, boolean includeOutputs, String user) {
        Notebook notebook = notebookService.getNotebook(notebookId, user);
        if (includeOutputs) {
            String role = notebookService.checkAccess(notebook, user, "VIEWER");
            if (!"OWNER".equals(role)) {
                throw new NotebookApiException("ACCESS_DENIED", "Only OWNER is permitted to export outputs", HttpStatus.FORBIDDEN);
            }
        }

        NotebookDocument doc = notebookService.parseDocument(notebook.getDocumentJson());

        ObjectNode root = objectMapper.createObjectNode();
        root.put("nbformat", 4);
        root.put("nbformat_minor", 5);

        ObjectNode metadata = root.putObject("metadata");
        if (doc.getMetadata() != null && doc.getMetadata().containsKey("kernelspec")) {
            metadata.set("kernelspec", objectMapper.valueToTree(doc.getMetadata().get("kernelspec")));
        } else {
            ObjectNode kernelSpec = metadata.putObject("kernelspec");
            kernelSpec.put("name", "python3");
            kernelSpec.put("display_name", "Python 3 (PySpark / Kyuubi)");
            kernelSpec.put("language", "python");
        }

        if (doc.getMetadata() != null && doc.getMetadata().containsKey("language_info")) {
            metadata.set("language_info", objectMapper.valueToTree(doc.getMetadata().get("language_info")));
        } else {
            ObjectNode languageInfo = metadata.putObject("language_info");
            languageInfo.put("name", "python");
        }

        ArrayNode cellsNode = root.putArray("cells");
        for (NotebookDocument.CellItem cell : doc.getCells()) {
            ObjectNode c = cellsNode.addObject();
            c.put("id", cell.getId());
            c.put("cell_type", cell.getType().toLowerCase());

            ObjectNode cMeta = c.putObject("metadata");
            if (cell.getMetadata() != null) {
                cell.getMetadata().forEach((k, v) -> {
                    if (!"executionCount".equals(k)) {
                        cMeta.set(k, objectMapper.valueToTree(v));
                    }
                });
            }
            if (!"MARKDOWN".equalsIgnoreCase(cell.getType()) && !"RAW".equalsIgnoreCase(cell.getType())) {
                ObjectNode kyuubiMeta = cMeta.has("kyuubi") && cMeta.get("kyuubi").isObject()
                        ? (ObjectNode) cMeta.get("kyuubi")
                        : cMeta.putObject("kyuubi");
                kyuubiMeta.put("language", cell.getLanguage().toLowerCase());
            }

            ArrayNode srcArr = c.putArray("source");
            String[] lines = cell.getSource().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (i < lines.length - 1) line += "\n";
                srcArr.add(line);
            }

            if ("CODE".equalsIgnoreCase(cell.getType())) {
                Object execCount = cell.getMetadata() != null ? cell.getMetadata().get("executionCount") : null;
                if (execCount instanceof Number) {
                    c.put("execution_count", ((Number) execCount).intValue());
                } else {
                    c.putNull("execution_count");
                }

                if (includeOutputs && cell.getOutputs() != null) {
                    c.set("outputs", objectMapper.valueToTree(cell.getOutputs()));
                } else {
                    c.putArray("outputs");
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
