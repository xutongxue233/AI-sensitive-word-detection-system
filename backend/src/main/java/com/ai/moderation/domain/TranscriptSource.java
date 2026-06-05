package com.ai.moderation.domain;

/**
 * 转写文本的来源。该来源决定导出时的处理方式,并参与转写合并去重。
 *
 * <p>同一时段不同来源即便文本相同也各保留一条:导出处理方式不同
 * (音频命中删时间片段,画面硬字幕走 delogo 去字幕)。</p>
 */
public enum TranscriptSource {
    /** 音频:Whisper ASR 转写,命中导出时删除对应时间片段。 */
    AUDIO,
    /** 外部字幕文件:用户上传的 srt/vtt 解析所得。 */
    SUBTITLE_FILE,
    /** 画面硬字幕:PaddleOCR 识别所得,命中导出时走 ffmpeg delogo 去字幕。 */
    VIDEO_SUBTITLE
}
