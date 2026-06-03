import axios, { AxiosProgressEvent } from 'axios';
import {
  AiConnectionTestRequest,
  AiConnectionTestResponse,
  AppSettings,
  AppSettingsUpdate,
  ClipStatus,
  ClipSuggestion,
  DetectionJob,
  ExportResult,
  ReviewStatus,
  TermHit,
  TermImportResult,
  TimelineItem,
  TranscriptSegment,
  VideoFile,
  ViolationTerm
} from './types';

export const api = axios.create({
  baseURL: '/api/v1'
});

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

export const importTerms = (file: File) => {
  const form = new FormData();
  form.append('file', file);
  return api.post<TermImportResult>('/terms/import', form).then((res) => res.data);
};

export const listVideos = () => api.get<VideoFile[]>('/videos').then((res) => res.data);

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

export const startJob = (videoId: number) =>
  api.post<DetectionJob>(`/videos/${videoId}/jobs`).then((res) => res.data);

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

export const updateClipSuggestion = (
  id: number,
  payload: { startTime?: number; endTime?: number; status: ClipStatus }
) => api.patch<ClipSuggestion>(`/clip-suggestions/${id}`, payload).then((res) => res.data);

export const updateHitStatus = (id: number, status: ReviewStatus) =>
  api.patch<TermHit>(`/hits/${id}/review-status`, { status }).then((res) => res.data);

export const exportVideo = (videoId: number) =>
  api.post<ExportResult>(`/videos/${videoId}/exports`).then((res) => res.data);

export const getSettings = () => api.get<AppSettings>('/settings').then((res) => res.data);

export const updateSettings = (payload: AppSettingsUpdate) =>
  api.put<AppSettings>('/settings', payload).then((res) => res.data);

export const testAiConnection = (payload: AiConnectionTestRequest) =>
  api.post<AiConnectionTestResponse>('/settings/ai/test', payload).then((res) => res.data);

export const videoContentUrl = (videoId: number) => `/api/v1/videos/${videoId}/content`;

export const exportContentUrl = (suggestionId: number) => `/api/v1/clip-suggestions/${suggestionId}/export-content`;
