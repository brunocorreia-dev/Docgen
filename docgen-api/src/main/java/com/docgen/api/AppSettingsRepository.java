package com.docgen.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Key-value preferences per owner ({@code app_settings}, migration V4). */
@Repository
class AppSettingsRepository {
    static final String DEFAULT_MODEL = "default_model";
    static final String ONBOARDING_COMPLETED = "onboarding_completed";

    private final JdbcClient jdbc;

    AppSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> get(String owner, String key) {
        return jdbc.sql("SELECT setting_value FROM app_settings WHERE owner = :owner AND setting_key = :key")
                .param("owner", owner).param("key", key)
                .query(String.class).optional();
    }

    void put(String owner, String key, String value, Instant now) {
        jdbc.sql("""
                        MERGE INTO app_settings (owner, setting_key, setting_value, updated_at)
                        KEY (owner, setting_key)
                        VALUES (:owner, :key, :value, :updated)
                        """)
                .param("owner", owner).param("key", key).param("value", value)
                .param("updated", ProjectRepository.time(now))
                .update();
    }

    void remove(String owner, String key) {
        jdbc.sql("DELETE FROM app_settings WHERE owner = :owner AND setting_key = :key")
                .param("owner", owner).param("key", key).update();
    }
}
