package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 敏感词分类(表 {@code term_categories}),供 {@link ViolationTerm} 按业务主题归类。
 */
@Getter
@Setter
@TableName("term_categories")
public class TermCategory implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 创建时间:构造即填入默认值,落库前无需显式赋值。 */
    private Instant createdAt = Instant.now();
}
