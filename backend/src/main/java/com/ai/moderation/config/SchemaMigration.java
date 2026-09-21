package com.ai.moderation.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 启动时对已有数据库做幂等的增量补列迁移。
 *
 * <p>schema 脚本仅 {@code CREATE TABLE IF NOT EXISTS}——对已存在的库不会补新列。
 * 因此<b>所有后续新增的列一律加在本类</b>:按当前数据库类型判断列是否已存在,
 * 缺失才 {@code ALTER TABLE ADD COLUMN},可重复运行而不报错。MySQL 走
 * {@code information_schema.columns},SQLite 走 {@code PRAGMA table_info(...)}。
 *
 * <p><b>不要</b>只改 schema 脚本就期望它对已有库生效。
 */
@Component
public class SchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public SchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        createExportTasksTableIfMissing();
        // 转写段:音/画双源标识 + 画面硬字幕的归一化 bbox(0~1,供 delogo 去字幕定位)
        addColumnIfMissing("transcript_segments", "source", "VARCHAR(20) NOT NULL DEFAULT 'AUDIO'");
        addColumnIfMissing("transcript_segments", "bbox_x", "DOUBLE");
        addColumnIfMissing("transcript_segments", "bbox_y", "DOUBLE");
        addColumnIfMissing("transcript_segments", "bbox_width", "DOUBLE");
        addColumnIfMissing("transcript_segments", "bbox_height", "DOUBLE");
        // 命中项:音/画双源标识,决定导出走删片段还是去字幕
        addColumnIfMissing("term_hits", "source", "VARCHAR(20) NOT NULL DEFAULT 'AUDIO'");
        // 视频:文件元信息与分辨率(去字幕探测分辨率失败时需要)
        addColumnIfMissing("videos", "size_bytes", "BIGINT");
        addColumnIfMissing("videos", "content_type", "VARCHAR(100)");
        addColumnIfMissing("videos", "width", "INT");
        addColumnIfMissing("videos", "height", "INT");
    }

    private void createExportTasksTableIfMissing() {
        if ("sqlite".equals(databaseKind())) {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS export_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        video_id INTEGER NOT NULL,
                        job_id INTEGER,
                        status TEXT NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        export_path TEXT,
                        removed_clip_count INTEGER,
                        error_message TEXT,
                        started_at TIMESTAMP,
                        completed_at TIMESTAMP,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_export_tasks_video FOREIGN KEY (video_id) REFERENCES videos(id),
                        CONSTRAINT fk_export_tasks_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id)
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_export_tasks_video ON export_tasks (video_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_export_tasks_status ON export_tasks (status, created_at)");
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS export_tasks (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    video_id BIGINT NOT NULL,
                    job_id BIGINT,
                    status VARCHAR(20) NOT NULL,
                    progress INT NOT NULL DEFAULT 0,
                    export_path VARCHAR(1000),
                    removed_clip_count INT,
                    error_message TEXT,
                    started_at TIMESTAMP(6),
                    completed_at TIMESTAMP(6),
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    KEY idx_export_tasks_video (video_id),
                    KEY idx_export_tasks_status (status, created_at),
                    CONSTRAINT fk_export_tasks_video FOREIGN KEY (video_id) REFERENCES videos(id),
                    CONSTRAINT fk_export_tasks_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    /**
     * 当目标列不存在时补加,已存在则跳过(幂等)。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @param definition 列定义片段(类型 + 约束 + 默认值),直接拼入 ALTER 语句
     */
    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (!columnExists(tableName, columnName)) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        return switch (databaseKind()) {
            case "sqlite" -> sqliteColumnExists(tableName, columnName);
            case "mysql" -> mysqlColumnExists(tableName, columnName);
            default -> mysqlColumnExists(tableName, columnName);
        };
    }

    private boolean mysqlColumnExists(String tableName, String columnName) {
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
        return count != null && count > 0;
    }

    private boolean sqliteColumnExists(String tableName, String columnName) {
        validateIdentifier(tableName);
        validateIdentifier(columnName);
        return jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")")
                .stream()
                .map(row -> row.get("name"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(name -> name.equalsIgnoreCase(columnName));
    }

    private String databaseKind() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("sqlite")) {
                return "sqlite";
            }
            if (product.contains("mysql") || product.contains("mariadb")) {
                return "mysql";
            }
            return product;
        } catch (SQLException ex) {
            throw new IllegalStateException("无法识别数据库类型", ex);
        }
    }

    private void validateIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法数据库标识符: " + value);
        }
    }
}
