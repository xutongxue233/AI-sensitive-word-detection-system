package com.ai.moderation.domain;

public enum JobStatus {
    QUEUED,
    EXTRACTING_AUDIO,
    TRANSCRIBING,
    MATCHING_TERMS,
    AI_REVIEWING,
    SUGGESTING_CLIPS,
    COMPLETED,
    FAILED
}

