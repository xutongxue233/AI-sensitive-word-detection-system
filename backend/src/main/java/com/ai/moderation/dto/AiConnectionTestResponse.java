package com.ai.moderation.dto;

/**
 * AI 连接测试响应。对应 {@link AiConnectionTestRequest} 的探活结果,
 * 供前端「测试连接」即时展示成败与往返耗时。
 *
 * @param ok        是否连通成功
 * @param message   结果说明(成功提示或失败原因)
 * @param model     实际响应的模型名(用于核对端点返回是否与请求一致)
 * @param elapsedMs 一次探活请求的往返耗时(毫秒)
 */
public record AiConnectionTestResponse(
        boolean ok,
        String message,
        String model,
        long elapsedMs
) {
}
