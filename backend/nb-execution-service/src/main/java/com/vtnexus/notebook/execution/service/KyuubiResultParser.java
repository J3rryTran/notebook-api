package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thuần Java (không phụ thuộc Spring) chuyên phân tích kết quả, metadata, logs và lỗi từ Kyuubi REST.
 */
public class KyuubiResultParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SQL_EXCEPTION_PATTERN = Pattern.compile("([a-zA-Z_0-9]+(?:\\.[a-zA-Z_0-9]+)*Exception)");
    private static final Pattern PYTHON_ERROR_LINE_PATTERN = Pattern.compile("^([a-zA-Z_0-9]+Error|[a-zA-Z_0-9]+Exception):\\s*(.*)$");

    public record Column(String columnName, String dataType, Integer precision, Integer scale, String comment) {}

    public record ErrorInfo(String ename, String evalue, List<String> traceback) {}

    /**
     * Parse columns từ Kyuubi GET /api/v1/operations/{op}/resultsetmetadata
     * JSON mẫu: {"columns":[{"columnName":"id","dataType":"INT_TYPE","precision":0,"scale":0,"comment":""}]}
     */
    public List<Column> columns(JsonNode metadata) {
        if (metadata == null || !metadata.has("columns") || !metadata.get("columns").isArray()) {
            return Collections.emptyList();
        }
        List<Column> result = new ArrayList<>();
        for (JsonNode colNode : metadata.get("columns")) {
            String colName = colNode.path("columnName").asText("");
            String dataType = colNode.path("dataType").asText("");
            Integer precision = colNode.has("precision") && !colNode.get("precision").isNull() ? colNode.get("precision").asInt() : null;
            Integer scale = colNode.has("scale") && !colNode.get("scale").isNull() ? colNode.get("scale").asInt() : null;
            String comment = colNode.path("comment").asText("");
            result.add(new Column(colName, dataType, precision, scale, comment));
        }
        return result;
    }

    /**
     * Parse rows từ Kyuubi GET /api/v1/operations/{op}/rowset
     * JSON mẫu: {"rows":[{"fields":[{"dataType":"INT_TYPE","value":1},{"dataType":"STRING_TYPE","value":"a"}]}],"rowCount":1}
     */
    public List<List<JsonNode>> rows(JsonNode rowset) {
        if (rowset == null || !rowset.has("rows") || !rowset.get("rows").isArray()) {
            return Collections.emptyList();
        }
        List<List<JsonNode>> result = new ArrayList<>();
        for (JsonNode rowNode : rowset.get("rows")) {
            List<JsonNode> rowValues = new ArrayList<>();
            if (rowNode.has("fields") && rowNode.get("fields").isArray()) {
                for (JsonNode fieldNode : rowNode.get("fields")) {
                    rowValues.add(fieldNode.get("value")); // may be null, number, string, boolean
                }
            }
            result.add(rowValues);
        }
        return result;
    }

    /**
     * Parse log lines từ Kyuubi GET /api/v1/operations/{op}/log
     * JSON mẫu: {"logRowSet":["line1","line2"],"rowCount":2}
     */
    public List<String> logLines(JsonNode log) {
        if (log == null || !log.has("logRowSet") || !log.get("logRowSet").isArray()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode lineNode : log.get("logRowSet")) {
            lines.add(lineNode.asText());
        }
        return lines;
    }

    /**
     * Phân tách ename / evalue / traceback từ message lỗi.
     * - Python: tìm traceback theo 'Traceback (most recent call last):', dòng cuối 'Name: value'.
     * - SQL: 'ename' là lớp exception đầu tiên dạng 'org.apache...Exception', mặc định "SQLException".
     * - Traceback tách theo '\n', giới hạn 200 dòng.
     */
    public ErrorInfo error(String message) {
        if (message == null || message.isBlank()) {
            return new ErrorInfo("ExecutionError", "Unknown execution error", Collections.singletonList("Unknown execution error"));
        }

        String[] rawLines = message.split("\\r?\\n");
        List<String> traceback = new ArrayList<>();
        int limit = Math.min(rawLines.length, 200);
        for (int i = 0; i < limit; i++) {
            traceback.add(rawLines[i]);
        }

        // Kiểm tra Python Traceback
        boolean isPythonTb = false;
        for (String line : rawLines) {
            if (line.contains("Traceback (most recent call last):")) {
                isPythonTb = true;
                break;
            }
        }

        if (isPythonTb) {
            // Duyệt từ dòng cuối lên tìm "NameError: name 'x' is not defined"
            for (int i = rawLines.length - 1; i >= 0; i--) {
                String line = rawLines[i].trim();
                Matcher m = PYTHON_ERROR_LINE_PATTERN.matcher(line);
                if (m.find()) {
                    String ename = m.group(1);
                    String evalue = m.group(2);
                    return new ErrorInfo(ename, evalue, traceback);
                }
            }
            // Nếu không khớp regex chính xác, lấy dòng cuối không rỗng
            for (int i = rawLines.length - 1; i >= 0; i--) {
                String line = rawLines[i].trim();
                if (!line.isEmpty()) {
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        return new ErrorInfo(line.substring(0, colonIdx).trim(), line.substring(colonIdx + 1).trim(), traceback);
                    }
                    return new ErrorInfo("PythonError", line, traceback);
                }
            }
            return new ErrorInfo("PythonError", message, traceback);
        }

        // Lỗi SQL / Java exception
        Matcher sqlExMatcher = SQL_EXCEPTION_PATTERN.matcher(message);
        String ename = "SQLException";
        if (sqlExMatcher.find()) {
            ename = sqlExMatcher.group(1);
        }

        // evalue: lấy dòng đầu tiên hoặc message sạch
        String evalue = rawLines.length > 0 ? rawLines[0].trim() : message;
        if (evalue.contains(":")) {
            int idx = evalue.indexOf(':');
            evalue = evalue.substring(idx + 1).trim();
        }

        return new ErrorInfo(ename, evalue.isEmpty() ? message : evalue, traceback);
    }

    /**
     * Tạo text/plain dạng bảng chữ ascii (tối đa 50 dòng) để export .ipynb
     */
    public static String renderPlainAsciiTable(List<Column> columns, List<List<JsonNode>> rows) {
        if (columns == null || columns.isEmpty()) {
            return "";
        }
        int numCols = columns.size();
        int[] colWidths = new int[numCols];

        for (int i = 0; i < numCols; i++) {
            colWidths[i] = Math.max(columns.get(i).columnName().length(), 4);
        }

        int maxRowLimit = Math.min(rows != null ? rows.size() : 0, 50);
        List<String[]> formattedRows = new ArrayList<>();

        for (int r = 0; r < maxRowLimit; r++) {
            List<JsonNode> row = rows.get(r);
            String[] rowTexts = new String[numCols];
            for (int c = 0; c < numCols; c++) {
                String valStr = "null";
                if (c < row.size() && row.get(c) != null && !row.get(c).isNull()) {
                    valStr = row.get(c).isValueNode() ? row.get(c).asText() : row.get(c).toString();
                }
                rowTexts[c] = valStr;
                colWidths[c] = Math.max(colWidths[c], valStr.length());
            }
            formattedRows.add(rowTexts);
        }

        StringBuilder sb = new StringBuilder();
        // Header
        for (int c = 0; c < numCols; c++) {
            sb.append(String.format("%-" + colWidths[c] + "s", columns.get(c).columnName()));
            if (c < numCols - 1) sb.append(" | ");
        }
        sb.append("\n");

        // Separator
        for (int c = 0; c < numCols; c++) {
            sb.append("-".repeat(colWidths[c]));
            if (c < numCols - 1) sb.append("-+-");
        }
        sb.append("\n");

        // Rows
        for (String[] r : formattedRows) {
            for (int c = 0; c < numCols; c++) {
                sb.append(String.format("%-" + colWidths[c] + "s", r[c]));
                if (c < numCols - 1) sb.append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
