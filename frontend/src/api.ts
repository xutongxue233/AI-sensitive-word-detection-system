/**
 * 前端唯一的后端 REST 客户端:统一封装对 `/api/v1` 的访问。
 *
 * 按业务域分组导出函数:词库 / 视频 / 任务 / 转写 / 命中 / 时间轴 / 剪辑建议 /
 * 导出 / 设置。约定每个调用都返回 `res.data` 解包后的领域对象(类型见 {@link ./types}),
 * 调用方拿到的直接是 DTO,无需再处理 axios 响应包装。
 *
 * 与后端 controller(统一前缀 `/api/v1`)一一对应;后端改动接口路径/形状时须同步此处。
 */
import axios, { AxiosProgressEvent } from 'axios';
import {
  AiConnectionTestRequest,
  AiConnectionTestResponse,
  AppSettings,
  AppSettingsUpdate,
  BatchOperationResult,
  ClipSuggestionBatchItem,
  ClipStatus,
  ClipSuggestion,
  DetectionJob,
  ExportResult,
  ExportTask,
  GeneratedTerm,
  ManualClipSuggestionRequest,
  ReviewStatus,
  SystemHealth,
  TermHit,
  TermBatchUpdateRequest,
  TermGenerationRequest,
  TermImportResult,
  TimelineItem,
  TranscriptSegment,
  VideoFile,
  ViolationTerm
} from './types';

export const api = axios.create({
  baseURL: '/api/v1'
});

/**
 * 从未知异常中提取可展示给用户的错误文案。
 *
 * 兜底顺序 `data.message → data.error → error.message → fallback`:因国产 AI 网关
 * 与各后端返回的错误结构不统一(有的放 `message`、有的放 `error`),需逐级回退。
 *
 * @param error 捕获到的异常,可能是 AxiosError、普通 Error 或任意值
 * @param fallback 全部字段缺失时的默认文案
 */

export function getErrorMessage(error: unknown, fallback = '操作失败') {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string; error?: string } | undefined;
    return data?.message || data?.error || error.message || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

export const listTerms = (keyword?: string) =>
  api.get<ViolationTerm[]>('/terms', { params: { keyword } }).then((res) => res.data);

export const createTerm = (payload: Partial<ViolationTerm>) =>
  api.post<ViolationTerm>('/terms', payload).then((res) => res.data);

export const updateTerm = (id: number, payload: Partial<ViolationTerm>) =>
  api.patch<ViolationTerm>(`/terms/${id}`, payload).then((res) => res.data);

export const deleteTerm = (id: number) => api.delete(`/terms/${id}`);

export const batchUpdateTerms = (payload: TermBatchUpdateRequest) =>
  api.patch<BatchOperationResult>('/terms/batch', payload).then((res) => res.data);

export const batchDeleteTerms = (ids: number[]) =>
  api.delete<BatchOperationResult>('/terms/batch', { data: { ids } }).then((res) => res.data);

export const importTerms = (file: File) => {
  const form = new FormData();
  form.append('file', file);
  return api.post<TermImportResult>('/terms/import', form).then((res) => res.data);
};

export const generateTermsWithAi = (payload: TermGenerationRequest) =>
  api.post<GeneratedTerm[]>('/terms/ai-generate', payload).then((res) => res.data);

export const listVideos = () => api.get<VideoFile[]>('/videos').then((res) => res.data);

/**
 * 上传视频,可选携带外部字幕文件。
 *
 * 通过 multipart 表单提交;`subtitle` 缺省时不附带(后端据此决定是否跑画面 OCR 腿)。
 * 上传进度经 `onUploadProgress` 把 `loaded/total` 换算为 0~100 的整数百分比回调;
 * 当 `total` 缺失(部分浏览器/代理不给总长度)时不触发回调,避免传出错误进度。
 *
 * @param video 待上传的视频文件
 * @param subtitle 可选的外部字幕文件(.srt/.vtt)
 * @param onProgress 可选的进度回调,入参为 0~100 的整数百分比
 */
export const uploadVideo = (video: File, subtitle?: File, onProgress?: (percent: number) => void) => {
  const form = new FormData();
  form.append('video', video);
  if (subtitle) {
    form.append('subtitle', subtitle);
  }
  return api
    .post<VideoFile>('/videos', form, {
      onUploadProgress: (event: AxiosProgressEvent) => {
        if (event.total && onProgress) {
          onProgress(Math.round((event.loaded / event.total) * 100));
        }
      }
    })
    .then((res) => res.data);
};

