package com.vtnexus.notebook.store.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotebookDocument {

    @Builder.Default
    private int schemaVersion = 1;

    @Builder.Default
    private String defaultLanguage = "SQL";

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Builder.Default
    private List<Object> parameters = new ArrayList<>();

    @Builder.Default
    private List<CellItem> cells = new ArrayList<>();

    private final Map<String, Object> extraProperties = new LinkedHashMap<>();

    @JsonAnySetter
    public void addExtraProperty(String key, Object value) {
        extraProperties.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtraProperties() {
        return extraProperties;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CellItem {
        private String id;

        @Builder.Default
        private String type = "CODE";

        @Builder.Default
        private String language = "SQL";

        @Builder.Default
        private String source = "";

        @Builder.Default
        private Map<String, Object> metadata = new LinkedHashMap<>();

        private List<Object> outputs;

        private Integer executionCount;

        private final Map<String, Object> extraCellProperties = new LinkedHashMap<>();

        @JsonAnySetter
        public void addExtraCellProperty(String key, Object value) {
            extraCellProperties.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtraCellProperties() {
            return extraCellProperties;
        }
    }
}
