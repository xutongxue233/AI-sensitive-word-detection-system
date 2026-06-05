package com.ai.moderation.service.support;

/**
 * 字幕遮盖区域:时间段 + 归一化矩形框(取值 0~1,相对视频宽高)。
 * 用于对画面硬字幕命中做 delogo 去字幕;{@link #expanded()} 在原框基础上外扩一圈,
 * 覆盖字幕描边与抗锯齿边缘。
 *
 * @param start  遮盖起始秒
 * @param end    遮盖结束秒
 * @param x      矩形左上角 x(归一化 0~1)
 * @param y      矩形左上角 y(归一化 0~1)
 * @param width  矩形宽(归一化 0~1)
 * @param height 矩形高(归一化 0~1)
 */
public record SubtitleMaskRange(
        double start,
        double end,
        double x,
        double y,
        double width,
        double height
) {
    /** 外扩矩形框的归一化横向边距(覆盖字幕左右描边) */
    private static final double PADDING_X = 0.04;
    /** 外扩矩形框的归一化纵向边距(覆盖字幕上下描边) */
    private static final double PADDING_Y = 0.022;

    /**
     * 在原矩形框四周外扩一圈并 clamp 到 [0,1] 区间,覆盖字幕描边/抗锯齿边缘;
     * 同时把时间段规整为非负,且结束至少比起始大 0.1 秒。
     *
     * @return 外扩后的遮盖区域
     */
    public SubtitleMaskRange expanded() {
        double nx = clamp(x - PADDING_X, 0, 0.98);
        double ny = clamp(y - PADDING_Y, 0, 0.98);
        double right = clamp(x + width + PADDING_X, 0.02, 1);
        double bottom = clamp(y + height + PADDING_Y, 0.02, 1);
        double nw = Math.max(0.02, right - nx);
        double nh = Math.max(0.02, bottom - ny);
        return new SubtitleMaskRange(Math.max(0, start), Math.max(start + 0.1, end), nx, ny, nw, nh);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
