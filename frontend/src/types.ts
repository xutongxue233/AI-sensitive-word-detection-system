export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type MatchType = 'EXACT' | 'VARIANT' | 'REGEX' | 'SEMANTIC';
export type VideoStatus = 'UPLOADED' | 'DETECTING' | 'DETECTED' | 'EXPORTED' | 'FAILED';
export type JobStatus =
  | 'QUEUED'
  | 'EXTRACTING_AUDIO'
  | 'TRANSCRIBING'
  | 'MATCHING_TERMS'
  | 'AI_REVIEWING'
  | 'SUGGESTING_CLIPS'
  | 'COMPLETED'
  | 'FAILED';
export type ReviewStatus = 'PENDING' | 'VIOLATION' | 'SAFE' | 'CONFIRMED' | 'IGNORED';
export type ClipStatus = 'PENDING' | 'CONFIRMED' | 'IGNORED' | 'EXPORTED';

export interface ViolationTerm {
  id: number;
  term: string;
  category?: string;
  severity: Severity;
  matchType: MatchType;
  enabled: boolean;
  variants?: string;
  createdAt: string;
  updatedAt: string;
}

export interface VideoFile {
  id: number;
  originalFilename: string;
  storedFilename: string;
  storagePath: string;
  subtitlePath?: string;
  durationSeconds?: number;
  status: VideoStatus;
  createdAt: string;
}

export interface DetectionJob {
  id: number;
  videoId: number;
  status: JobStatus;
  progress: number;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
}

export interface TranscriptWord {
  id: number;
  sequenceNo: number;
  word: string;
  startTime: number;
  endTime: number;
}

export interface TranscriptSegment {
  id: number;
  sequenceNo: number;
  startTime: number;
  endTime: number;
  text: string;
  words: TranscriptWord[];
}

export interface AiReview {
  id: number;
  violation: boolean;
  confidence?: number;
  category?: string;
  reason?: string;
}

export interface TermHit {
  id: number;
  segmentId: number;
  termId?: number;
  matchedText: string;
  category?: string;
  severity: Severity;
  ruleSource: MatchType;
  startTime: number;
  endTime: number;
  contextText?: string;
  reviewStatus: ReviewStatus;
  aiConfidence?: number;
  aiReview?: AiReview;
}

export interface TimelineItem {
  hitId: number;
  matchedText: string;
  category?: string;
  severity: Severity;
  reviewStatus: ReviewStatus;
  startTime: number;
  endTime: number;
  contextText?: string;
}

export interface ClipSuggestion {
  id: number;
  hitId: number;
  matchedText: string;
  startTime: number;
  endTime: number;
  paddingSeconds: number;
  status: ClipStatus;
  exportPath?: string;
}

export interface ExportResult {
  videoId: number;
  jobId: number;
  exportPath: string;
  removedClipCount: number;
}

export interface TermImportResult {
  importedCount: number;
  skippedCount: number;
}
