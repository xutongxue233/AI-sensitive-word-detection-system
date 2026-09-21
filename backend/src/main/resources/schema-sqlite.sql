PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS term_categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS violation_terms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    term TEXT NOT NULL,
    category TEXT,
    severity TEXT NOT NULL,
    match_type TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    variants TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_terms_enabled ON violation_terms (enabled);
CREATE INDEX IF NOT EXISTS idx_terms_match_type ON violation_terms (match_type);

CREATE TABLE IF NOT EXISTS videos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    original_filename TEXT NOT NULL,
    stored_filename TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    subtitle_path TEXT,
    duration_seconds REAL,
    size_bytes INTEGER,
    content_type TEXT,
    width INTEGER,
    height INTEGER,
    status TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS detection_jobs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    video_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_jobs_video FOREIGN KEY (video_id) REFERENCES videos(id)
);
CREATE INDEX IF NOT EXISTS idx_jobs_video ON detection_jobs (video_id);

CREATE TABLE IF NOT EXISTS transcript_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL,
    start_time REAL NOT NULL,
    end_time REAL NOT NULL,
    text TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'AUDIO',
    bbox_x REAL,
    bbox_y REAL,
    bbox_width REAL,
    bbox_height REAL,
    CONSTRAINT fk_segments_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id)
);
CREATE INDEX IF NOT EXISTS idx_segments_job ON transcript_segments (job_id);

CREATE TABLE IF NOT EXISTS transcript_words (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    segment_id INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL,
    word TEXT NOT NULL,
    normalized_word TEXT NOT NULL,
    start_time REAL NOT NULL,
    end_time REAL NOT NULL,
    CONSTRAINT fk_words_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id),
    CONSTRAINT fk_words_segment FOREIGN KEY (segment_id) REFERENCES transcript_segments(id)
);
CREATE INDEX IF NOT EXISTS idx_words_job ON transcript_words (job_id);
CREATE INDEX IF NOT EXISTS idx_words_segment ON transcript_words (segment_id);

CREATE TABLE IF NOT EXISTS term_hits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    term_id INTEGER,
    segment_id INTEGER NOT NULL,
    matched_text TEXT NOT NULL,
    category TEXT,
    severity TEXT NOT NULL,
    rule_source TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'AUDIO',
    start_time REAL NOT NULL,
    end_time REAL NOT NULL,
    context_text TEXT,
    review_status TEXT NOT NULL,
    ai_confidence REAL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_hits_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id),
    CONSTRAINT fk_hits_term FOREIGN KEY (term_id) REFERENCES violation_terms(id),
    CONSTRAINT fk_hits_segment FOREIGN KEY (segment_id) REFERENCES transcript_segments(id)
);
CREATE INDEX IF NOT EXISTS idx_hits_job ON term_hits (job_id);
CREATE INDEX IF NOT EXISTS idx_hits_segment ON term_hits (segment_id);

CREATE TABLE IF NOT EXISTS ai_reviews (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hit_id INTEGER NOT NULL UNIQUE,
    violation INTEGER NOT NULL,
    confidence REAL,
    category TEXT,
    reason TEXT,
    raw_response TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_reviews_hit FOREIGN KEY (hit_id) REFERENCES term_hits(id)
);

CREATE TABLE IF NOT EXISTS app_settings (
    id INTEGER PRIMARY KEY,
    ai_enabled INTEGER NOT NULL DEFAULT 0,
    ai_api_type TEXT NOT NULL DEFAULT 'CHAT',
    ai_base_url TEXT,
    ai_api_key TEXT,
    ai_model TEXT,
    ai_temperature REAL NOT NULL DEFAULT 0,
    ai_confidence_threshold REAL NOT NULL DEFAULT 0.6,
    ai_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    clip_padding_seconds REAL NOT NULL DEFAULT 0.2,
    clip_precise_export INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS clip_suggestions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    hit_id INTEGER NOT NULL,
    start_time REAL NOT NULL,
    end_time REAL NOT NULL,
    padding_seconds REAL NOT NULL DEFAULT 1,
    status TEXT NOT NULL,
    export_path TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_clips_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id),
    CONSTRAINT fk_clips_hit FOREIGN KEY (hit_id) REFERENCES term_hits(id)
);
CREATE INDEX IF NOT EXISTS idx_clips_job ON clip_suggestions (job_id);

CREATE TABLE IF NOT EXISTS export_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    video_id INTEGER NOT NULL,
    job_id INTEGER,
    status TEXT NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    export_path TEXT,
    removed_clip_count INTEGER,
    error_message TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_export_tasks_video FOREIGN KEY (video_id) REFERENCES videos(id),
    CONSTRAINT fk_export_tasks_job FOREIGN KEY (job_id) REFERENCES detection_jobs(id)
);
CREATE INDEX IF NOT EXISTS idx_export_tasks_video ON export_tasks (video_id);
CREATE INDEX IF NOT EXISTS idx_export_tasks_status ON export_tasks (status, created_at);
