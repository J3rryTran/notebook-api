package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KyuubiResultParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KyuubiResultParser parser = new KyuubiResultParser();

    @Test
    void testParseColumns() throws Exception {
        String metadataJson = """
                {
                  "columns": [
                    {"columnName": "id", "dataType": "INT_TYPE", "precision": 0, "scale": 0, "comment": ""},
                    {"columnName": "name", "dataType": "STRING_TYPE", "precision": null, "scale": null, "comment": "username"}
                  ]
                }
                """;
        JsonNode metaNode = mapper.readTree(metadataJson);
        List<KyuubiResultParser.Column> cols = parser.columns(metaNode);

        assertThat(cols).hasSize(2);
        assertThat(cols.get(0).columnName()).isEqualTo("id");
        assertThat(cols.get(0).dataType()).isEqualTo("INT_TYPE");
        assertThat(cols.get(0).precision()).isEqualTo(0);
        assertThat(cols.get(1).columnName()).isEqualTo("name");
        assertThat(cols.get(1).comment()).isEqualTo("username");
    }

    @Test
    void testParseRowsWithNullAndEmpty() throws Exception {
        String emptyRowset = "{\"rows\": [], \"rowCount\": 0}";
        List<List<JsonNode>> emptyRows = parser.rows(mapper.readTree(emptyRowset));
        assertThat(emptyRows).isEmpty();

        String rowsetJson = """
                {
                  "rows": [
                    {
                      "fields": [
                        {"dataType": "INT_TYPE", "value": 1},
                        {"dataType": "STRING_TYPE", "value": "alice"},
                        {"dataType": "BOOLEAN_TYPE", "value": true}
                      ]
                    },
                    {
                      "fields": [
                        {"dataType": "INT_TYPE", "value": null},
                        {"dataType": "STRING_TYPE", "value": null},
                        {"dataType": "BOOLEAN_TYPE", "value": false}
                      ]
                    }
                  ],
                  "rowCount": 2
                }
                """;
        List<List<JsonNode>> rows = parser.rows(mapper.readTree(rowsetJson));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(0).asInt()).isEqualTo(1);
        assertThat(rows.get(0).get(1).asText()).isEqualTo("alice");
        assertThat(rows.get(0).get(2).asBoolean()).isTrue();

        assertThat(rows.get(1).get(0).isNull()).isTrue();
        assertThat(rows.get(1).get(1).isNull()).isTrue();
        assertThat(rows.get(1).get(2).asBoolean()).isFalse();
    }

    @Test
    void testParseLogLines() throws Exception {
        String logJson = """
                {
                  "logRowSet": ["Starting query execution", "Stage 1 finished in 120ms"],
                  "rowCount": 2
                }
                """;
        List<String> lines = parser.logLines(mapper.readTree(logJson));
        assertThat(lines).containsExactly("Starting query execution", "Stage 1 finished in 120ms");
    }

    @Test
    void testParsePythonTracebackNameError() {
        String pythonTraceback = """
                Traceback (most recent call last):
                  File "<stdin>", line 1, in <module>
                NameError: name 'undefined_var' is not defined
                """;
        KyuubiResultParser.ErrorInfo errorInfo = parser.error(pythonTraceback);
        assertThat(errorInfo.ename()).isEqualTo("NameError");
        assertThat(errorInfo.evalue()).isEqualTo("name 'undefined_var' is not defined");
        assertThat(errorInfo.traceback()).hasSize(3);
    }

    @Test
    void testParseSqlAnalysisException() {
        String sqlError = """
                org.apache.spark.sql.catalyst.analysis.TableNotFoundException: Table or view not found: default.non_existent_table;
                line 1 pos 14;
                'Project [*]
                +- 'UnresolvedRelation [default, non_existent_table], [], false
                """;
        KyuubiResultParser.ErrorInfo errorInfo = parser.error(sqlError);
        assertThat(errorInfo.ename()).isEqualTo("org.apache.spark.sql.catalyst.analysis.TableNotFoundException");
        assertThat(errorInfo.evalue()).contains("Table or view not found");
        assertThat(errorInfo.traceback()).isNotEmpty();
    }

    @Test
    void testRenderPlainAsciiTable() {
        List<KyuubiResultParser.Column> cols = List.of(
                new KyuubiResultParser.Column("id", "INT_TYPE", 0, 0, ""),
                new KyuubiResultParser.Column("name", "STRING_TYPE", null, null, "")
        );
        List<List<JsonNode>> rows = List.of(
                List.of(mapper.getNodeFactory().numberNode(1), mapper.getNodeFactory().textNode("Alice")),
                List.of(mapper.getNodeFactory().nullNode(), mapper.getNodeFactory().textNode("Bob"))
        );

        String table = KyuubiResultParser.renderPlainAsciiTable(cols, rows);
        assertThat(table).contains("id");
        assertThat(table).contains("name");
        assertThat(table).contains("Alice");
        assertThat(table).contains("null");
    }
}
