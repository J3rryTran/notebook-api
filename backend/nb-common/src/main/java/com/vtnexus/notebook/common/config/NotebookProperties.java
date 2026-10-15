package com.vtnexus.notebook.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notebook")
public class NotebookProperties {

    private String publicUrl = "";
    private List<String> admins = new ArrayList<>();
    private String internalToken = "";
    private String encryptionKey = "";
    private String notebookServiceUrl = "http://127.0.0.1:8084";

    private MaxProperties max = new MaxProperties();
    private CellProperties cell = new CellProperties();
    private DocumentProperties document = new DocumentProperties();
    // "import" là từ khóa Java nên field đặt tên khác; getImport()/setImport() bên dưới để Spring bind đúng
    // khóa notebook.import.* (Lombok sinh getAnImport() thì Spring chỉ bind notebook.an-import.*).
    private ImportProperties anImport = new ImportProperties();
    private RevisionProperties revision = new RevisionProperties();
    private ResultsProperties results = new ResultsProperties();
    private EventsProperties events = new EventsProperties();

    public ImportProperties getImport() {
        return anImport;
    }

    public void setImport(ImportProperties value) {
        this.anImport = value;
    }

    @Getter
    @Setter
    public static class MaxProperties {
        private int cells = 500;
        private int pageSize = 200;
    }

    @Getter
    @Setter
    public static class CellProperties {
        private SourceProperties source = new SourceProperties();

        @Getter
        @Setter
        public static class SourceProperties {
            private int maxSize = 1048576; // 1Mi
        }
    }

    @Getter
    @Setter
    public static class DocumentProperties {
        private int maxSize = 16777216; // 16Mi
    }

    @Getter
    @Setter
    public static class ImportProperties {
        private int maxSize = 16777216; // 16Mi
    }

    @Getter
    @Setter
    public static class RevisionProperties {
        private int maxPerNotebook = 100;
    }

    @Getter
    @Setter
    public static class ResultsProperties {
        private int maxRows = 1000;
    }

    @Getter
    @Setter
    public static class EventsProperties {
        private int poolSize = 16;
        private Duration maxWait = Duration.ofSeconds(10);
    }
}
