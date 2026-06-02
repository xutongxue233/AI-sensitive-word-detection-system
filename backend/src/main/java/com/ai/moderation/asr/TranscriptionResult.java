package com.ai.moderation.asr;

import java.util.List;

public record TranscriptionResult(List<TranscriptionSegment> segments) {
}

