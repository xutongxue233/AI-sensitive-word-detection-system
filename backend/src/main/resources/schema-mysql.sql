CREATE TABLE IF NOT EXISTS term_categories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS violation_terms (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    term VARCHAR(200) NOT NULL,
    category VARCHAR(80),
    severity VARCHAR(20) NOT NULL,
    match_type VARCHAR(20) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    variants TEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_terms_enabled (enabled),
    KEY idx_terms_match_type (match_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS videos (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1000) NOT NULL,
    subtitle_path VARCHAR(1000),
    duration_seconds DOUBLE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS detection_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    video_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2000),
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_jobs_video (video_id),
    CONSTRAINT fk_jobs_video FOREIGN KEY (video_id) REFERENCES videos(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS transcript_segments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    start_time DOUBLE NOT NULL,
    end_time DOUBLE NOT NULL,
    text TEXT NOT NULL,
    KEY idx_segments_job (job_id),
    CONSTRAINT fk_segments_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS transcript_words (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    word VARCHAR(120) NOT NULL,
    normalized_word VARCHAR(120) NOT NULL,
    start_time DOUBLE NOT NULL,
    end_time DOUBLE NOT NULL,
    KEY idx_words_job (job_id),
    KEY idx_words_segment (segment_id),
    CONSTRAINT fk_words_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id),
    CONSTRAINT fk_words_segment FOREIGN KEY (segment_id) REFERENCES transcript_segments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS term_hits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    term_id BIGINT,
    segment_id BIGINT NOT NULL,
    matched_text VARCHAR(200) NOT NULL,
    category VARCHAR(80),
    severity VARCHAR(20) NOT NULL,
    rule_source VARCHAR(20) NOT NULL,
    start_time DOUBLE NOT NULL,
    end_time DOUBLE NOT NULL,
    context_text TEXT,
    review_status VARCHAR(20) NOT NULL,
    ai_confidence DOUBLE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_hits_job (job_id),
    KEY idx_hits_segment (segment_id),
    CONSTRAINT fk_hits_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id),
    CONSTRAINT fk_hits_term FOREIGN KEY (term_id) REFERENCES violation_terms(id),
    CONSTRAINT fk_hits_segment FOREIGN KEY (segment_id) REFERENCES transcript_segments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hit_id BIGINT NOT NULL UNIQUE,
    violation TINYINT(1) NOT NULL,
    confidence DOUBLE,
    category VARCHAR(80),
    reason VARCHAR(1000),
    raw_response TEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_ai_reviews_hit FOREIGN KEY (hit_id) REFERENCES term_hits(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS clip_suggestions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    hit_id BIGINT NOT NULL,
    start_time DOUBLE NOT NULL,
    end_time DOUBLE NOT NULL,
    padding_seconds DOUBLE NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL,
    export_path VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_clips_job (job_id),
    CONSTRAINT fk_clips_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id),
    CONSTRAINT fk_clips_hit FOREIGN KEY (hit_id) REFERENCES term_hits(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
