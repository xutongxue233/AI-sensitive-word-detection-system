import { useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import {
  Activity,
  AudioLines,
  Captions,
  ChevronRight,
  Database,
  Download,
  FileText,
  Film,
  Gauge,
  Inbox,
  ListChecks,
  Moon,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Scissors,
  Search,
  Settings,
  ShieldAlert,
  Sparkles,
  Sun,
  Trash2,
  UploadCloud,
  Video,
  Wand2
} from 'lucide-react';

import {
  createClipSuggestions,
  createTerm,
  deleteTerm,
  deleteVideo,
  exportContentUrl,
  exportVideo,
  getErrorMessage,
  getJob,
  getVideo,
  importTerms,
  listClipSuggestions,
  listHits,
  listJobs,
  listSegments,
  listTerms,
  listTimeline,
  listVideos,
  startJob,
  updateClipSuggestion,
  updateTerm,
  uploadVideo,
  videoContentUrl
} from './api';
import { SettingsDialog } from '@/components/SettingsDialog';
import type {
  ClipStatus,
  ClipSuggestion,
  DetectionJob,
  MatchType,
  Severity,
  TermHit,
  TimelineItem,
  TranscriptSource,
  TranscriptSegment,
  VideoFile,
  ViolationTerm
} from './types';

import { cn } from '@/lib/utils';
import { formatBytes, formatClock, formatDateTime, formatTimeShort, seconds } from '@/lib/format';
import {
  CLIP_STATUS,
  EmptyState,
  Eyebrow,
  JOB_STATUS,
  MATCH_TYPE,
  Pill,
  REVIEW_STATUS,
  SEVERITY,
  SeverityBadge,
  StatCard,
  VIDEO_STATUS,
  VideoFrame
} from '@/components/bits';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from '@/components/ui/alert-dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Switch } from '@/components/ui/switch';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { ScrollArea } from '@/components/ui/scroll-area';
import { RingProgress } from '@/components/ui/ring-progress';
import { Dropzone } from '@/components/ui/dropzone';
import { NumberField } from '@/components/ui/number-field';
import { DataPagination } from '@/components/ui/data-pagination';
import { Progress } from '@/components/ui/progress';
import { Toaster } from '@/components/ui/sonner';

type NavKey = 'videos' | 'terms';

const NAV = [
  {
    key: 'videos' as const,
    label: '视频检测',
    icon: Video,
    eyebrow: 'DETECTION',
    title: '视频检测任务',
    desc: '上传视频或字幕，触发检测后查看违规词时间轴与剪辑建议'
  },
  {
    key: 'terms' as const,
    label: '违规词库',
    icon: Database,
    eyebrow: 'DICTIONARY',
    title: '违规词库管理',
    desc: '维护规则召回词库，AI 只复核候选上下文'
  }
];

const TRANSCRIPT_SOURCE: Record<TranscriptSource, { label: string; tone: 'neutral' | 'primary' | 'success' | 'info' | 'warn' | 'danger' }> = {
  AUDIO: { label: '音频', tone: 'primary' },
  SUBTITLE_FILE: { label: '字幕文件', tone: 'info' },
  VIDEO_SUBTITLE: { label: '画面字幕', tone: 'warn' }
};

function useTheme() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() =>
    typeof document !== 'undefined' && document.documentElement.classList.contains('dark') ? 'dark' : 'light'
  );
  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', theme === 'dark');
    try {
      localStorage.setItem('vm-theme', theme);
    } catch {
      /* ignore */
    }
  }, [theme]);
  return { theme, toggle: () => setTheme((t) => (t === 'dark' ? 'light' : 'dark')) };
}

function BrandMark() {
  return (
    <svg viewBox="0 0 40 40" className="h-9 w-9 shrink-0" aria-hidden>
      <defs>
        <linearGradient id="brand-mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="hsl(158 64% 48%)" />
          <stop offset="1" stopColor="hsl(168 72% 26%)" />
        </linearGradient>
      </defs>
      <rect x="1.5" y="1.5" width="37" height="37" rx="11" fill="url(#brand-mark)" />
      <rect x="1.5" y="1.5" width="37" height="37" rx="11" fill="none" stroke="white" strokeOpacity="0.18" />
      <g stroke="white" strokeWidth="2.6" strokeLinecap="round">
        <line x1="12" y1="23" x2="12" y2="19" />
        <line x1="17" y1="25" x2="17" y2="15" />
        <line x1="22" y1="27" x2="22" y2="13" />
        <line x1="27" y1="24" x2="27" y2="18" />
      </g>
    </svg>
  );
}

function Sidebar({ active, onNavigate }: { active: NavKey; onNavigate: (key: NavKey) => void }) {
  return (
    <aside className="sticky top-0 hidden h-screen w-[252px] shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground lg:flex">
      <div className="flex items-center gap-3 px-5 pb-5 pt-6">
        <BrandMark />
        <div className="min-w-0">
          <div className="truncate text-[15px] font-semibold text-white">违规词检测</div>
          <div className="eyebrow mt-0.5 text-sidebar-foreground/55">AUDIT CONSOLE</div>
        </div>
      </div>

      <nav className="flex flex-col gap-1 px-3">
        {NAV.map((item) => {
          const isActive = active === item.key;
          const Icon = item.icon;
          return (
            <button
              key={item.key}
              type="button"
              onClick={() => onNavigate(item.key)}
              className={cn(
                'group relative flex items-center gap-3 rounded-md px-3 py-2.5 text-sm transition-colors',
                isActive
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                  : 'text-sidebar-foreground/75 hover:bg-white/5 hover:text-white'
              )}
            >
              <span
                className={cn(
                  'absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r-full bg-[hsl(var(--sidebar-ring))] transition-opacity',
                  isActive ? 'opacity-100' : 'opacity-0'
                )}
              />
              <Icon className={cn('h-[18px] w-[18px]', isActive ? 'text-[hsl(var(--sidebar-ring))]' : 'opacity-80')} />
              <span className="font-medium">{item.label}</span>
              {isActive && <ChevronRight className="ml-auto h-4 w-4 opacity-50" />}
            </button>
          );
        })}
      </nav>

      <div className="mt-auto space-y-3 px-4 pb-5">
        <div className="rounded-lg border border-sidebar-border bg-black/20 p-3">
          <Eyebrow className="text-sidebar-foreground/50">系统状态</Eyebrow>
          <div className="mt-2.5 space-y-2 text-[12px]">
            <StatusLine label="检测流水线" ok />
            <StatusLine label="ASR / 字幕 OCR" ok />
            <StatusLine label="数据来源" ok note="后端" />
          </div>
        </div>
        <div className="px-1 text-[11px] text-sidebar-foreground/40">
          <span className="telemetry">v0.0.1</span> · Whisper 时间轴审核台
        </div>
      </div>
    </aside>
  );
}

