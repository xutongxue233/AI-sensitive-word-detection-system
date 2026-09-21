/**
 * 前端领域类型契约。
 *
 * 各类型对应后端 `com.ai.moderation` 的 domain 实体与 dto;字段一律驼峰命名,
 * 与后端序列化出的 JSON 键名对齐。改动后端 DTO/实体字段时,须同步更新此处,
 * 否则前端拿到的对象会与类型声明不符。
 */
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type MatchType = 'EXACT' | 'VARIANT' | 'REGEX' | 'SEMANTIC';
export type VideoStatus = 'UPLOADED' | 'DETECTING' | 'DETECTED' | 'EXPORTED' | 'FAILED';
export type HealthStatus = 'OK' | 'WARN' | 'DOWN';
export type ExportTaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
/**
 * 转写来源,决定该条文本在导出阶段的处理方式(同后端 `TranscriptSource` 枚举):
 * - `AUDIO`:语音转写命中 → 导出时删除对应音频时间片段
 * - `SUBTITLE_FILE`:外部字幕文件,作为辅助文本来源
 * - `VIDEO_SUBTITLE`:画面硬字幕(OCR)命中 → 导出时用 delogo 去字幕
 */
export type TranscriptSource = 'AUDIO' | 'SUBTITLE_FILE' | 'VIDEO_SUBTITLE';
/**
 * 检测任务状态,对应后端管线各阶段(同 `JobStatus` 枚举),前端据此展示进度:
 * - `QUEUED`:已入队,待执行
 * - `EXTRACTING_AUDIO`:抽音频(音画两腿并行起点)
 * - `TRANSCRIBING`:转写落库
 * - `MATCHING_TERMS`:规则召回候选
 * - `AI_REVIEWING`:AI 上下文复核
 * - `SUGGESTING_CLIPS`:生成剪辑/去字幕建议
 * - `COMPLETED`:完成
 * - `FAILED`:失败(音频腿失败即整体失败,不降级)
 */
export type JobStatus =
  | 'QUEUED'
  | 'EXTRACTING_AUDIO'
  | 'TRANSCRIBING'
  | 'MATCHING_TERMS'
  | 'AI_REVIEWING'
  | 'SUGGESTING_CLIPS'
  | 'COMPLETED'
  | 'FAILED';
/**
 * 命中复核状态(同后端 `ReviewStatus` 枚举),区分 AI 自动判定与人工干预:
 * - `PENDING`:待复核(尚未判定)
 * - `VIOLATION`:AI 自动判定为违规(violation 且置信度达阈值)
 * - `SAFE`:AI 自动判定为安全(保留记录与原因,但不自动生成剪辑)
 * - `CONFIRMED`:人工确认违规
 * - `IGNORED`:人工忽略
 */
export type ReviewStatus = 'PENDING' | 'VIOLATION' | 'SAFE' | 'CONFIRMED' | 'IGNORED';
/**
 * 剪辑建议生命周期(同后端 `ClipStatus` 枚举):
 * - `PENDING`:待确认
 * - `CONFIRMED`:已确认(将参与导出)
 * - `IGNORED`:已忽略
 * - `EXPORTED`:已导出
 */
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

export interface GeneratedTerm {
  term: string;
  category?: string;
  severity: Severity;
  matchType: MatchType;
  variants?: string;
  reason?: string;
}

export interface TermGenerationRequest {
  prompt: string;
  category?: string;
  count?: number;
}