export const getVideo = (id: number) => api.get<VideoFile>(`/videos/${id}`).then((res) => res.data);

export const deleteVideo = (id: number) => api.delete(`/videos/${id}`);

export const batchDeleteVideos = (ids: number[]) =>
  api.delete<BatchOperationResult>('/videos/batch', { data: { ids } }).then((res) => res.data);

export const startJob = (videoId: number) =>
  api.post<DetectionJob>(`/videos/${videoId}/jobs`).then((res) => res.data);

export const batchStartJobs = (ids: number[]) =>
  api.post<BatchOperationResult>('/videos/batch/jobs', { ids }).then((res) => res.data);

export const listJobs = (videoId: number) =>
  api.get<DetectionJob[]>(`/videos/${videoId}/jobs`).then((res) => res.data);

export const getJob = (jobId: number) => api.get<DetectionJob>(`/jobs/${jobId}`).then((res) => res.data);

export const listSegments = (jobId: number) =>
  api.get<TranscriptSegment[]>(`/jobs/${jobId}/segments`).then((res) => res.data);

export const listHits = (jobId: number) => api.get<TermHit[]>(`/jobs/${jobId}/hits`).then((res) => res.data);

export const listTimeline = (jobId: number) =>
  api.get<TimelineItem[]>(`/jobs/${jobId}/timeline`).then((res) => res.data);

export const listClipSuggestions = (jobId: number) =>
  api.get<ClipSuggestion[]>(`/jobs/${jobId}/clip-suggestions`).then((res) => res.data);

export const createClipSuggestions = (jobId: number) =>
  api.post<ClipSuggestion[]>(`/jobs/${jobId}/clip-suggestions`).then((res) => res.data);

export const createManualClipSuggestion = (jobId: number, payload: ManualClipSuggestionRequest) =>
  api.post<ClipSuggestion>(`/jobs/${jobId}/clip-suggestions/manual`, payload).then((res) => res.data);

export const updateClipSuggestion = (
  id: number,
  payload: { startTime?: number; endTime?: number; status: ClipStatus }
) => api.patch<ClipSuggestion>(`/clip-suggestions/${id}`, payload).then((res) => res.data);

export const batchUpdateClipSuggestions = (items: ClipSuggestionBatchItem[]) =>
  api.patch<BatchOperationResult>('/clip-suggestions/batch', { items }).then((res) => res.data);

export const updateHitStatus = (id: number, status: ReviewStatus) =>
  api.patch<TermHit>(`/hits/${id}/review-status`, { status }).then((res) => res.data);

export const exportVideo = (videoId: number) =>
  api.post<ExportResult>(`/videos/${videoId}/exports`).then((res) => res.data);

export const enqueueExportTask = (videoId: number) =>
  api.post<ExportTask>(`/videos/${videoId}/export-tasks`).then((res) => res.data);

export const listExportTasks = (videoId: number) =>
  api.get<ExportTask[]>(`/videos/${videoId}/export-tasks`).then((res) => res.data);

export const getExportTask = (id: number) => api.get<ExportTask>(`/export-tasks/${id}`).then((res) => res.data);

export const batchEnqueueExportTasks = (ids: number[]) =>
  api.post<BatchOperationResult>('/export-tasks/batch', { ids }).then((res) => res.data);

export const getSettings = () => api.get<AppSettings>('/settings').then((res) => res.data);

export const updateSettings = (payload: AppSettingsUpdate) =>
  api.put<AppSettings>('/settings', payload).then((res) => res.data);

export const testAiConnection = (payload: AiConnectionTestRequest) =>
  api.post<AiConnectionTestResponse>('/settings/ai/test', payload).then((res) => res.data);

export const getSystemHealth = () => api.get<SystemHealth>('/system/health').then((res) => res.data);

export const videoContentUrl = (videoId: number) => `/api/v1/videos/${videoId}/content`;

export const videoExportContentUrl = (videoId: number, version?: string | number) => {
  const base = `/api/v1/videos/${videoId}/export-content`;
  return version === undefined || version === null || version === ''
    ? base
    : `${base}?v=${encodeURIComponent(String(version))}`;
};

export const exportContentUrl = (suggestionId: number) => `/api/v1/clip-suggestions/${suggestionId}/export-content`;