function StatusLine({ label, ok, note }: { label: string; ok: boolean; note?: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-sidebar-foreground/70">{label}</span>
      <span className="flex items-center gap-1.5">
        <span
          className={cn(
            'h-1.5 w-1.5 rounded-full',
            ok ? 'bg-[hsl(var(--sidebar-ring))] animate-pulse-ring' : 'bg-[hsl(var(--sev-medium))]'
          )}
        />
        <span className={cn('telemetry text-[10px] uppercase', ok ? 'text-sidebar-foreground/55' : 'text-[hsl(var(--sev-medium))]')}>
          {note ?? 'OK'}
        </span>
      </span>
    </div>
  );
}

function TopBar({
  active,
  onNavigate,
  theme,
  onToggleTheme,
  onOpenSettings
}: {
  active: NavKey;
  onNavigate: (key: NavKey) => void;
  theme: 'light' | 'dark';
  onToggleTheme: () => void;
  onOpenSettings: () => void;
}) {
  const current = NAV.find((n) => n.key === active)!;
  const [clock, setClock] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setClock(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <header className="sticky top-0 z-30 border-b border-border bg-background/85 backdrop-blur supports-[backdrop-filter]:bg-background/70">
      <div className="flex items-center gap-4 px-5 py-3.5 lg:px-8">
        <div className="flex items-center gap-2 lg:hidden">
          <BrandMark />
        </div>
        <div className="min-w-0 flex-1">
          <Eyebrow>{current.eyebrow}</Eyebrow>
          <h1 className="mt-0.5 truncate text-lg font-semibold tracking-tight">{current.title}</h1>
        </div>

        {/* 小屏导航 */}
        <div className="flex items-center rounded-md border border-border bg-card p-0.5 lg:hidden">
          {NAV.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => onNavigate(item.key)}
                aria-label={item.label}
                className={cn(
                  'grid h-8 w-9 place-items-center rounded-[5px] transition-colors',
                  active === item.key ? 'bg-primary/12 text-primary' : 'text-muted-foreground'
                )}
              >
                <Icon className="h-4 w-4" />
              </button>
            );
          })}
        </div>

        <div className="hidden items-center gap-2 rounded-md border border-border bg-card px-2.5 py-1.5 md:flex">
          <Activity className="h-3.5 w-3.5 text-primary" />
          <span className="telemetry text-xs text-muted-foreground">{formatClock(clock)}</span>
        </div>
        <Button variant="outline" size="icon" onClick={onOpenSettings} aria-label="系统设置">
          <Settings className="h-4 w-4" />
        </Button>
        <Button variant="outline" size="icon" onClick={onToggleTheme} aria-label="切换主题">
          {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        </Button>
      </div>
    </header>
  );
}

export default function App() {
  const { theme, toggle } = useTheme();
  const [active, setActive] = useState<NavKey>('videos');
  const [settingsOpen, setSettingsOpen] = useState(false);

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <Sidebar active={active} onNavigate={setActive} />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar
          active={active}
          onNavigate={setActive}
          theme={theme}
          onToggleTheme={toggle}
          onOpenSettings={() => setSettingsOpen(true)}
        />
        <main className="relative flex-1 overflow-x-hidden">
          <div className="pointer-events-none absolute inset-0 bg-grid opacity-60 [mask-image:linear-gradient(to_bottom,black,transparent_60%)]" />
          <div className="pointer-events-none absolute -top-24 right-0 h-72 w-72 rounded-full bg-primary/10 blur-3xl" />
          <div className="relative mx-auto w-full max-w-[1440px] px-5 py-6 lg:px-8">
            {active === 'videos' ? <VideosPage /> : <TermsPage />}
          </div>
        </main>
      </div>
      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />
      <Toaster theme={theme} richColors closeButton />
    </div>
  );
}

/* ============================ 视频检测 ============================ */

