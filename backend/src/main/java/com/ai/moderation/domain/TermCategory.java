package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("term_categories")
public class TermCategory implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Instant createdAt = Instant.now();
}
