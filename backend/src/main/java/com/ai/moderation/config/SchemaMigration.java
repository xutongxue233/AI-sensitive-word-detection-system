package com.ai.moderation.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public SchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("transcript_segments", "source", "VARCHAR(20) NOT NULL DEFAULT 'AUDIO'");
        addColumnIfMissing("transcript_segments", "bbox_x", "DOUBLE");
        addColumnIfMissing("transcript_segments", "bbox_y", "DOUBLE");
        addColumnIfMissing("transcript_segments", "bbox_width", "DOUBLE");
        addColumnIfMissing("transcript_segments", "bbox_height", "DOUBLE");
        addColumnIfMissing("term_hits", "source", "VARCHAR(20) NOT NULL DEFAULT 'AUDIO'");
        addColumnIfMissing("videos", "size_bytes", "BIGINT");
        addColumnIfMissing("videos", "content_type", "VARCHAR(100)");
        addColumnIfMissing("videos", "width", "INT");
        addColumnIfMissing("videos", "height", "INT");
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND column_name = ?
                        """,
                Integer.class,
                tableName,
                columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }
}