function VideosPage() {
  const [videos, setVideos] = useState<VideoFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [subtitleFile, setSubtitleFile] = useState<File | undefined>();
  const [selected, setSelected] = useState<{ videoId: number; jobId?: number } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<VideoFile | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [page, setPage] = useState(1);
  const pageSize = 6;
  const subtitleInputRef = useRef<HTMLInputElement>(null);

  const load = async () => {
    setLoading(true);
    try {
      setVideos(await listVideos());
    } catch (error) {
      toast.error(getErrorMessage(error, '加载视频列表失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    const pc = Math.max(1, Math.ceil(videos.length / pageSize));
    setPage((p) => Math.min(p, pc));
  }, [videos.length]);

  const handleUpload = async (files: File[]) => {
    const video = files[0];
    if (!video) return;
    setUploading(true);
    setProgress(0);
    try {
      await uploadVideo(video, subtitleFile, setProgress);
      toast.success('视频已上传');
      setSubtitleFile(undefined);
      setPage(1);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '上传失败'));
    } finally {
      setUploading(false);
      setProgress(0);
    }
  };

  const triggerJob = async (video: VideoFile) => {
    try {
      const job = await startJob(video.id);
      toast.success('检测任务已启动');
      setSelected({ videoId: video.id, jobId: job.id });
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '启动检测失败'));
    }
  };

  const exportModerated = async (video: VideoFile) => {
    try {
      const result = await exportVideo(video.id);
      toast.success(`已导出，处理建议 ${result.removedClipCount} 个`);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导出失败'));
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteVideo(deleteTarget.id);
      toast.success('视频已删除');
      if (selected?.videoId === deleteTarget.id) {
        setSelected(null);
      }
      setDeleteTarget(null);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '删除失败'));
    } finally {
      setDeleting(false);
    }
  };

  const stats = useMemo(() => {
    const done = videos.filter((v) => v.status === 'DETECTED' || v.status === 'EXPORTED').length;
    const processing = videos.filter((v) => v.status === 'DETECTING').length;
    const exported = videos.filter((v) => v.status === 'EXPORTED').length;
    return { total: videos.length, done, processing, exported };
  }, [videos]);

  const pageCount = Math.max(1, Math.ceil(videos.length / pageSize));
  const pageItems = videos.slice((Math.min(page, pageCount) - 1) * pageSize, Math.min(page, pageCount) * pageSize);

  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-4 animate-fade-up xl:grid-cols-4">
        <StatCard label="视频总数" value={stats.total} icon={Film} hint="已上传到工作台" />
        <StatCard label="检测完成" value={stats.done} icon={ListChecks} hint="含已导出视频" />
        <StatCard label="处理中" value={stats.processing} icon={Activity} hint="检测任务进行中" />
        <StatCard label="已导出" value={stats.exported} icon={Scissors} hint="去违规版本" />
      </div>

      <Card className="animate-fade-up animate-delay-75">
        <CardContent className="pt-5">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3 border-b border-border pb-4">
            <div className="flex items-center gap-3">
              <span className="grid h-9 w-9 place-items-center rounded-md bg-primary/10 text-primary">
                <Captions className="h-[18px] w-[18px]" />
              </span>
              <div>
                <div className="text-sm font-medium">字幕文件（可选）</div>
                <div className="text-[12px] text-muted-foreground">
                  上传后会检测音频层；选择字幕文件后作为字幕层，未选择时用 PaddleOCR 扫描画面字幕。
                </div>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <input
                ref={subtitleInputRef}
                type="file"
                accept=".srt,.vtt"
                className="hidden"
                onChange={(e) => {
                  setSubtitleFile(e.target.files?.[0]);
                  e.target.value = '';
                }}
              />
              <Button variant="outline" size="sm" onClick={() => subtitleInputRef.current?.click()}>
                <FileText className="h-4 w-4" />
                选择字幕
              </Button>
              {subtitleFile && (
                <Pill tone="success" dot={false} className="max-w-[200px]">
                  <span className="truncate">{subtitleFile.name}</span>
                  <button
                    type="button"
                    className="ml-0.5 opacity-70 hover:opacity-100"
                    onClick={() => setSubtitleFile(undefined)}
                    aria-label="移除字幕"
                  >
                    ×
                  </button>
                </Pill>
              )}
            </div>
          </div>

          <Dropzone onFiles={handleUpload} accept="video/*" disabled={uploading}>
            <span className="mx-auto grid h-14 w-14 place-items-center rounded-xl bg-primary/10 text-primary">
              {uploading ? (
                <RefreshCw className="h-6 w-6 animate-spin" />
              ) : (
                <UploadCloud className="h-7 w-7" />
              )}
            </span>
            <div className="mt-4 text-[15px] font-semibold">
              {uploading ? `正在上传… ${progress}%` : '拖拽视频到此处，或点击选择文件'}
            </div>
            {uploading ? (
              <Progress value={progress} className="mx-auto mt-3 h-1.5 w-56" />
            ) : (
              <div className="mt-1 text-[13px] text-muted-foreground">
                支持常见视频格式 · 音频层调用 Whisper ASR，字幕层优先解析字幕文件，否则使用 PaddleOCR
              </div>
            )}
          </Dropzone>
        </CardContent>
      </Card>

      <Card className="animate-fade-up animate-delay-150">
        <CardHeader className="flex-row items-center justify-between gap-3 space-y-0">
          <div className="flex items-center gap-2">
            <CardTitle>视频队列</CardTitle>
            <Pill tone="neutral" dot={false}>
              <span className="telemetry">{videos.length}</span>
            </Pill>
          </div>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} />
            刷新
          </Button>
        </CardHeader>
        <CardContent>
          {videos.length === 0 && !loading ? (
            <EmptyState icon={Inbox} title="还没有视频" description="上传一个视频文件开始你的第一次违规词检测。" />
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>文件名</TableHead>
                    <TableHead className="w-[96px]">时长</TableHead>
                    <TableHead className="w-[100px]">状态</TableHead>
                    <TableHead className="w-[150px]">上传时间</TableHead>
                    <TableHead className="w-[300px] text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pageItems.map((video) => {
                    const status = VIDEO_STATUS[video.status];
                    return (
                      <TableRow key={video.id}>
                        <TableCell>
                          <div className="flex items-center gap-2.5">
                            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-muted text-muted-foreground">
                              <Film className="h-4 w-4" />
                            </span>
                            <div className="min-w-0">
                              <div className="font-medium truncate">{video.originalFilename}</div>
                              <div className="telemetry text-[11px] text-muted-foreground">
                                {formatBytes(video.sizeBytes)}
                                {video.width && video.height ? ` · ${video.width}×${video.height}` : ''}
                              </div>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell className="telemetry text-[13px] text-muted-foreground">
                          {seconds(video.durationSeconds)}
                        </TableCell>
                        <TableCell>
                          <Pill tone={status.tone} pulse={video.status === 'DETECTING'}>
                            {status.label}
                          </Pill>
                        </TableCell>
                        <TableCell className="telemetry text-[12px] text-muted-foreground">
                          {formatDateTime(video.createdAt)}
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center justify-end gap-1.5">
                            <Button variant="ghost" size="sm" onClick={() => triggerJob(video)}>
                              <Play className="h-4 w-4" />
                              检测
                            </Button>
                            <Button variant="ghost" size="sm" onClick={() => setSelected({ videoId: video.id })}>
                              <Gauge className="h-4 w-4" />
                              详情
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              onClick={() => exportModerated(video)}
                              aria-label="导出去违规视频"
                            >
                              <Download className="h-4 w-4" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              className="text-muted-foreground hover:text-destructive"
                              onClick={() => setDeleteTarget(video)}
                              aria-label="删除视频"
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
              <DataPagination page={Math.min(page, pageCount)} pageSize={pageSize} total={videos.length} onPageChange={setPage} />
            </>
          )}
        </CardContent>
      </Card>

      <Sheet
        open={!!selected}
        onOpenChange={(open) => {
          if (!open) {
            setSelected(null);
            load();
          }
        }}
      >
        <SheetContent
          side="right"
          className="flex w-full flex-col gap-0 p-0 sm:max-w-none sm:w-[min(1100px,96vw)]"
        >
          <SheetTitle className="sr-only">检测详情</SheetTitle>
          <SheetDescription className="sr-only">视频检测结果、违规词时间轴与剪辑建议</SheetDescription>
          {selected && <JobDetail videoId={selected.videoId} initialJobId={selected.jobId} />}
        </SheetContent>
      </Sheet>

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && !deleting && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除视频</AlertDialogTitle>
            <AlertDialogDescription>
              将删除「{deleteTarget?.originalFilename}」及其全部检测任务、命中记录、剪辑建议与原始文件，此操作不可恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => {
                e.preventDefault();
                confirmDelete();
              }}
              disabled={deleting}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleting ? '删除中…' : '确认删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/* ============================ 检测详情 ============================ */

