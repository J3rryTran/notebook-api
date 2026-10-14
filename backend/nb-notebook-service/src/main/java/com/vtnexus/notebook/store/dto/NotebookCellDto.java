package com.vtnexus.notebook.store.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotebookCellDto {
    private String id;
    private String cellType;
    private String language;
    private String source;
    private Integer position;
    private Map<String, Object> metadata;
}
