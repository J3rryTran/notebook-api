package com.vtnexus.notebook.common.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceGuardTest {

    @Test
    @DisplayName("Chấp nhận URL bắt đầu bằng jdbc:mariadb:")
    void testValidMariaDbUrl() {
        assertDoesNotThrow(() -> DatasourceGuard.validateUrl("jdbc:mariadb://10.29.19.52:3306/test?characterEncoding=utf8"));
    }

    @Test
    @DisplayName("Từ chối URL postgresql")
    void testPostgresUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DatasourceGuard.validateUrl("jdbc:postgresql://localhost:5432/notebook"));
        assertTrue(ex.getMessage().contains(DatasourceGuard.ERROR_MESSAGE));
    }

    @Test
    @DisplayName("Từ chối URL h2")
    void testH2Url() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DatasourceGuard.validateUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"));
        assertTrue(ex.getMessage().contains(DatasourceGuard.ERROR_MESSAGE));
    }

    @Test
    @DisplayName("Từ chối URL mysql")
    void testMysqlUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DatasourceGuard.validateUrl("jdbc:mysql://localhost:3306/test"));
        assertTrue(ex.getMessage().contains(DatasourceGuard.ERROR_MESSAGE));
    }

    @Test
    @DisplayName("Từ chối URL sqlite")
    void testSqliteUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DatasourceGuard.validateUrl("jdbc:sqlite:test.db"));
        assertTrue(ex.getMessage().contains(DatasourceGuard.ERROR_MESSAGE));
    }

    @Test
    @DisplayName("Từ chối URL rỗng hoặc null")
    void testNullOrEmptyUrl() {
        assertThrows(IllegalStateException.class, () -> DatasourceGuard.validateUrl(null));
        assertThrows(IllegalStateException.class, () -> DatasourceGuard.validateUrl("   "));
    }
}