function JobDetail({ videoId, initialJobId }: { videoId: number; initialJobId?: number }) {
  const [video, setVideo] = useState<VideoFile | null>(null);
  const [job, setJob] = useState<DetectionJob | null>(null);
  const [segments, setSegments] = useState<TranscriptSegment[]>([]);
  const [hits, setHits] = useState<TermHit[]>([]);
  const [timeline, setTimeline] = useState<TimelineItem[]>([]);
  const [suggestions, setSuggestions] = useState<ClipSuggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [hitPage, setHitPage] = useState(1);

  const activeJobId = job?.id ?? initialJobId;

  const load = async () => {
    setLoading(true);
    try {
      const currentVideo = await getVideo(videoId);
      setVideo(currentVideo);
      let jobId = job?.id ?? initialJobId;
      if (!jobId) {
        const jobs = await listJobs(videoId);
        jobId = jobs[0]?.id;
      }
      if (jobId) {
        const currentJob = await getJob(jobId);
        setJob(currentJob);
        if (currentJob.status === 'COMPLETED' || currentJob.status === 'FAILED') {
          const [nextSegments, nextHits, nextTimeline, nextSuggestions] = await Promise.all([
            listSegments(jobId),
            listHits(jobId),
            listTimeline(jobId),
            listClipSuggestions(jobId)
          ]);
          setSegments(nextSegments);
          setHits(nextHits);
          setTimeline(nextTimeline);
          setSuggestions(nextSuggestions);
        }
      }
    } catch (error) {
      toast.error(getErrorMessage(error, '加载检测详情失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoId, initialJobId]);

  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  });

  useEffect(() => {
    setHitPage((p) => Math.min(p, Math.max(1, Math.ceil(hits.length / 6))));
  }, [hits.length]);

  useEffect(() => {
    if (!job || job.status === 'COMPLETED' || job.status === 'FAILED') {
      return undefined;
    }
    const timer = window.setInterval(() => loadRef.current(), 2500);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.id, job?.status]);

  const duration = useMemo(() => {
    const ends = [
      video?.durationSeconds ?? 0,
      ...timeline.map((t) => t.endTime + 1),
      ...segments.map((s) => s.endTime)
    ];
    return Math.max(1, ...ends);
  }, [video, timeline, segments]);

  const regenerateSuggestions = async () => {
    if (!activeJobId) return;
    try {
      setSuggestions(await createClipSuggestions(activeJobId));
      toast.success('剪辑建议已重新生成');
    } catch (error) {
      toast.error(getErrorMessage(error, '重新生成失败'));
    }
  };

  const saveSuggestion = async (record: ClipSuggestion, status: ClipStatus) => {
    try {
      const next = await updateClipSuggestion(record.id, {
        startTime: record.startTime,
        endTime: record.endTime,
        status
      });
      setSuggestions((items) => items.map((item) => (item.id === record.id ? next : item)));
      toast.success('剪辑建议已更新');
    } catch (error) {
      toast.error(getErrorMessage(error, '更新失败'));
    }
  };

  const handleExport = async () => {
    if (!video) return;
    setExporting(true);
    try {
      const result = await exportVideo(video.id);
      toast.success(`已导出，删除片段 ${result.removedClipCount} 个`);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导出失败'));
    } finally {
      setExporting(false);
    }
  };

  const patchSuggestion = (id: number, patch: Partial<ClipSuggestion>) =>
    setSuggestions((items) => items.map((item) => (item.id === id ? { ...item, ...patch } : item)));

  const exportedSuggestion = suggestions.find((item) => item.status === 'EXPORTED');
  const jobStatus = job ? JOB_STATUS[job.status] : null;
  const violationCount = timeline.length;

  const hitPageSize = 6;
  const hitPageCount = Math.max(1, Math.ceil(hits.length / hitPageSize));
  const hitItems = hits.slice((Math.min(hitPage, hitPageCount) - 1) * hitPageSize, Math.min(hitPage, hitPageCount) * hitPageSize);

  return (
    <>
      <div className="sticky top-0 z-10 flex items-center gap-3 border-b border-border bg-card/95 px-6 py-4 backdrop-blur">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-primary/10 text-primary">
          <Gauge className="h-5 w-5" />
        </span>
        <div className="min-w-0 flex-1">
          <Eyebrow>检测详情</Eyebrow>
          <div className="truncate text-[15px] font-semibold">{video?.originalFilename ?? '加载中…'}</div>
        </div>
        {job && jobStatus && (
          <Pill tone={jobStatus.tone} pulse={job.status !== 'COMPLETED' && job.status !== 'FAILED'}>
            {jobStatus.label}
          </Pill>
        )}
      </div>

      <ScrollArea className="flex-1">
        <div className="space-y-5 px-6 py-5">
          {video && (
            <div className="grid gap-4 md:grid-cols-2">
              <VideoFrame src={videoContentUrl(video.id)} label="原始视频" icon={Film} />
              {exportedSuggestion ? (
                <VideoFrame src={exportContentUrl(exportedSuggestion.id)} label="去违规版本" icon={Scissors} />
              ) : (
                <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-border bg-muted/20 text-center text-muted-foreground">
                  <Scissors className="h-6 w-6 opacity-50" />
                  <span className="text-[12px]">确认剪辑并导出后将在此显示去违规版本</span>
                </div>
              )}
            </div>
          )}

          {/* 任务进度 */}
          {job ? (
            <Card>
              <CardContent className="flex flex-wrap items-center gap-5 pt-5">
                <RingProgress
                  value={job.progress}
                  status={job.status === 'FAILED' ? 'exception' : job.status === 'COMPLETED' ? 'success' : 'active'}
                />
                <div className="min-w-0 flex-1 space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="telemetry text-sm font-semibold">任务 #{job.id}</span>
                    {jobStatus && <Pill tone={jobStatus.tone}>{jobStatus.label}</Pill>}
                    <span className="text-[12px] text-muted-foreground">
                      {job.startedAt ? `开始于 ${formatTimeShort(job.startedAt)}` : '等待启动'}
                    </span>
                  </div>
                  <div className="flex flex-wrap gap-x-6 gap-y-1 text-[12px] text-muted-foreground">
                    <span>
                      违规命中 <span className="telemetry text-foreground">{violationCount}</span>
                    </span>
                    <span>
                      候选命中 <span className="telemetry text-foreground">{hits.length}</span>
                    </span>
                    <span>
                      字幕句段 <span className="telemetry text-foreground">{segments.length}</span>
                    </span>
                  </div>
                  {job.errorMessage && (
                    <Alert variant="destructive">
                      <ShieldAlert />
                      <AlertDescription>{job.errorMessage}</AlertDescription>
                    </Alert>
                  )}
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  className="self-start"
                  disabled={exporting || job.status !== 'COMPLETED'}
                  onClick={handleExport}
                >
                  {exporting ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
                  导出去违规视频
                </Button>
              </CardContent>
            </Card>
          ) : (
            <Alert variant="info">
              <Sparkles />
              <AlertDescription>该视频还没有检测任务，可以在视频列表点击“检测”。</AlertDescription>
            </Alert>
          )}

          {/* 违规词时间轴 */}
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2">
                <CardTitle>违规词时间轴</CardTitle>
                {violationCount > 0 && (
                  <Pill tone="danger" dot={false}>
                    <span className="telemetry">{violationCount}</span> 处
                  </Pill>
                )}
              </div>
            </CardHeader>
            <CardContent>
              {timeline.length === 0 ? (
                <EmptyState
                  icon={ShieldAlert}
                  title="暂无违规命中"
                  description={loading ? '正在加载…' : 'AI 复核未判定出违规内容，或任务尚未完成。'}
                />
              ) : (
                <div className="space-y-4">
                  <div className="relative">
                    <div className="relative h-9 overflow-hidden rounded-md border border-border bg-muted/40 bg-dots">
                      {timeline.map((item) => {
                        const left = Math.max(0, (item.startTime / duration) * 100);
                        const width = Math.max(1.2, ((item.endTime - item.startTime) / duration) * 100);
                        return (
                          <Tooltip key={item.hitId}>
                            <TooltipTrigger asChild>
                              <button
                                type="button"
                                className="absolute top-1 h-7 rounded-[4px] ring-1 ring-inset ring-white/20 transition-transform hover:scale-y-110"
                                style={{
                                  left: `${left}%`,
                                  width: `${width}%`,
                                  backgroundColor: `hsl(var(${SEVERITY[item.severity].varName}))`
                                }}
                                aria-label={item.matchedText}
                              />
                            </TooltipTrigger>
                            <TooltipContent>
                              <div className="max-w-[260px] space-y-0.5">
                                <div className="flex items-center gap-2">
                                  <span className="font-medium">{item.matchedText}</span>
                                  <Pill tone={TRANSCRIPT_SOURCE[item.source]?.tone ?? 'neutral'} dot={false}>
                                    {TRANSCRIPT_SOURCE[item.source]?.label ?? item.source}
                                  </Pill>
                                  {item.aiConfidence !== undefined && (
                                    <span className="telemetry text-[11px] text-muted-foreground">
                                      AI {Math.round(item.aiConfidence * 100)}%
                                    </span>
                                  )}
                                </div>
                                <div className="telemetry text-[11px] text-muted-foreground">
                                  {seconds(item.startTime)} – {seconds(item.endTime)}
                                </div>
                                {item.aiReason && (
                                  <div className="text-[11px] text-muted-foreground">{item.aiReason}</div>
                                )}
                              </div>
                            </TooltipContent>
                          </Tooltip>
                        );
                      })}
                    </div>
                    <div className="mt-1.5 flex justify-between">
                      {[0, 0.25, 0.5, 0.75, 1].map((frac) => (
                        <span key={frac} className="telemetry text-[10px] text-muted-foreground">
                          {seconds(duration * frac)}
                        </span>
                      ))}
                    </div>
                  </div>

                  <div className="space-y-1.5">
                    {timeline.map((item) => (
                      <div
                        key={item.hitId}
                        className="flex items-center gap-3 rounded-md border border-border bg-card px-3 py-2"
                      >
                        <SeverityBadge severity={item.severity} />
                        <Pill tone={TRANSCRIPT_SOURCE[item.source]?.tone ?? 'neutral'} dot={false}>
                          {TRANSCRIPT_SOURCE[item.source]?.label ?? item.source}
                        </Pill>
                        <span className="font-medium">{item.matchedText}</span>
                        <span className="telemetry shrink-0 text-[12px] text-muted-foreground">
                          {seconds(item.startTime)} – {seconds(item.endTime)}
                        </span>
                        {item.aiConfidence !== undefined && (
                          <Pill tone="info" dot={false}>
                            AI 置信 <span className="telemetry">{Math.round(item.aiConfidence * 100)}%</span>
                          </Pill>
                        )}
                        {(item.aiReason || item.contextText) && (
                          <span className="ml-auto min-w-0 truncate text-[12px] text-muted-foreground">
                            {item.aiReason || item.contextText}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* 剪辑建议 */}
          <Card>
            <CardHeader className="flex-row items-center justify-between gap-3 space-y-0">
              <CardTitle>剪辑建议</CardTitle>
              <Button variant="outline" size="sm" onClick={regenerateSuggestions} disabled={!activeJobId}>
                <Wand2 className="h-4 w-4" />
                重新生成
              </Button>
            </CardHeader>
            <CardContent>
              {suggestions.length === 0 ? (
                <EmptyState icon={Scissors} title="暂无剪辑建议" description="检测完成并存在违规命中后将生成剪辑建议。" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow className="hover:bg-transparent">
                      <TableHead>违规词</TableHead>
                      <TableHead className="w-[96px]">处理</TableHead>
                      <TableHead className="w-[150px]">开始 (秒)</TableHead>
                      <TableHead className="w-[150px]">结束 (秒)</TableHead>
                      <TableHead className="w-[88px]">置信度</TableHead>
                      <TableHead className="w-[96px]">状态</TableHead>
                      <TableHead className="w-[170px] text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {suggestions.map((record) => {
                      const status = CLIP_STATUS[record.status];
                      const locked = record.status === 'EXPORTED';
                      return (
                        <TableRow key={record.id}>
                          <TableCell className="font-medium">{record.matchedText}</TableCell>
                          <TableCell>
                            <Pill tone={TRANSCRIPT_SOURCE[record.source]?.tone ?? 'neutral'} dot={false}>
                              {record.action === 'BLUR_SUBTITLE' ? '遮盖字幕' : '删除音频段'}
                            </Pill>
                          </TableCell>
                          <TableCell>
                            <NumberField
                              value={record.startTime}
                              min={0}
                              step={0.1}
                              suffix="s"
                              className="w-[120px]"
                              name={`clip-${record.id}-start`}
                              aria-label="剪辑开始时间（秒）"
                              onChange={(value) => patchSuggestion(record.id, { startTime: value })}
                            />
                          </TableCell>
                          <TableCell>
                            <NumberField
                              value={record.endTime}
                              min={0}
                              step={0.1}
                              suffix="s"
                              className="w-[120px]"
                              name={`clip-${record.id}-end`}
                              aria-label="剪辑结束时间（秒）"
                              onChange={(value) => patchSuggestion(record.id, { endTime: value })}
                            />
                          </TableCell>
                          <TableCell className="telemetry text-[13px] text-muted-foreground">
                            {record.aiConfidence === undefined ? '-' : `${Math.round(record.aiConfidence * 100)}%`}
                          </TableCell>
                          <TableCell>
                            <Pill tone={status.tone}>{status.label}</Pill>
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center justify-end gap-1.5">
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={locked}
                                onClick={() => saveSuggestion(record, 'CONFIRMED')}
                              >
                                确认
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={locked}
                                onClick={() => saveSuggestion(record, 'IGNORED')}
                              >
                                忽略
                              </Button>
                            </div>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>

          {/* 命中与 AI 复核 */}
          <Card>
            <CardHeader>
              <CardTitle>命中与 AI 复核</CardTitle>
            </CardHeader>
            <CardContent>
              {hits.length === 0 ? (
                <EmptyState icon={Sparkles} title="暂无命中记录" description="规则召回的候选命中及 AI 判定将展示在这里。" />
              ) : (
                <>
                  <Table>
                    <TableHeader>
                      <TableRow className="hover:bg-transparent">
                        <TableHead>词</TableHead>
                        <TableHead className="w-[96px]">来源</TableHead>
                        <TableHead className="w-[150px]">时间</TableHead>
                        <TableHead className="w-[92px]">判定</TableHead>
                        <TableHead className="w-[88px]">置信度</TableHead>
                        <TableHead>AI 原因</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {hitItems.map((hit) => {
                        const review = REVIEW_STATUS[hit.reviewStatus];
                        return (
                          <TableRow key={hit.id}>
                            <TableCell className="font-medium">{hit.matchedText}</TableCell>
                            <TableCell>
                              <Pill tone={TRANSCRIPT_SOURCE[hit.source]?.tone ?? 'neutral'} dot={false}>
                                {TRANSCRIPT_SOURCE[hit.source]?.label ?? hit.source}
                              </Pill>
                            </TableCell>
                            <TableCell className="telemetry text-[12px] text-muted-foreground">
                              {seconds(hit.startTime)} – {seconds(hit.endTime)}
                            </TableCell>
                            <TableCell>
                              <Pill tone={review.tone}>{review.label}</Pill>
                            </TableCell>
                            <TableCell className="telemetry text-[13px]">
                              {hit.aiConfidence === undefined ? '-' : `${Math.round(hit.aiConfidence * 100)}%`}
                            </TableCell>
                            <TableCell className="text-[13px] text-muted-foreground">
                              {hit.aiReview?.reason || '-'}
                            </TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                  <DataPagination
                    page={Math.min(hitPage, hitPageCount)}
                    pageSize={hitPageSize}
                    total={hits.length}
                    onPageChange={setHitPage}
                  />
                </>
              )}
            </CardContent>
          </Card>

          {/* 字幕片段 */}
          <Card>
            <CardHeader className="flex-row items-center gap-2 space-y-0">
              <AudioLines className="h-4 w-4 text-muted-foreground" />
              <CardTitle>字幕片段</CardTitle>
            </CardHeader>
            <CardContent>
              {segments.length === 0 ? (
                <EmptyState icon={FileText} title="暂无字幕句段" description="检测完成后将展示转写得到的句段与时间码。" />
              ) : (
                <ScrollArea className="h-[280px] pr-3">
                  <div className="space-y-1.5">
                    {segments.map((segment) => (
                      <div key={segment.id} className="flex gap-3 rounded-md border border-border bg-card px-3 py-2">
                        <span className="telemetry shrink-0 rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                          {seconds(segment.startTime)} – {seconds(segment.endTime)}
                        </span>
                        <Pill tone={TRANSCRIPT_SOURCE[segment.source]?.tone ?? 'neutral'} dot={false}>
                          {TRANSCRIPT_SOURCE[segment.source]?.label ?? segment.source}
                        </Pill>
                        <span className="text-[13px] leading-relaxed">{segment.text}</span>
                      </div>
                    ))}
                  </div>
                </ScrollArea>
              )}
            </CardContent>
          </Card>
        </div>
      </ScrollArea>
    </>
  );
}

/* ============================ 违规词库 ============================ */

interface TermForm {
  term: string;
  category: string;
  severity: Severity;
  matchType: MatchType;
  enabled: boolean;
  variants: string;
}

const EMPTY_FORM: TermForm = {
  term: '',
  category: '',
  severity: 'MEDIUM',
  matchType: 'EXACT',
  enabled: true,
  variants: ''
};

function TermsPage() {
  const [terms, setTerms] = useState<ViolationTerm[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<ViolationTerm | null>(null);
  const [form, setForm] = useState<TermForm>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ViolationTerm | null>(null);
  const [page, setPage] = useState(1);
  const pageSize = 8;
  const importInputRef = useRef<HTMLInputElement>(null);

  const load = async (kw = keyword) => {
    setLoading(true);
    try {
      setTerms(await listTerms(kw));
    } catch (error) {
      toast.error(getErrorMessage(error, '加载违规词失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const pc = Math.max(1, Math.ceil(terms.length / pageSize));
    setPage((p) => Math.min(p, pc));
  }, [terms.length]);

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (term: ViolationTerm) => {
    setEditing(term);
    setForm({
      term: term.term,
      category: term.category ?? '',
      severity: term.severity,
      matchType: term.matchType,
      enabled: term.enabled,
      variants: term.variants ?? ''
    });
    setDialogOpen(true);
  };

  const submit = async () => {
    if (!form.term.trim()) {
      toast.error('请输入词条');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await updateTerm(editing.id, form);
        toast.success('违规词已更新');
      } else {
        await createTerm(form);
        toast.success('违规词已创建');
      }
      setDialogOpen(false);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '保存违规词失败'));
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = async (term: ViolationTerm, enabled: boolean) => {
    setTerms((items) => items.map((item) => (item.id === term.id ? { ...item, enabled } : item)));
    try {
      await updateTerm(term.id, { enabled });
    } catch (error) {
      setTerms((items) => items.map((item) => (item.id === term.id ? { ...item, enabled: !enabled } : item)));
      toast.error(getErrorMessage(error, '更新状态失败'));
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteTerm(deleteTarget.id);
      toast.success('已删除');
      setDeleteTarget(null);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '删除失败'));
    }
  };

  const handleImport = async (file?: File) => {
    if (!file) return;
    try {
      const result = await importTerms(file);
      toast.success(`已导入 ${result.importedCount} 条，跳过 ${result.skippedCount} 条`);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导入失败'));
    }
  };

  const enabledCount = terms.filter((t) => t.enabled).length;
  const pageCount = Math.max(1, Math.ceil(terms.length / pageSize));
  const pageItems = terms.slice((Math.min(page, pageCount) - 1) * pageSize, Math.min(page, pageCount) * pageSize);

  return (
    <div className="flex flex-col gap-5">
      <Card className="animate-fade-up">
        <CardContent className="pt-5">
          <div className="flex flex-wrap items-center gap-3">
            <div className="relative min-w-[240px] flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="搜索词条或分类，回车检索"
                id="term-search"
                name="term-search"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    setPage(1);
                    load();
                  }
                }}
                className="pl-9"
              />
            </div>
            <Button variant="outline" onClick={() => load()} disabled={loading}>
              <RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} />
              刷新
            </Button>
            <input
              ref={importInputRef}
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={(e) => {
                handleImport(e.target.files?.[0]);
                e.target.value = '';
              }}
            />
            <Button variant="outline" onClick={() => importInputRef.current?.click()}>
              <UploadCloud className="h-4 w-4" />
              导入 CSV
            </Button>
            <Button onClick={openCreate}>
              <Plus className="h-4 w-4" />
              新增违规词
            </Button>
          </div>
          <div className="mt-3 flex items-center gap-4 text-[12px] text-muted-foreground">
            <span>
              共 <span className="telemetry text-foreground">{terms.length}</span> 条
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              启用 <span className="telemetry text-foreground">{enabledCount}</span>
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-1.5 w-1.5 rounded-full bg-muted-foreground/50" />
              停用 <span className="telemetry text-foreground">{terms.length - enabledCount}</span>
            </span>
          </div>
        </CardContent>
      </Card>

      <Card className="animate-fade-up animate-delay-75">
        <CardContent className="pt-5">
          {terms.length === 0 && !loading ? (
            <EmptyState
              icon={Database}
              title="词库为空"
              description="新增违规词或导入 CSV，开始构建你的规则召回词库。"
            />
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>词条</TableHead>
                    <TableHead className="w-[140px]">分类</TableHead>
                    <TableHead className="w-[88px]">严重级别</TableHead>
                    <TableHead className="w-[88px]">匹配方式</TableHead>
                    <TableHead className="w-[80px]">状态</TableHead>
                    <TableHead className="w-[160px]">变体词</TableHead>
                    <TableHead className="w-[140px]">更新时间</TableHead>
                    <TableHead className="w-[96px] text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pageItems.map((term) => (
                    <TableRow key={term.id}>
                      <TableCell>
                        <span className="font-medium">{term.term}</span>
                      </TableCell>
                      <TableCell className="text-[13px] text-muted-foreground">{term.category || '-'}</TableCell>
                      <TableCell>
                        <SeverityBadge severity={term.severity} />
                      </TableCell>
                      <TableCell>
                        <span className="text-[13px] text-muted-foreground">{MATCH_TYPE[term.matchType]}</span>
                      </TableCell>
                      <TableCell>
                        <Switch checked={term.enabled} onCheckedChange={(v) => toggleEnabled(term, v)} />
                      </TableCell>
                      <TableCell className="max-w-[160px] truncate text-[12px] text-muted-foreground">
                        {term.variants || '-'}
                      </TableCell>
                      <TableCell className="telemetry text-[12px] text-muted-foreground">
                        {formatDateTime(term.updatedAt)}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center justify-end gap-1">
                          <Button variant="ghost" size="icon-sm" onClick={() => openEdit(term)} aria-label="编辑">
                            <Pencil className="h-4 w-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={() => setDeleteTarget(term)}
                            aria-label="删除"
                            className="text-muted-foreground hover:text-destructive"
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <DataPagination page={Math.min(page, pageCount)} pageSize={pageSize} total={terms.length} onPageChange={setPage} />
            </>
          )}
        </CardContent>
      </Card>

      {/* 新增 / 编辑 */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? '编辑违规词' : '新增违规词'}</DialogTitle>
            <DialogDescription>规则召回用于找到候选命中，AI 仅复核候选上下文。</DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="term">词条 / 正则表达式</Label>
              <Input
                id="term"
                value={form.term}
                onChange={(e) => setForm((f) => ({ ...f, term: e.target.value }))}
                placeholder="例如：全网最低、\d+元"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="category">分类</Label>
              <Input
                id="category"
                value={form.category}
                onChange={(e) => setForm((f) => ({ ...f, category: e.target.value }))}
                placeholder="例如：广告极限词、违规引流、价格"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>严重级别</Label>
                <Select value={form.severity} onValueChange={(v) => setForm((f) => ({ ...f, severity: v as Severity }))}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(Object.keys(SEVERITY) as Severity[]).map((key) => (
                      <SelectItem key={key} value={key}>
                        {SEVERITY[key].label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label>匹配方式</Label>
                <Select value={form.matchType} onValueChange={(v) => setForm((f) => ({ ...f, matchType: v as MatchType }))}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(Object.keys(MATCH_TYPE) as MatchType[]).map((key) => (
                      <SelectItem key={key} value={key}>
                        {MATCH_TYPE[key]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <Alert variant="info">
              <Sparkles />
              <AlertDescription>
                价格类规则可选择“语义”，词条填写“价格”或“金额”。系统会召回 1块钱、29.9、¥29.9 等候选，再由 AI 按上下文判断。
              </AlertDescription>
            </Alert>

            <div className="flex items-center justify-between rounded-md border border-border bg-muted/30 px-3 py-2.5">
              <div>
                <Label className="cursor-default">启用状态</Label>
                <div className="mt-0.5 text-[12px] text-muted-foreground">停用后不参与后续检测召回</div>
              </div>
              <Switch checked={form.enabled} onCheckedChange={(v) => setForm((f) => ({ ...f, enabled: v }))} />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="variants">变体词</Label>
              <Textarea
                id="variants"
                rows={3}
                value={form.variants}
                onChange={(e) => setForm((f) => ({ ...f, variants: e.target.value }))}
                placeholder="仅变体匹配使用，多个词用逗号或换行分隔"
              />
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={submit} disabled={saving}>
              {saving && <RefreshCw className="h-4 w-4 animate-spin" />}
              {editing ? '保存修改' : '创建'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认 */}
      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除违规词</AlertDialogTitle>
            <AlertDialogDescription>
              确认删除「{deleteTarget?.term}」？删除后将不再参与后续检测召回。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={confirmDelete}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
