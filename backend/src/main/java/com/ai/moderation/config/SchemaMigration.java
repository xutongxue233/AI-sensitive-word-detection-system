package com.ai.moderation.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时对已有数据库做幂等的增量补列迁移。
 *
 * <p>{@code schema-mysql.sql} 仅 {@code CREATE TABLE IF NOT EXISTS}——对已存在的库不会补新列。
 * 因此<b>所有后续新增的列一律加在本类</b>:通过查询 {@code information_schema.columns} 判断列是否已存在,
 * 缺失才 {@code ALTER TABLE ADD COLUMN},可重复运行而不报错。
 *
 * <p><b>不要</b>改 {@code schema-mysql.sql} 期望它对已有库生效。
 */
@Component
public class SchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public SchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
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

    /**
     * 当目标列不存在时补加,已存在则跳过(幂等)。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @param definition 列定义片段(类型 + 约束 + 默认值),直接拼入 ALTER 语句
     */
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
