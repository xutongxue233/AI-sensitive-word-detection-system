package com.ai.moderation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 词库生成请求。用户用自然语言描述希望覆盖的违规类型,模型返回可人工确认的候选词条。
 *
 * @param prompt   自然语言需求描述
 * @param category 可选的目标分类提示
 * @param count    期望生成数量,服务端限制 1~50
 */
public record TermGenerationRequest(
        @NotBlank @Size(max = 1000) String prompt,
        @Size(max = 80) String category,
        @Min(1) @Max(50) Integer count
) {
}