export interface VideoFile {
  id: number;
  originalFilename: string;
  storedFilename: string;
  storagePath: string;
  subtitlePath?: string;
  durationSeconds?: number;
  sizeBytes?: number;
  contentType?: string;
  width?: number;
  height?: number;
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
  source: TranscriptSource;
  bboxX?: number; // OCR 写入的归一化(0~1)字幕框左上角 X,仅 VIDEO_SUBTITLE 有值,供 delogo 定位
  bboxY?: number; // 归一化(0~1)字幕框左上角 Y,仅 VIDEO_SUBTITLE 有值
  bboxWidth?: number; // 归一化(0~1)字幕框宽度,仅 VIDEO_SUBTITLE 有值
  bboxHeight?: number; // 归一化(0~1)字幕框高度,仅 VIDEO_SUBTITLE 有值
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
  source: TranscriptSource;
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
  source: TranscriptSource;
  reviewStatus: ReviewStatus;
  startTime: number;
  endTime: number;
  contextText?: string;
  aiConfidence?: number;
  aiReason?: string;
}

export interface ClipSuggestion {
  id: number;
  hitId: number;
  matchedText: string;
  source: TranscriptSource;
  action: 'REMOVE_AUDIO_SEGMENT' | 'BLUR_SUBTITLE'; // 处置动作:删音频段(AUDIO)或去字幕(VIDEO_SUBTITLE)
  startTime: number;
  endTime: number;
  segmentStartTime?: number | null;
  segmentEndTime?: number | null;
  paddingSeconds: number; // 命中时段前后各扩充的秒数,避免剪切边界过紧导致内容残留
  status: ClipStatus;
  exportPath?: string;
  aiConfidence?: number;
}

export interface ManualClipSuggestionRequest {
  segmentId: number;
  matchedText: string;
  startTime: number;
  endTime: number;
}

export interface ExportResult {
  videoId: number;
  jobId: number;
  exportPath: string;
  removedClipCount: number;
}

export interface ExportTask {
  id: number;
  videoId: number;
  jobId?: number | null;
  status: ExportTaskStatus;
  progress: number;
  exportPath?: string | null;
  removedClipCount?: number | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BatchItemResult {
  id: number;
  success: boolean;
  message?: string;
}

export interface BatchOperationResult {
  total: number;
  succeeded: number;
  failed: number;
  items: BatchItemResult[];
}

export interface ClipSuggestionBatchItem {
  id: number;
  startTime?: number;
  endTime?: number;
  status: ClipStatus;
}

export interface TermBatchUpdateRequest {
  ids: number[];
  enabled?: boolean;
  category?: string;
  severity?: Severity;
  matchType?: MatchType;
}

export type AiApiType = 'CHAT' | 'RESPONSES';

export interface AppSettings {
  aiEnabled: boolean;
  aiApiType: AiApiType;
  aiBaseUrl: string;
  aiApiKeyConfigured: boolean; // 后端不回传明文 API Key,仅以此布尔指示是否已配置
  aiModel: string;
  aiTemperature: number;
  aiConfidenceThreshold: number;
  aiTimeoutSeconds: number;
  clipPaddingSeconds: number;
  clipPreciseExport: boolean; // 是否精确导出(按词级时间戳精剪),关闭则按整段处理
}

export interface AppSettingsUpdate {
  aiEnabled?: boolean;
  aiApiType?: AiApiType;
  aiBaseUrl?: string;
  aiApiKey?: string;
  aiModel?: string;
  aiTemperature?: number;
  aiConfidenceThreshold?: number;
  aiTimeoutSeconds?: number;
  clipPaddingSeconds?: number;
  clipPreciseExport?: boolean;
}

export interface AiConnectionTestRequest {
  aiApiType?: AiApiType;
  aiBaseUrl?: string;
  aiApiKey?: string;
  aiModel?: string;
  aiTemperature?: number;
  aiTimeoutSeconds?: number;
}

export interface AiConnectionTestResponse {
  ok: boolean;
  message: string;
  model: string;
  elapsedMs: number;
}

export interface TermImportResult {
  importedCount: number;
  skippedCount: number;
}

export interface HealthItem {
  key: string;
  label: string;
  status: HealthStatus;
  message?: string;
  elapsedMs?: number | null;
}

export interface SystemHealth {
  status: HealthStatus;
  checkedAt: string;
  items: HealthItem[];
}
