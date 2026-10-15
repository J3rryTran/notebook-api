package com.vtnexus.notebook.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesBindingTest {

    @Test
    void notebookImportMaxSizeBindsDespiteJavaKeyword() {
        NotebookProperties props = new NotebookProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "notebook.import.max-size", "1234",
                "notebook.document.max-size", "5678")))
                .bind("notebook", Bindable.ofInstance(props));

        assertThat(props.getImport().getMaxSize()).isEqualTo(1234);
        assertThat(props.getDocument().getMaxSize()).isEqualTo(5678);
    }

    @Test
    void kyuubiIdleSettingsBind() {
        KyuubiProperties props = new KyuubiProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "kyuubi.idle-session-timeout", "0s",
                "kyuubi.idle-reaper-interval", "60000")))
                .bind("kyuubi", Bindable.ofInstance(props));

        assertThat(props.getIdleSessionTimeout()).isEqualTo(Duration.ZERO);
        assertThat(props.getIdleReaperInterval()).isEqualTo(60000L);
    }
}
