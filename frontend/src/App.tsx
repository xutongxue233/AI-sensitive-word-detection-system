import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  CheckCheck,
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
  batchDeleteTerms,
  batchDeleteVideos,
  batchEnqueueExportTasks,
  batchStartJobs,
  batchUpdateClipSuggestions,
  batchUpdateTerms,
  createClipSuggestions,
  createManualClipSuggestion,
  createTerm,
  deleteTerm,
  deleteVideo,
  enqueueExportTask,
  exportContentUrl,
  exportVideo,
  generateTermsWithAi,
  getErrorMessage,
  getJob,
  getSystemHealth,
  getVideo,
  importTerms,
  listExportTasks,
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
  videoExportContentUrl,
  videoContentUrl
} from './api';
import { SettingsDialog } from '@/components/SettingsDialog';
import type {
  ClipStatus,
  ClipSuggestion,
  ClipSuggestionBatchItem,
  BatchOperationResult,
  DetectionJob,
  ExportTask,
  GeneratedTerm,
  HealthStatus,
  ManualClipSuggestionRequest,
  MatchType,
  Severity,
  TermHit,
  TimelineItem,
  TranscriptSource,
  TranscriptSegment,
  TranscriptWord,
  VideoFile,
  ViolationTerm,
  SystemHealth
} from './types';

import { cn } from '@/lib/utils';
import { formatBytes, formatClock, formatDateTime, formatDuration, formatTimeShort, seconds } from '@/lib/format';
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

/**
 * 左侧导航与顶栏共用的页面元数据。
 * key 决定主区渲染哪个页面;eyebrow 是顶栏上方的英文小标识;title/desc 用于顶栏标题区。
 */
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

/**
 * 把后端 {@link TranscriptSource} 三类转写来源映射为前端中文标签与色调。
 * 三类对应不同的导出处理方式:AUDIO/SUBTITLE_FILE 命中按删音频段处理,VIDEO_SUBTITLE 命中按去字幕(遮盖)处理。
 */
const TRANSCRIPT_SOURCE: Record<TranscriptSource, { label: string; tone: 'neutral' | 'primary' | 'success' | 'info' | 'warn' | 'danger' }> = {
  AUDIO: { label: '音频', tone: 'primary' },
  SUBTITLE_FILE: { label: '字幕文件', tone: 'info' },
  VIDEO_SUBTITLE: { label: '画面字幕', tone: 'warn' }
};

interface ManualWordSelection {
  segmentId: number;
  startIndex: number;
  endIndex: number;
}

interface ManualClipDraft extends ManualClipSuggestionRequest {
  source: TranscriptSource;
}

function exportDownloadName(video: VideoFile | null) {
  const base = (video?.originalFilename || `video-${video?.id ?? 'export'}`)
    .replace(/\.[^/.\\]+$/, '')
    .replace(/[\\/:*?"<>|]+/g, '_')
    .trim();
  return `${base || 'export'}-去违规版本.mp4`;
}

function joinTranscriptWords(words: TranscriptWord[]) {
  const values = words.map((word) => word.word.trim()).filter(Boolean);
  const compact = values.join('');
  return /[\u4e00-\u9fff]/.test(compact) ? compact : values.join(' ');
}

function segmentDraft(segment: TranscriptSegment): ManualClipDraft {
  return {
    segmentId: segment.id,
    matchedText: segment.text,
    startTime: segment.startTime,
    endTime: segment.endTime,
    source: segment.source
  };
}

function showBatchResult(result: BatchOperationResult, successText: string) {
  if (result.failed > 0) {
    toast.success(`${successText} ${result.succeeded} 条，失败 ${result.failed} 条`);
    return;
  }
  toast.success(`${successText} ${result.succeeded} 条`);
}

/**
 * 管理明暗主题。
 * 初值取自 document.documentElement 上的 dark class,以兼容首屏内联脚本提前设置的主题(避免闪烁);
 * 主题变更时切换 root 的 dark class 并持久化到 localStorage('vm-theme')。
 */
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
      /* 隐私模式下 localStorage 不可写,忽略即可 */
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

const HEALTH_STATUS_META: Record<HealthStatus, { label: string; dot: string; text: string; panel: string }> = {
  OK: {
    label: '正常',
    dot: 'bg-[hsl(var(--sidebar-ring))]',
    text: 'text-sidebar-foreground/55',
    panel: 'border-[hsl(var(--sidebar-ring))]/25 bg-[hsl(var(--sidebar-ring))]/10 text-[hsl(var(--sidebar-ring))]'
  },
  WARN: {
    label: '预警',
    dot: 'bg-amber-400',
    text: 'text-amber-200',
    panel: 'border-amber-400/25 bg-amber-400/10 text-amber-100'
  },
  DOWN: {
    label: '异常',
    dot: 'bg-red-400',
    text: 'text-red-200',
    panel: 'border-red-400/25 bg-red-400/10 text-red-100'
  }
};

function Sidebar({
  active,
  onNavigate,
  health,
  healthLoading,
  onRefreshHealth
}: {
  active: NavKey;
  onNavigate: (key: NavKey) => void;
  health: SystemHealth | null;
  healthLoading: boolean;
  onRefreshHealth: () => void;
}) {
  const status = health?.status ?? 'WARN';
  const statusMeta = HEALTH_STATUS_META[status];
  const healthItems =
    health?.items?.length
      ? health.items
      : [{ key: 'pending', label: '后端状态', status: healthLoading ? 'WARN' : 'DOWN', message: healthLoading ? '检查中' : '等待检查' } as const];

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
          <div className="flex items-center justify-between gap-2">
            <Eyebrow className="text-sidebar-foreground/50">系统状态</Eyebrow>
            <button
              type="button"
              onClick={onRefreshHealth}
              disabled={healthLoading}
              className="rounded-md p-1 text-sidebar-foreground/45 transition-colors hover:bg-white/5 hover:text-sidebar-foreground disabled:cursor-not-allowed disabled:opacity-50"
              title="刷新系统状态"
              aria-label="刷新系统状态"
            >
              <RefreshCw className={cn('h-3.5 w-3.5', healthLoading && 'animate-spin')} />
            </button>
          </div>
          <div className={cn('mt-2 inline-flex items-center rounded-md border px-2 py-1 text-[11px] font-medium', statusMeta.panel)}>
            {healthLoading ? '检查中' : statusMeta.label}
          </div>
          <div className="mt-2.5 space-y-2 text-[12px]">
            {healthItems.map((item) => (
              <StatusLine key={item.key} label={item.label} status={item.status} note={item.message} />
            ))}
          </div>
        </div>
        <div className="px-1 text-[11px] text-sidebar-foreground/40">
          <span className="telemetry">v0.0.1</span> · Whisper 时间轴审核台
        </div>
      </div>
    </aside>
  );
}

function StatusLine({ label, status, note }: { label: string; status: HealthStatus; note?: string }) {
  const meta = HEALTH_STATUS_META[status];
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-sidebar-foreground/70">{label}</span>
      <span className="min-w-0 flex items-center gap-1.5" title={note}>
        <span
          className={cn(
            'h-1.5 w-1.5 rounded-full',
            meta.dot,
            status === 'OK' && 'animate-pulse-ring'
          )}
        />
        <span className={cn('telemetry max-w-[104px] truncate text-right text-[10px] uppercase', meta.text)}>
          {note ?? meta.label}
        </span>
      </span>
    </div>
  );
}

/**
 * 顶栏:展示当前页面标题区、小屏导航、实时时钟与设置/主题切换按钮。
 *
 * @param active 当前激活的页面 key,用于查 {@link NAV} 取标题元数据
 * @param onNavigate 小屏导航切换页面回调
 * @param theme 当前主题,决定切换按钮显示太阳/月亮图标
 * @param onToggleTheme 切换明暗主题回调
 * @param onOpenSettings 打开系统设置弹窗回调
 */
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
    // 每秒刷新时钟;卸载时清理定时器避免泄漏
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

/**
 * 根组件:持有导航激活页、主题与设置弹窗的开关状态,
 * 编排 {@link Sidebar} / {@link TopBar} 与主区({@link VideosPage} 或 {@link TermsPage})。
 */
export default function App() {
  const { theme, toggle } = useTheme();
  const [active, setActive] = useState<NavKey>('videos');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [systemHealth, setSystemHealth] = useState<SystemHealth | null>(null);
  const [healthLoading, setHealthLoading] = useState(false);

  const refreshSystemHealth = useCallback(async () => {
    setHealthLoading(true);
    try {
      setSystemHealth(await getSystemHealth());
    } catch (error) {
      setSystemHealth({
        status: 'DOWN',
        checkedAt: new Date().toISOString(),
        items: [
          {
            key: 'backend',
            label: '后端接口',
            status: 'DOWN',
            message: getErrorMessage(error, '接口不可达')
          }
        ]
      });
    } finally {
      setHealthLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshSystemHealth();
    const timer = window.setInterval(refreshSystemHealth, 30000);
    return () => window.clearInterval(timer);
  }, [refreshSystemHealth]);

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <Sidebar
        active={active}
        onNavigate={setActive}
        health={systemHealth}
        healthLoading={healthLoading}
        onRefreshHealth={refreshSystemHealth}
      />
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

/**
 * 视频检测工作台:上传视频与可选字幕文件、触发检测任务、轮询查看任务详情、导出去违规版本、删除视频。
 * 顶部展示队列统计卡片,下方为可分页的视频队列表格;点击「详情」在侧拉抽屉内渲染 {@link JobDetail}。
 */
/** 客户端分页:维护页码、按数据条数自动钳制页码到合法区间,并切出当前页数据。视频/命中/词库三处列表共用。 */
function usePagination<T>(items: T[], pageSize: number) {
  const [page, setPage] = useState(1);
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));
  // 数据条数变化(删除、检索等)后把页码钳制回合法区间,避免停留在已不存在的页
  useEffect(() => {
    setPage((p) => Math.min(p, pageCount));
  }, [pageCount]);
  const safePage = Math.min(page, pageCount);
  const pageItems = items.slice((safePage - 1) * pageSize, safePage * pageSize);
  return { page: safePage, setPage, pageCount, pageItems };
}

function VideosPage() {
  const [videos, setVideos] = useState<VideoFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [subtitleFile, setSubtitleFile] = useState<File | undefined>();
  const [selected, setSelected] = useState<{ videoId: number; jobId?: number } | null>(null);
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<number>>(new Set());
  const [deleteTarget, setDeleteTarget] = useState<VideoFile | null>(null);
  const [batchDeleteOpen, setBatchDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [batchingVideos, setBatchingVideos] = useState(false);
  const pageSize = 6;
  const { page, setPage, pageCount, pageItems } = usePagination(videos, pageSize);
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
    setSelectedVideoIds((current) => {
      const liveIds = new Set(videos.map((video) => video.id));
      const next = new Set([...current].filter((id) => liveIds.has(id)));
      return next.size === current.size ? current : next;
    });
  }, [videos]);

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
      const task = await enqueueExportTask(video.id);
      toast.success(`导出任务 #${task.id} 已入队`);
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导出入队失败'));
    }
  };

  const confirmDelete = async () => {
    const targets = deleteTarget ? [deleteTarget] : videos.filter((video) => selectedVideoIds.has(video.id));
    if (targets.length === 0) return;
    setDeleting(true);
    try {
      const result = await batchDeleteVideos(targets.map((video) => video.id));
      showBatchResult(result, '已删除视频');
      if (selected && targets.some((video) => video.id === selected.videoId)) {
        setSelected(null);
      }
      setDeleteTarget(null);
      setBatchDeleteOpen(false);
      setSelectedVideoIds(new Set());
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '删除失败'));
    } finally {
      setDeleting(false);
    }
  };

  const selectedVideos = useMemo(
    () => videos.filter((video) => selectedVideoIds.has(video.id)),
    [selectedVideoIds, videos]
  );
  const selectedExportableVideos = selectedVideos.filter(
    (video) => video.status === 'DETECTED' || video.status === 'EXPORTED'
  );
  const pageVideoIds = pageItems.map((video) => video.id);
  const pageVideosAllSelected = pageVideoIds.length > 0 && pageVideoIds.every((id) => selectedVideoIds.has(id));

  const toggleVideoSelection = (id: number, checked: boolean) => {
    setSelectedVideoIds((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  const togglePageVideoSelection = (checked: boolean) => {
    setSelectedVideoIds((current) => {
      const next = new Set(current);
      pageVideoIds.forEach((id) => {
        if (checked) {
          next.add(id);
        } else {
          next.delete(id);
        }
      });
      return next;
    });
  };

  const batchDetectVideos = async () => {
    const targets = selectedVideos.filter((video) => video.status !== 'DETECTING');
    if (targets.length === 0) {
      toast.error('请选择可检测的视频');
      return;
    }
    setBatchingVideos(true);
    try {
      const result = await batchStartJobs(targets.map((video) => video.id));
      showBatchResult(result, '已启动检测');
      load();
    } finally {
      setBatchingVideos(false);
    }
  };

  const batchExportVideos = async () => {
    if (selectedExportableVideos.length === 0) {
      toast.error('请选择已检测完成或已导出的视频');
      return;
    }
    setBatchingVideos(true);
    try {
      const result = await batchEnqueueExportTasks(selectedExportableVideos.map((video) => video.id));
      showBatchResult(result, '已创建导出任务');
      load();
    } finally {
      setBatchingVideos(false);
    }
  };

  const stats = useMemo(() => {
    const done = videos.filter((v) => v.status === 'DETECTED' || v.status === 'EXPORTED').length;
    const processing = videos.filter((v) => v.status === 'DETECTING').length;
    const exported = videos.filter((v) => v.status === 'EXPORTED').length;
    return { total: videos.length, done, processing, exported };
  }, [videos]);

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
              {selectedVideoIds.size > 0 && (
                <div className="mb-3 flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/30 px-3 py-2">
                  <Pill tone="info" dot={false}>
                    已选 <span className="telemetry">{selectedVideoIds.size}</span>
                  </Pill>
                  <Button variant="outline" size="sm" onClick={batchDetectVideos} disabled={batchingVideos}>
                    <Play className="h-4 w-4" />
                    批量检测
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={batchExportVideos}
                    disabled={batchingVideos || selectedExportableVideos.length === 0}
                  >
                    <Scissors className="h-4 w-4" />
                    批量导出
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-muted-foreground hover:text-destructive"
                    onClick={() => setBatchDeleteOpen(true)}
                    disabled={batchingVideos}
                  >
                    <Trash2 className="h-4 w-4" />
                    批量删除
                  </Button>
                  <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setSelectedVideoIds(new Set())}>
                    清空选择
                  </Button>
                </div>
              )}
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-[44px]">
                      <input
                        type="checkbox"
                        checked={pageVideosAllSelected}
                        onChange={(e) => togglePageVideoSelection(e.target.checked)}
                        aria-label="选择当前页视频"
                        className="h-4 w-4 rounded border-border accent-primary"
                      />
                    </TableHead>
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
                    const downloadName = exportDownloadName(video);
                    const downloadUrl = videoExportContentUrl(video.id);
                    return (
                      <TableRow key={video.id}>
                        <TableCell>
                          <input
                            type="checkbox"
                            checked={selectedVideoIds.has(video.id)}
                            onChange={(e) => toggleVideoSelection(video.id, e.target.checked)}
                            aria-label={`选择 ${video.originalFilename}`}
                            className="h-4 w-4 rounded border-border accent-primary"
                          />
                        </TableCell>
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
                            {video.status === 'EXPORTED' && (
                              <Button asChild variant="ghost" size="icon-sm">
                                <a href={downloadUrl} download={downloadName} aria-label="下载去违规视频" title="下载去违规视频">
                                  <Download className="h-4 w-4" />
                                </a>
                              </Button>
                            )}
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              onClick={() => exportModerated(video)}
                              aria-label={video.status === 'EXPORTED' ? '重新生成剪辑视频' : '导出去违规视频'}
                              title={video.status === 'EXPORTED' ? '重新生成剪辑视频' : '导出去违规视频'}
                            >
                              <Scissors className="h-4 w-4" />
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
              <DataPagination page={page} pageSize={pageSize} total={videos.length} onPageChange={setPage} />
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

      <AlertDialog
        open={!!deleteTarget || batchDeleteOpen}
        onOpenChange={(open) => {
          if (!open && !deleting) {
            setDeleteTarget(null);
            setBatchDeleteOpen(false);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除视频</AlertDialogTitle>
            <AlertDialogDescription>
              {deleteTarget
                ? `将删除「${deleteTarget.originalFilename}」及其全部检测任务、命中记录、剪辑建议与原始文件，此操作不可恢复。`
                : `将删除选中的 ${selectedVideoIds.size} 个视频及其全部检测任务、命中记录、剪辑建议与原始文件，此操作不可恢复。`}
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

/**
 * 单个检测任务详情:原始视频与去违规版本对照、任务进度轮询、违规词时间轴、剪辑建议(可编辑/确认/重生成)、命中与 AI 复核明细、字幕片段。
 *
 * @param videoId 所属视频 id
 * @param initialJobId 初次打开时已知的任务 id;缺省时取该视频最近一次任务
 */
function JobDetail({ videoId, initialJobId }: { videoId: number; initialJobId?: number }) {
  const [video, setVideo] = useState<VideoFile | null>(null);
  const [job, setJob] = useState<DetectionJob | null>(null);
  const [segments, setSegments] = useState<TranscriptSegment[]>([]);
  const [hits, setHits] = useState<TermHit[]>([]);
  const [timeline, setTimeline] = useState<TimelineItem[]>([]);
  const [suggestions, setSuggestions] = useState<ClipSuggestion[]>([]);
  const [exportTasks, setExportTasks] = useState<ExportTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportPreviewVersion, setExportPreviewVersion] = useState(0);
  const [manualSelection, setManualSelection] = useState<ManualWordSelection | null>(null);
  const [addingManualSuggestion, setAddingManualSuggestion] = useState(false);
  const [selectedSuggestionIds, setSelectedSuggestionIds] = useState<Set<number>>(new Set());
  const [activeEvidenceHitId, setActiveEvidenceHitId] = useState<number | null>(null);
  const [activeSuggestionId, setActiveSuggestionId] = useState<number | null>(null);
  const [batchingSuggestions, setBatchingSuggestions] = useState(false);
  const hitPageSize = 6;
  const { page: hitPage, setPage: setHitPage, pageCount: hitPageCount, pageItems: hitItems } = usePagination(hits, hitPageSize);

  const activeJobId = job?.id ?? initialJobId;

  const selectedManualDraft = useMemo<ManualClipDraft | null>(() => {
    if (!manualSelection) return null;
    const segment = segments.find((item) => item.id === manualSelection.segmentId);
    if (!segment) return null;
    const words = segment.words ?? [];
    if (words.length === 0) return segmentDraft(segment);
    const from = Math.max(0, Math.min(manualSelection.startIndex, manualSelection.endIndex));
    const to = Math.min(words.length - 1, Math.max(manualSelection.startIndex, manualSelection.endIndex));
    const selectedWords = words.slice(from, to + 1);
    if (selectedWords.length === 0) return null;
    return {
      segmentId: segment.id,
      matchedText: joinTranscriptWords(selectedWords),
      startTime: Math.min(...selectedWords.map((word) => word.startTime)),
      endTime: Math.max(...selectedWords.map((word) => word.endTime)),
      source: segment.source
    };
  }, [manualSelection, segments]);

  const load = async () => {
    setLoading(true);
    try {
      const currentVideo = await getVideo(videoId);
      setVideo(currentVideo);
      setExportTasks(await listExportTasks(videoId));
      let jobId = job?.id ?? initialJobId;
      if (!jobId) {
        const jobs = await listJobs(videoId);
        jobId = jobs[0]?.id;
      }
      if (jobId) {
        const currentJob = await getJob(jobId);
        setJob(currentJob);
        // 仅终态(COMPLETED/FAILED)才并行拉取四类结果数据;非终态结果尚不完整,继续轮询即可
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

  useEffect(() => {
    setSelectedSuggestionIds((current) => {
      const editableIds = new Set(suggestions.filter((item) => item.status !== 'EXPORTED').map((item) => item.id));
      const next = new Set([...current].filter((id) => editableIds.has(id)));
      return next.size === current.size ? current : next;
    });
  }, [suggestions]);

  useEffect(() => {
    setActiveEvidenceHitId((current) => {
      if (current && timeline.some((item) => item.hitId === current)) {
        return current;
      }
      return timeline[0]?.hitId ?? null;
    });
  }, [timeline]);

  useEffect(() => {
    setActiveSuggestionId((current) => {
      if (current && suggestions.some((item) => item.id === current)) {
        return current;
      }
      return suggestions.find((item) => item.status !== 'EXPORTED')?.id ?? suggestions[0]?.id ?? null;
    });
  }, [suggestions]);

  const loadRef = useRef(load);
  useEffect(() => {
    // loadRef 始终镜像最新的 load,供下方 setInterval 闭包调用,避免捕获到陈旧的 load 引用
    loadRef.current = load;
  });

  useEffect(() => {
    if (!job || job.status === 'COMPLETED' || job.status === 'FAILED') {
      return undefined;
    }
    // 非终态任务每 2.5s 轮询一次进度,直至完成/失败后由上面的守卫停止
    const timer = window.setInterval(() => loadRef.current(), 2500);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.id, job?.status]);

  useEffect(() => {
    if (!exportTasks.some((task) => task.status === 'QUEUED' || task.status === 'RUNNING')) {
      return undefined;
    }
    const timer = window.setInterval(() => loadRef.current(), 2500);
    return () => window.clearInterval(timer);
  }, [exportTasks]);

  const duration = useMemo(() => {
    // 时间轴总时长取视频时长、各违规命中结束时间、各句段结束时间三者最大值,并兜底 1 防止后续按比例换算时除零
    const ends = [
      video?.durationSeconds ?? 0,
      ...timeline.map((t) => t.endTime + 1),
      ...segments.map((s) => s.endTime)
    ];
    return Math.max(1, ...ends);
  }, [video, timeline, segments]);

  const activeEvidence = useMemo(() => {
    if (timeline.length === 0) return null;
    return timeline.find((item) => item.hitId === activeEvidenceHitId) ?? timeline[0];
  }, [activeEvidenceHitId, timeline]);

  // 检测耗时:运行中取「现在 - 开始」(随 2.5s 轮询刷新),终态取「完成 - 开始」定格
  const jobElapsedSeconds = job?.startedAt
    ? Math.max(0, ((job.completedAt ? new Date(job.completedAt) : new Date()).getTime() - new Date(job.startedAt).getTime()) / 1000)
    : null;
  const activeEvidenceHit = activeEvidence ? hits.find((item) => item.id === activeEvidence.hitId) : undefined;
  const activeEvidenceSuggestion = activeEvidence
    ? suggestions.find((item) => item.hitId === activeEvidence.hitId)
    : undefined;
  const activeEvidenceSegment = activeEvidenceHit
    ? segments.find((item) => item.id === activeEvidenceHit.segmentId)
    : undefined;
  const latestExportTask = exportTasks[0];
  const runningExportTask = exportTasks.find((task) => task.status === 'QUEUED' || task.status === 'RUNNING');
  const completedExportTask = exportTasks.find((task) => task.status === 'COMPLETED' && task.exportPath);

  const regenerateSuggestions = async () => {
    if (!activeJobId) return;
    try {
      setSuggestions(await createClipSuggestions(activeJobId));
      toast.success('剪辑建议已重新生成');
    } catch (error) {
      toast.error(getErrorMessage(error, '重新生成失败'));
    }
  };

  const selectTranscriptWord = (segment: TranscriptSegment, wordIndex: number) => {
    setManualSelection((current) => {
      if (current?.segmentId !== segment.id) {
        return { segmentId: segment.id, startIndex: wordIndex, endIndex: wordIndex };
      }
      if (current.startIndex === wordIndex && current.endIndex === wordIndex) {
        return null;
      }
      return {
        segmentId: segment.id,
        startIndex: Math.min(current.startIndex, wordIndex),
        endIndex: Math.max(current.startIndex, wordIndex)
      };
    });
  };

  const refreshReviewData = async (jobId: number) => {
    const [nextHits, nextTimeline, nextSuggestions] = await Promise.all([
      listHits(jobId),
      listTimeline(jobId),
      listClipSuggestions(jobId)
    ]);
    setHits(nextHits);
    setTimeline(nextTimeline);
    setSuggestions(nextSuggestions);
  };

  const addManualSuggestion = async (draft = selectedManualDraft) => {
    if (!activeJobId || !draft) return;
    setAddingManualSuggestion(true);
    try {
      await createManualClipSuggestion(activeJobId, {
        segmentId: draft.segmentId,
        matchedText: draft.matchedText,
        startTime: draft.startTime,
        endTime: draft.endTime
      });
      await refreshReviewData(activeJobId);
      setManualSelection(null);
      toast.success(`已添加“${draft.matchedText}”到剪辑建议`);
    } catch (error) {
      toast.error(getErrorMessage(error, '添加剪辑建议失败'));
    } finally {
      setAddingManualSuggestion(false);
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

  const selectableSuggestions = suggestions.filter((item) => item.status !== 'EXPORTED');
  const selectedSuggestions = suggestions.filter((item) => selectedSuggestionIds.has(item.id) && item.status !== 'EXPORTED');
  const suggestionsAllSelected =
    selectableSuggestions.length > 0 && selectableSuggestions.every((item) => selectedSuggestionIds.has(item.id));

  const toggleSuggestionSelection = (id: number, checked: boolean) => {
    setSelectedSuggestionIds((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  const toggleAllSuggestions = (checked: boolean) => {
    setSelectedSuggestionIds((current) => {
      const next = new Set(current);
      selectableSuggestions.forEach((item) => {
        if (checked) {
          next.add(item.id);
        } else {
          next.delete(item.id);
        }
      });
      return next;
    });
  };

  const batchSaveSuggestions = async (status: ClipStatus) => {
    if (selectedSuggestions.length === 0) {
      toast.error('请选择剪辑建议');
      return;
    }
    setBatchingSuggestions(true);
    try {
      const payload: ClipSuggestionBatchItem[] = selectedSuggestions.map((item) => ({
        id: item.id,
        startTime: item.startTime,
        endTime: item.endTime,
        status
      }));
      const result = await batchUpdateClipSuggestions(payload);
      if (activeJobId) {
        setSuggestions(await listClipSuggestions(activeJobId));
      }
      setSelectedSuggestionIds(new Set());
      showBatchResult(result, status === 'CONFIRMED' ? '已确认剪辑建议' : '已忽略剪辑建议');
    } finally {
      setBatchingSuggestions(false);
    }
  };

  const confirmAllSuggestions = async () => {
    const pending = suggestions.filter((item) => item.status === 'PENDING');
    if (pending.length === 0) {
      toast.success('没有需要确认的剪辑建议');
      return;
    }
    try {
      await batchUpdateClipSuggestions(
        pending.map((item) => ({ id: item.id, startTime: item.startTime, endTime: item.endTime, status: 'CONFIRMED' }))
      );
      if (activeJobId) setSuggestions(await listClipSuggestions(activeJobId));
      toast.success(`已确认 ${pending.length} 条剪辑建议`);
    } catch (error) {
      toast.error(getErrorMessage(error, '一键确认失败'));
    }
  };

  const handleExport = async () => {
    if (!video) return;
    setExporting(true);
    try {
      const task = await enqueueExportTask(video.id);
      toast.success(`导出任务 #${task.id} 已入队`);
      setExportPreviewVersion(Date.now());
      setExportTasks(await listExportTasks(video.id));
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导出入队失败'));
    } finally {
      setExporting(false);
    }
  };

  const patchSuggestion = (id: number, patch: Partial<ClipSuggestion>) =>
    setSuggestions((items) => items.map((item) => (item.id === id ? { ...item, ...patch } : item)));

  const exportedSuggestion = suggestions.find((item) => item.status === 'EXPORTED');
  const exportedVersion = completedExportTask?.exportPath ?? exportedSuggestion?.exportPath ?? (exportPreviewVersion || undefined);
  const exportedDownloadUrl =
    video && (completedExportTask || exportedSuggestion) ? videoExportContentUrl(video.id, exportedVersion) : undefined;
  const exportedDownloadName = exportDownloadName(video);
  const jobStatus = job ? JOB_STATUS[job.status] : null;
  const violationCount = timeline.length;

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
              {exportedDownloadUrl ? (
                <VideoFrame
                  src={exportedDownloadUrl}
                  label="去违规版本"
                  icon={Scissors}
                  action={
                    <Button
                      asChild
                      variant="ghost"
                      size="xs"
                      className="h-7 px-2 text-white/70 hover:bg-white/10 hover:text-white"
                    >
                      <a href={exportedDownloadUrl} download={exportedDownloadName} aria-label="下载去违规视频">
                        <Download className="h-3.5 w-3.5" />
                        下载
                      </a>
                    </Button>
                  }
                />
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
                    {jobElapsedSeconds != null && (
                      <span className="text-[12px] text-muted-foreground">
                        {job.completedAt ? `耗时 ${formatDuration(jobElapsedSeconds)}` : `已用 ${formatDuration(jobElapsedSeconds)}`}
                      </span>
                    )}
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
                <div className="flex flex-wrap items-center gap-2 self-start">
                  {exportedDownloadUrl && (
                    <Button asChild variant="default" size="sm">
                      <a href={exportedDownloadUrl} download={exportedDownloadName}>
                        <Download className="h-4 w-4" />
                        下载去违规视频
                      </a>
                    </Button>
                  )}
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={exporting || job.status !== 'COMPLETED'}
                    onClick={handleExport}
                  >
                    {exporting ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Scissors className="h-4 w-4" />}
                    {exportedDownloadUrl ? '重新生成剪辑视频' : '生成剪辑视频'}
                  </Button>
                </div>
                {latestExportTask && (
                  <div className="basis-full rounded-md border border-border bg-muted/25 px-3 py-2">
                    <div className="flex flex-wrap items-center gap-2 text-[12px]">
                      <span className="font-medium">导出队列</span>
                      <Pill
                        tone={
                          latestExportTask.status === 'COMPLETED'
                            ? 'success'
                            : latestExportTask.status === 'FAILED'
                              ? 'danger'
                              : 'info'
                        }
                        pulse={latestExportTask.status === 'QUEUED' || latestExportTask.status === 'RUNNING'}
                      >
                        #{latestExportTask.id} {latestExportTask.status}
                      </Pill>
                      <span className="telemetry text-muted-foreground">{latestExportTask.progress}%</span>
                      {runningExportTask && <span className="text-muted-foreground">后台生成中，完成后自动刷新预览</span>}
                      {completedExportTask?.removedClipCount != null && (
                        <span className="text-muted-foreground">
                          最近完成处理 <span className="telemetry text-foreground">{completedExportTask.removedClipCount}</span> 段
                        </span>
                      )}
                      {exportTasks.length > 1 && (
                        <span className="ml-auto text-muted-foreground">
                          历史任务 <span className="telemetry text-foreground">{exportTasks.length}</span>
                        </span>
                      )}
                    </div>
                    {latestExportTask.errorMessage && (
                      <div className="mt-1 truncate text-[12px] text-destructive">{latestExportTask.errorMessage}</div>
                    )}
                  </div>
                )}
              </CardContent>
            </Card>
          ) : (
            <Alert variant="info">
              <Sparkles />
              <AlertDescription>该视频还没有检测任务，可以在视频列表点击“检测”。</AlertDescription>
            </Alert>
          )}

          <Card>
            <CardHeader className="flex-row items-center justify-between gap-3 space-y-0">
              <div className="flex items-center gap-2">
                <ShieldAlert className="h-4 w-4 text-muted-foreground" />
                <CardTitle>违规证据预览</CardTitle>
              </div>
              {activeEvidence && (
                <div className="telemetry text-[12px] text-muted-foreground">
                  {seconds(activeEvidence.startTime)} - {seconds(activeEvidence.endTime)}
                </div>
              )}
            </CardHeader>
            <CardContent>
              {!video || !activeEvidence ? (
                <EmptyState icon={ShieldAlert} title="暂无可预览证据" description="完成检测并产生违规命中后，可在这里预览证据片段。" />
              ) : (
                <div className="grid gap-4 lg:grid-cols-[minmax(280px,0.9fr)_1.1fr]">
                  <VideoFrame
                    src={`${videoContentUrl(video.id)}#t=${Math.max(0, activeEvidence.startTime).toFixed(2)}`}
                    label="证据定位"
                    icon={Film}
                  />
                  <div className="space-y-3 rounded-lg border border-border bg-muted/20 p-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <SeverityBadge severity={activeEvidence.severity} />
                      <Pill tone={TRANSCRIPT_SOURCE[activeEvidence.source]?.tone ?? 'neutral'} dot={false}>
                        {TRANSCRIPT_SOURCE[activeEvidence.source]?.label ?? activeEvidence.source}
                      </Pill>
                      <Pill tone={REVIEW_STATUS[activeEvidence.reviewStatus].tone} dot={false}>
                        {REVIEW_STATUS[activeEvidence.reviewStatus].label}
                      </Pill>
                      {activeEvidence.aiConfidence !== undefined && (
                        <Pill tone="info" dot={false}>
                          AI <span className="telemetry">{Math.round(activeEvidence.aiConfidence * 100)}%</span>
                        </Pill>
                      )}
                    </div>
                    <div>
                      <div className="text-[12px] text-muted-foreground">命中文本</div>
                      <div className="mt-1 text-lg font-semibold">{activeEvidence.matchedText}</div>
                    </div>
                    <div className="grid gap-2 text-[13px]">
                      <div>
                        <span className="text-muted-foreground">上下文：</span>
                        <span>{activeEvidence.contextText || activeEvidenceHit?.contextText || activeEvidenceSegment?.text || '-'}</span>
                      </div>
                      <div>
                        <span className="text-muted-foreground">AI 原因：</span>
                        <span>{activeEvidence.aiReason || activeEvidenceHit?.aiReview?.reason || '-'}</span>
                      </div>
                      <div>
                        <span className="text-muted-foreground">处置建议：</span>
                        <span>
                          {activeEvidenceSuggestion
                            ? `${activeEvidenceSuggestion.action === 'BLUR_SUBTITLE' ? '遮盖字幕' : '删除音频段'}，${seconds(activeEvidenceSuggestion.startTime)} - ${seconds(activeEvidenceSuggestion.endTime)}`
                            : '尚未生成剪辑建议'}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

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
                                onClick={() => setActiveEvidenceHitId(item.hitId)}
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
                        role="button"
                        tabIndex={0}
                        onClick={() => setActiveEvidenceHitId(item.hitId)}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            setActiveEvidenceHitId(item.hitId);
                          }
                        }}
                        className={cn(
                          'flex items-center gap-3 rounded-md border bg-card px-3 py-2 transition-colors',
                          activeEvidenceHitId === item.hitId ? 'border-primary/50 bg-primary/5' : 'border-border'
                        )}
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
              <div className="flex items-center gap-2">
                <Button
                  variant="default"
                  size="sm"
                  onClick={confirmAllSuggestions}
                  disabled={!activeJobId || !suggestions.some((item) => item.status === 'PENDING')}
                >
                  <CheckCheck className="h-4 w-4" />
                  一键确认
                </Button>
                <Button variant="outline" size="sm" onClick={regenerateSuggestions} disabled={!activeJobId}>
                  <Wand2 className="h-4 w-4" />
                  重新生成
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              {suggestions.length === 0 ? (
                <EmptyState icon={Scissors} title="暂无剪辑建议" description="检测完成并存在违规命中后将生成剪辑建议。" />
              ) : (
                <>
                  <ClipTimelineEditor
                    suggestions={suggestions}
                    duration={duration}
                    activeId={activeSuggestionId}
                    onActiveChange={setActiveSuggestionId}
                    onPatch={patchSuggestion}
                    onSave={saveSuggestion}
                  />
                  {selectedSuggestionIds.size > 0 && (
                    <div className="mb-3 flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/30 px-3 py-2">
                      <Pill tone="info" dot={false}>
                        已选 <span className="telemetry">{selectedSuggestionIds.size}</span>
                      </Pill>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => batchSaveSuggestions('CONFIRMED')}
                        disabled={batchingSuggestions}
                      >
                        <CheckCheck className="h-4 w-4" />
                        批量确认
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => batchSaveSuggestions('IGNORED')}
                        disabled={batchingSuggestions}
                      >
                        批量忽略
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="ml-auto"
                        onClick={() => setSelectedSuggestionIds(new Set())}
                      >
                        清空选择
                      </Button>
                    </div>
                  )}
                  <Table>
                    <TableHeader>
                      <TableRow className="hover:bg-transparent">
                        <TableHead className="w-[44px]">
                          <input
                            type="checkbox"
                            checked={suggestionsAllSelected}
                            onChange={(e) => toggleAllSuggestions(e.target.checked)}
                            aria-label="选择全部剪辑建议"
                            className="h-4 w-4 rounded border-border accent-primary"
                          />
                        </TableHead>
                        <TableHead>违规词</TableHead>
                        <TableHead className="w-[96px]">处理</TableHead>
                        <TableHead className="w-[150px]">开始 (秒)</TableHead>
                        <TableHead className="w-[150px]">结束 (秒)</TableHead>
                        <TableHead className="w-[88px]">置信度</TableHead>
                        <TableHead className="w-[96px]">状态</TableHead>
                        <TableHead className="w-[220px] text-right">操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {suggestions.map((record) => {
                        const status = CLIP_STATUS[record.status];
                        const locked = record.status === 'EXPORTED';
                        const segmentStartTime = record.segmentStartTime;
                        const segmentEndTime = record.segmentEndTime;
                        const canUseSegmentRange =
                          record.action === 'BLUR_SUBTITLE' &&
                          segmentStartTime != null &&
                          segmentEndTime != null &&
                          segmentEndTime > segmentStartTime;
                        const suggestionDownloadUrl = record.status === 'EXPORTED' && record.exportPath
                          ? exportContentUrl(record.id)
                          : undefined;
                        return (
                          <TableRow key={record.id} onClick={() => setActiveSuggestionId(record.id)}>
                            <TableCell>
                              <input
                                type="checkbox"
                                checked={selectedSuggestionIds.has(record.id)}
                                disabled={locked}
                                onChange={(e) => toggleSuggestionSelection(record.id, e.target.checked)}
                                aria-label={`选择 ${record.matchedText}`}
                                className="h-4 w-4 rounded border-border accent-primary disabled:opacity-40"
                              />
                            </TableCell>
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
                              max={Math.max(0, record.endTime - 0.1)}
                              step={0.1}
                              suffix="s"
                              className="w-[120px]"
                              disabled={locked}
                              name={`clip-${record.id}-start`}
                              aria-label={record.action === 'BLUR_SUBTITLE' ? '字幕遮挡开始时间（秒）' : '剪辑开始时间（秒）'}
                              onChange={(value) => patchSuggestion(record.id, { startTime: value })}
                            />
                          </TableCell>
                          <TableCell>
                            <NumberField
                              value={record.endTime}
                              min={record.startTime + 0.1}
                              max={duration}
                              step={0.1}
                              suffix="s"
                              className="w-[120px]"
                              disabled={locked}
                              name={`clip-${record.id}-end`}
                              aria-label={record.action === 'BLUR_SUBTITLE' ? '字幕遮挡结束时间（秒）' : '剪辑结束时间（秒）'}
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
                              {suggestionDownloadUrl ? (
                                <Button asChild variant="outline" size="sm">
                                  <a href={suggestionDownloadUrl} download={exportedDownloadName}>
                                    <Download className="h-4 w-4" />
                                    下载
                                  </a>
                                </Button>
                              ) : (
                                <>
                                  {canUseSegmentRange && (
                                    <Button
                                      variant="ghost"
                                      size="sm"
                                      disabled={locked}
                                      title="扩展到整条字幕出现时段"
                                      onClick={() => {
                                        if (segmentStartTime == null || segmentEndTime == null) return;
                                        patchSuggestion(record.id, {
                                          startTime: segmentStartTime,
                                          endTime: segmentEndTime
                                        });
                                      }}
                                    >
                                      <Captions className="h-4 w-4" />
                                      整句
                                    </Button>
                                  )}
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
                                </>
                              )}
                            </div>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
                </>
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
                    page={hitPage}
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
            <CardHeader className="flex-row items-center justify-between gap-3 space-y-0">
              <div className="flex items-center gap-2">
                <AudioLines className="h-4 w-4 text-muted-foreground" />
                <CardTitle>字幕片段</CardTitle>
              </div>
              <div className="flex min-w-0 items-center gap-2">
                {selectedManualDraft && (
                  <div className="hidden min-w-0 max-w-[260px] truncate rounded-md bg-muted px-2 py-1 text-[12px] text-muted-foreground md:block">
                    {selectedManualDraft.matchedText}
                  </div>
                )}
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!selectedManualDraft || addingManualSuggestion}
                  onClick={() => addManualSuggestion()}
                >
                  {addingManualSuggestion ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                  添加选词
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              {segments.length === 0 ? (
                <EmptyState icon={FileText} title="暂无字幕句段" description="检测完成后将展示转写得到的句段与时间码。" />
              ) : (
                <ScrollArea className="h-[340px] pr-3">
                  <div className="space-y-1.5">
                    {segments.map((segment) => {
                      const words = segment.words ?? [];
                      const isActiveSegment = manualSelection?.segmentId === segment.id;
                      const selectionStart = isActiveSegment
                        ? Math.min(manualSelection.startIndex, manualSelection.endIndex)
                        : -1;
                      const selectionEnd = isActiveSegment
                        ? Math.max(manualSelection.startIndex, manualSelection.endIndex)
                        : -1;
                      return (
                        <div
                          key={segment.id}
                          className={cn(
                            'rounded-md border px-3 py-2 transition-colors',
                            isActiveSegment ? 'border-primary/40 bg-primary/5' : 'border-border bg-card'
                          )}
                        >
                          <div className="mb-2 flex flex-wrap items-center gap-2">
                            <span className="telemetry shrink-0 rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                              {seconds(segment.startTime)} – {seconds(segment.endTime)}
                            </span>
                            <Pill tone={TRANSCRIPT_SOURCE[segment.source]?.tone ?? 'neutral'} dot={false}>
                              {TRANSCRIPT_SOURCE[segment.source]?.label ?? segment.source}
                            </Pill>
                            <Button
                              variant="ghost"
                              size="xs"
                              className="ml-auto"
                              disabled={addingManualSuggestion}
                              onClick={() => addManualSuggestion(segmentDraft(segment))}
                            >
                              <Plus className="h-3.5 w-3.5" />
                              整句
                            </Button>
                          </div>
                          {words.length > 0 ? (
                            <div className="flex flex-wrap gap-1">
                              {words.map((word, index) => {
                                const selected = isActiveSegment && index >= selectionStart && index <= selectionEnd;
                                return (
                                  <button
                                    key={word.id}
                                    type="button"
                                    className={cn(
                                      'min-h-7 rounded-md border px-2 py-1 text-[13px] leading-tight transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                                      selected
                                        ? 'border-primary bg-primary text-primary-foreground shadow-sm'
                                        : 'border-border bg-background hover:border-primary/50 hover:bg-primary/5'
                                    )}
                                    onClick={() => selectTranscriptWord(segment, index)}
                                  >
                                    {word.word}
                                  </button>
                                );
                              })}
                            </div>
                          ) : (
                            <div className="text-[13px] leading-relaxed">{segment.text}</div>
                          )}
                        </div>
                      );
                    })}
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

function ClipTimelineEditor({
  suggestions,
  duration,
  activeId,
  onActiveChange,
  onPatch,
  onSave
}: {
  suggestions: ClipSuggestion[];
  duration: number;
  activeId: number | null;
  onActiveChange: (id: number) => void;
  onPatch: (id: number, patch: Partial<ClipSuggestion>) => void;
  onSave: (record: ClipSuggestion, status: ClipStatus) => void;
}) {
  const active = suggestions.find((item) => item.id === activeId) ?? suggestions[0];
  if (!active) return null;
  const locked = active.status === 'EXPORTED';
  const segmentStartTime = active.segmentStartTime;
  const segmentEndTime = active.segmentEndTime;
  const canUseSegmentRange =
    active.action === 'BLUR_SUBTITLE' &&
    segmentStartTime != null &&
    segmentEndTime != null &&
    segmentEndTime > segmentStartTime &&
    !locked;

  const patchActive = (patch: Partial<ClipSuggestion>) => onPatch(active.id, patch);
  const moveStart = (delta: number) => patchActive({ startTime: Math.max(0, Math.min(active.endTime - 0.1, active.startTime + delta)) });
  const moveEnd = (delta: number) => patchActive({ endTime: Math.max(active.startTime + 0.1, Math.min(duration, active.endTime + delta)) });

  return (
    <div className="mb-4 rounded-lg border border-border bg-muted/20 p-3">
      <div className="mb-2 flex items-center justify-between gap-3">
        <div>
          <div className="text-sm font-medium">可视化时间轴编辑</div>
          <div className="text-[12px] text-muted-foreground">点击片段后微调范围，确认后参与后台导出队列。</div>
        </div>
        <div className="telemetry text-[11px] text-muted-foreground">{seconds(duration)}</div>
      </div>
      <div className="relative h-14 overflow-hidden rounded-md border border-border bg-background">
        <div className="absolute inset-x-0 top-1/2 h-px bg-border" />
        {suggestions.map((item) => {
          const left = Math.max(0, (item.startTime / duration) * 100);
          const width = Math.max(1.6, ((item.endTime - item.startTime) / duration) * 100);
          const isActive = item.id === active.id;
          const status = CLIP_STATUS[item.status];
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onActiveChange(item.id)}
              className={cn(
                'absolute top-3 h-8 rounded-[5px] border px-1 text-left transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                isActive ? 'z-10 border-primary bg-primary text-primary-foreground shadow-md' : 'border-border bg-card hover:border-primary/50',
                item.status === 'EXPORTED' && !isActive && 'bg-muted text-muted-foreground'
              )}
              style={{ left: `${left}%`, width: `${width}%` }}
              title={`${item.matchedText} ${seconds(item.startTime)} - ${seconds(item.endTime)}`}
            >
              <span className="block truncate text-[11px] font-medium">{item.matchedText}</span>
              <span className={cn('telemetry block truncate text-[10px]', isActive ? 'text-primary-foreground/75' : 'text-muted-foreground')}>
                {status.label}
              </span>
            </button>
          );
        })}
      </div>
      <div className="mt-2 flex justify-between">
        {[0, 0.25, 0.5, 0.75, 1].map((frac) => (
          <span key={frac} className="telemetry text-[10px] text-muted-foreground">
            {seconds(duration * frac)}
          </span>
        ))}
      </div>
      <div className="mt-3 grid gap-3 lg:grid-cols-[1fr_auto]">
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label>开始时间</Label>
            <div className="flex items-center gap-1.5">
              <Button variant="outline" size="icon-sm" disabled={locked} onClick={() => moveStart(-0.1)}>
                -
              </Button>
              <NumberField
                value={active.startTime}
                min={0}
                max={Math.max(0, active.endTime - 0.1)}
                step={0.1}
                suffix="s"
                className="w-full"
                disabled={locked}
                name={`timeline-${active.id}-start`}
                aria-label="时间轴编辑开始时间"
                onChange={(value) => patchActive({ startTime: value })}
              />
              <Button variant="outline" size="icon-sm" disabled={locked} onClick={() => moveStart(0.1)}>
                +
              </Button>
            </div>
          </div>
          <div className="space-y-1.5">
            <Label>结束时间</Label>
            <div className="flex items-center gap-1.5">
              <Button variant="outline" size="icon-sm" disabled={locked} onClick={() => moveEnd(-0.1)}>
                -
              </Button>
              <NumberField
                value={active.endTime}
                min={active.startTime + 0.1}
                max={duration}
                step={0.1}
                suffix="s"
                className="w-full"
                disabled={locked}
                name={`timeline-${active.id}-end`}
                aria-label="时间轴编辑结束时间"
                onChange={(value) => patchActive({ endTime: value })}
              />
              <Button variant="outline" size="icon-sm" disabled={locked} onClick={() => moveEnd(0.1)}>
                +
              </Button>
            </div>
          </div>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          {canUseSegmentRange && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => patchActive({ startTime: segmentStartTime, endTime: segmentEndTime })}
            >
              <Captions className="h-4 w-4" />
              整句
            </Button>
          )}
          <Button variant="outline" size="sm" disabled={locked} onClick={() => onSave(active, 'CONFIRMED')}>
            确认
          </Button>
          <Button variant="ghost" size="sm" disabled={locked} onClick={() => onSave(active, 'IGNORED')}>
            忽略
          </Button>
        </div>
      </div>
    </div>
  );
}

/* ============================ 违规词库 ============================ */

/** 新增/编辑违规词对话框的受控表单模型。 */
interface TermForm {
  term: string;
  category: string;
  severity: Severity;
  matchType: MatchType;
  enabled: boolean;
  /** 逗号或换行分隔的变体词文本,提交后端后按分隔符拆分为多个变体 */
  variants: string;
}

/** 新增违规词时表单的初始空值。 */
const EMPTY_FORM: TermForm = {
  term: '',
  category: '',
  severity: 'MEDIUM',
  matchType: 'EXACT',
  enabled: true,
  variants: ''
};

/**
 * 违规词库管理:词条 CRUD、关键词搜索、CSV 批量导入,以及启用状态的即时开关。
 * 词库是规则召回的基础,AI 仅复核规则召回出的候选上下文。
 */
function TermsPage() {
  const [terms, setTerms] = useState<ViolationTerm[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<ViolationTerm | null>(null);
  const [form, setForm] = useState<TermForm>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ViolationTerm | null>(null);
  const [selectedTermIds, setSelectedTermIds] = useState<Set<number>>(new Set());
  const [batchDeleteTermsOpen, setBatchDeleteTermsOpen] = useState(false);
  const [batchingTerms, setBatchingTerms] = useState(false);
  const [aiDialogOpen, setAiDialogOpen] = useState(false);
  const [aiPrompt, setAiPrompt] = useState('');
  const [aiCategory, setAiCategory] = useState('');
  const [aiCount, setAiCount] = useState(12);
  const [generatingTerms, setGeneratingTerms] = useState(false);
  const [generatedTerms, setGeneratedTerms] = useState<GeneratedTerm[]>([]);
  const [selectedGenerated, setSelectedGenerated] = useState<Set<number>>(new Set());
  const [importingGenerated, setImportingGenerated] = useState(false);
  const pageSize = 8;
  const { page, setPage, pageCount, pageItems } = usePagination(terms, pageSize);
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
    setSelectedTermIds((current) => {
      const liveIds = new Set(terms.map((term) => term.id));
      const next = new Set([...current].filter((id) => liveIds.has(id)));
      return next.size === current.size ? current : next;
    });
  }, [terms]);

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
    // 乐观更新:先就地改 UI 状态,后端失败再回滚为原值,避免开关明显卡顿
    setTerms((items) => items.map((item) => (item.id === term.id ? { ...item, enabled } : item)));
    try {
      await updateTerm(term.id, { enabled });
    } catch (error) {
      setTerms((items) => items.map((item) => (item.id === term.id ? { ...item, enabled: !enabled } : item)));
      toast.error(getErrorMessage(error, '更新状态失败'));
    }
  };

  const confirmDelete = async () => {
    const targets = deleteTarget ? [deleteTarget] : terms.filter((term) => selectedTermIds.has(term.id));
    if (targets.length === 0) return;
    setBatchingTerms(true);
    try {
      const result = await batchDeleteTerms(targets.map((term) => term.id));
      showBatchResult(result, '已删除词条');
      setDeleteTarget(null);
      setBatchDeleteTermsOpen(false);
      setSelectedTermIds(new Set());
      load();
    } catch (error) {
      toast.error(getErrorMessage(error, '删除失败'));
    } finally {
      setBatchingTerms(false);
    }
  };

  const selectedTerms = useMemo(
    () => terms.filter((term) => selectedTermIds.has(term.id)),
    [selectedTermIds, terms]
  );
  const pageTermIds = pageItems.map((term) => term.id);
  const pageTermsAllSelected = pageTermIds.length > 0 && pageTermIds.every((id) => selectedTermIds.has(id));

  const toggleTermSelection = (id: number, checked: boolean) => {
    setSelectedTermIds((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  const togglePageTermSelection = (checked: boolean) => {
    setSelectedTermIds((current) => {
      const next = new Set(current);
      pageTermIds.forEach((id) => {
        if (checked) {
          next.add(id);
        } else {
          next.delete(id);
        }
      });
      return next;
    });
  };

  const batchSetTermsEnabled = async (enabled: boolean) => {
    if (selectedTerms.length === 0) {
      toast.error('请选择词条');
      return;
    }
    setBatchingTerms(true);
    try {
      const result = await batchUpdateTerms({ ids: selectedTerms.map((term) => term.id), enabled });
      showBatchResult(result, enabled ? '已启用词条' : '已停用词条');
      load();
    } finally {
      setBatchingTerms(false);
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

  const openAiGenerate = () => {
    setAiDialogOpen(true);
    setGeneratedTerms([]);
    setSelectedGenerated(new Set());
  };

  const openAiGeneratePreset = (prompt: string, category: string, count = 12) => {
    setAiPrompt(prompt);
    setAiCategory(category);
    setAiCount(count);
    setAiDialogOpen(true);
    setGeneratedTerms([]);
    setSelectedGenerated(new Set());
  };

  const generateAiTerms = async () => {
    if (!aiPrompt.trim()) {
      toast.error('请输入生成需求');
      return;
    }
    setGeneratingTerms(true);
    try {
      const result = await generateTermsWithAi({
        prompt: aiPrompt.trim(),
        category: aiCategory.trim() || undefined,
        count: aiCount
      });
      setGeneratedTerms(result);
      setSelectedGenerated(new Set(result.map((_, index) => index)));
      if (result.length === 0) {
        toast.error('AI 未返回可导入的候选词，换一种描述再试');
      } else {
        toast.success(`已生成 ${result.length} 条候选词`);
      }
    } catch (error) {
      toast.error(getErrorMessage(error, 'AI 生成失败'));
    } finally {
      setGeneratingTerms(false);
    }
  };

  const toggleGeneratedTerm = (index: number, checked: boolean) => {
    setSelectedGenerated((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(index);
      } else {
        next.delete(index);
      }
      return next;
    });
  };

  const importGeneratedTerms = async () => {
    const selected = generatedTerms.filter((_, index) => selectedGenerated.has(index));
    if (selected.length === 0) {
      toast.error('请选择要写入词库的候选词');
      return;
    }
    setImportingGenerated(true);
    let imported = 0;
    let skipped = 0;
    try {
      for (const item of selected) {
        try {
          await createTerm({
            term: item.term,
            category: item.category ?? '',
            severity: item.severity,
            matchType: item.matchType,
            enabled: true,
            variants: item.variants ?? ''
          });
          imported++;
        } catch {
          skipped++;
        }
      }
      toast.success(`已写入 ${imported} 条，跳过 ${skipped} 条`);
      setAiDialogOpen(false);
      setGeneratedTerms([]);
      setSelectedGenerated(new Set());
      load();
    } finally {
      setImportingGenerated(false);
    }
  };

  const enabledCount = terms.filter((t) => t.enabled).length;
  const termOps = useMemo(() => {
    const disabledCount = terms.length - enabledCount;
    const missingVariants = terms.filter((term) => term.matchType === 'VARIANT' && !term.variants?.trim()).length;
    const regexCount = terms.filter((term) => term.matchType === 'REGEX').length;
    const semanticCount = terms.filter((term) => term.matchType === 'SEMANTIC').length;
    const categoryCounts = terms.reduce<Record<string, number>>((acc, term) => {
      const key = term.category?.trim() || '未分类';
      acc[key] = (acc[key] ?? 0) + 1;
      return acc;
    }, {});
    const topCategories = Object.entries(categoryCounts)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4);
    const score = Math.max(
      0,
      Math.min(100, 100 - disabledCount * 2 - missingVariants * 4 - (topCategories[0]?.[1] === terms.length && terms.length > 5 ? 12 : 0))
    );
    return { disabledCount, missingVariants, regexCount, semanticCount, topCategories, score };
  }, [enabledCount, terms]);

  const aiPresets = [
    {
      label: '广告极限词',
      category: '广告极限词',
      prompt: '生成直播带货、短视频口播中常见的广告极限词和绝对化宣传违规词，包含口语化、谐音和变体表达。'
    },
    {
      label: '价格误导',
      category: '价格营销',
      prompt: '生成价格误导、虚假低价、限时限量诱导、比价夸大相关违规词，适合电商视频审核。'
    },
    {
      label: '站外引流',
      category: '违规引流',
      prompt: '生成站外引流、私下交易、联系方式规避表达相关违规词，包含拼音、谐音和拆字表达。'
    }
  ];

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
            <Button variant="outline" onClick={openAiGenerate}>
              <Sparkles className="h-4 w-4" />
              AI 生成
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
        <CardHeader className="flex-row items-center justify-between gap-3 space-y-0">
          <div>
            <CardTitle>智能词库运营</CardTitle>
            <div className="mt-1 text-[12px] text-muted-foreground">按启用率、变体缺口和分类分布评估词库维护状态。</div>
          </div>
          <Pill tone={termOps.score >= 80 ? 'success' : termOps.score >= 60 ? 'warn' : 'danger'} dot={false}>
            健康分 <span className="telemetry">{termOps.score}</span>
          </Pill>
        </CardHeader>
        <CardContent>
          <div className="grid gap-3 lg:grid-cols-[1fr_1fr_1.2fr]">
            <div className="rounded-md border border-border bg-muted/20 p-3">
              <div className="text-[12px] text-muted-foreground">运营风险</div>
              <div className="mt-2 grid grid-cols-3 gap-2 text-center">
                <div>
                  <div className="telemetry text-xl font-semibold">{termOps.disabledCount}</div>
                  <div className="text-[11px] text-muted-foreground">停用</div>
                </div>
                <div>
                  <div className="telemetry text-xl font-semibold">{termOps.missingVariants}</div>
                  <div className="text-[11px] text-muted-foreground">变体缺口</div>
                </div>
                <div>
                  <div className="telemetry text-xl font-semibold">{termOps.semanticCount}</div>
                  <div className="text-[11px] text-muted-foreground">语义词</div>
                </div>
              </div>
            </div>
            <div className="rounded-md border border-border bg-muted/20 p-3">
              <div className="mb-2 text-[12px] text-muted-foreground">分类覆盖</div>
              {termOps.topCategories.length === 0 ? (
                <div className="text-[12px] text-muted-foreground">暂无分类数据</div>
              ) : (
                <div className="space-y-1.5">
                  {termOps.topCategories.map(([category, count]) => (
                    <div key={category} className="flex items-center gap-2 text-[12px]">
                      <span className="min-w-0 flex-1 truncate">{category}</span>
                      <span className="telemetry text-muted-foreground">{count}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="rounded-md border border-border bg-muted/20 p-3">
              <div className="mb-2 text-[12px] text-muted-foreground">AI 运营动作</div>
              <div className="flex flex-wrap gap-2">
                {aiPresets.map((preset) => (
                  <Button
                    key={preset.label}
                    variant="outline"
                    size="sm"
                    onClick={() => openAiGeneratePreset(preset.prompt, preset.category)}
                  >
                    <Sparkles className="h-4 w-4" />
                    {preset.label}
                  </Button>
                ))}
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card className="animate-fade-up animate-delay-150">
        <CardContent className="pt-5">
          {terms.length === 0 && !loading ? (
            <EmptyState
              icon={Database}
              title="词库为空"
              description="新增违规词或导入 CSV，开始构建你的规则召回词库。"
            />
          ) : (
            <>
              {selectedTermIds.size > 0 && (
                <div className="mb-3 flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/30 px-3 py-2">
                  <Pill tone="info" dot={false}>
                    已选 <span className="telemetry">{selectedTermIds.size}</span>
                  </Pill>
                  <Button variant="outline" size="sm" onClick={() => batchSetTermsEnabled(true)} disabled={batchingTerms}>
                    <CheckCheck className="h-4 w-4" />
                    批量启用
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => batchSetTermsEnabled(false)} disabled={batchingTerms}>
                    批量停用
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-muted-foreground hover:text-destructive"
                    onClick={() => setBatchDeleteTermsOpen(true)}
                    disabled={batchingTerms}
                  >
                    <Trash2 className="h-4 w-4" />
                    批量删除
                  </Button>
                  <Button variant="ghost" size="sm" className="ml-auto" onClick={() => setSelectedTermIds(new Set())}>
                    清空选择
                  </Button>
                </div>
              )}
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-[44px]">
                      <input
                        type="checkbox"
                        checked={pageTermsAllSelected}
                        onChange={(e) => togglePageTermSelection(e.target.checked)}
                        aria-label="选择当前页词条"
                        className="h-4 w-4 rounded border-border accent-primary"
                      />
                    </TableHead>
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
                        <input
                          type="checkbox"
                          checked={selectedTermIds.has(term.id)}
                          onChange={(e) => toggleTermSelection(term.id, e.target.checked)}
                          aria-label={`选择 ${term.term}`}
                          className="h-4 w-4 rounded border-border accent-primary"
                        />
                      </TableCell>
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
              <DataPagination page={page} pageSize={pageSize} total={terms.length} onPageChange={setPage} />
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

      <Dialog open={aiDialogOpen} onOpenChange={setAiDialogOpen}>
        <DialogContent className="max-w-4xl">
          <DialogHeader>
            <DialogTitle>AI 生成词库</DialogTitle>
            <DialogDescription>用自然语言描述要覆盖的违规场景，生成候选词后再人工确认写入。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="ai-term-prompt">生成需求</Label>
              <Textarea
                id="ai-term-prompt"
                name="ai-term-prompt"
                rows={4}
                value={aiPrompt}
                onChange={(e) => setAiPrompt(e.target.value)}
                placeholder="例如：生成直播带货中价格误导、绝对化宣传、虚假功效相关的违规词，包含常见口语化表达和谐音变体"
              />
            </div>
            <div className="grid gap-3 md:grid-cols-[1fr_160px_auto]">
              <div className="grid gap-2">
                <Label htmlFor="ai-term-category">分类提示</Label>
                <Input
                  id="ai-term-category"
                  name="ai-term-category"
                  value={aiCategory}
                  onChange={(e) => setAiCategory(e.target.value)}
                  placeholder="可选，例如 广告极限词"
                />
              </div>
              <div className="grid gap-2">
                <Label>生成数量</Label>
                <NumberField
                  value={aiCount}
                  min={1}
                  max={50}
                  step={1}
                  precision={0}
                  className="w-full"
                  name="ai-term-count"
                  aria-label="AI 生成词条数量"
                  onChange={setAiCount}
                />
              </div>
              <div className="flex items-end">
                <Button onClick={generateAiTerms} disabled={generatingTerms} className="w-full md:w-auto">
                  {generatingTerms ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                  生成候选
                </Button>
              </div>
            </div>
            {generatedTerms.length > 0 && (
              <div className="rounded-md border border-border">
                <div className="flex items-center justify-between border-b border-border px-3 py-2">
                  <div className="text-sm font-medium">候选词条</div>
                  <Button
                    variant="ghost"
                    size="xs"
                    onClick={() => {
                      const allSelected = selectedGenerated.size === generatedTerms.length;
                      setSelectedGenerated(allSelected ? new Set() : new Set(generatedTerms.map((_, index) => index)));
                    }}
                  >
                    {selectedGenerated.size === generatedTerms.length ? '取消全选' : '全选'}
                  </Button>
                </div>
                <ScrollArea className="max-h-[320px]">
                  <Table>
                    <TableHeader>
                      <TableRow className="hover:bg-transparent">
                        <TableHead className="w-[48px]">选择</TableHead>
                        <TableHead>词条</TableHead>
                        <TableHead className="w-[130px]">分类</TableHead>
                        <TableHead className="w-[88px]">严重级别</TableHead>
                        <TableHead className="w-[88px]">匹配方式</TableHead>
                        <TableHead>变体 / 依据</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {generatedTerms.map((term, index) => (
                        <TableRow key={`${term.term}-${index}`}>
                          <TableCell>
                            <input
                              type="checkbox"
                              checked={selectedGenerated.has(index)}
                              onChange={(e) => toggleGeneratedTerm(index, e.target.checked)}
                              aria-label={`选择 ${term.term}`}
                              className="h-4 w-4 rounded border-border accent-primary"
                            />
                          </TableCell>
                          <TableCell className="font-medium">{term.term}</TableCell>
                          <TableCell className="text-[13px] text-muted-foreground">{term.category || '-'}</TableCell>
                          <TableCell>
                            <SeverityBadge severity={term.severity} />
                          </TableCell>
                          <TableCell className="text-[13px] text-muted-foreground">{MATCH_TYPE[term.matchType]}</TableCell>
                          <TableCell className="text-[12px] text-muted-foreground">
                            <div className="line-clamp-1">{term.variants || '-'}</div>
                            {term.reason && <div className="line-clamp-1 opacity-80">{term.reason}</div>}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </ScrollArea>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setAiDialogOpen(false)}>
              取消
            </Button>
            <Button
              onClick={importGeneratedTerms}
              disabled={generatedTerms.length === 0 || selectedGenerated.size === 0 || importingGenerated}
            >
              {importingGenerated ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Database className="h-4 w-4" />}
              写入选中词条
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认 */}
      <AlertDialog
        open={!!deleteTarget || batchDeleteTermsOpen}
        onOpenChange={(open) => {
          if (!open && !batchingTerms) {
            setDeleteTarget(null);
            setBatchDeleteTermsOpen(false);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除违规词</AlertDialogTitle>
            <AlertDialogDescription>
              {deleteTarget
                ? `确认删除「${deleteTarget.term}」？删除后将不再参与后续检测召回。`
                : `确认删除选中的 ${selectedTermIds.size} 条违规词？删除后将不再参与后续检测召回。`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={batchingTerms}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={confirmDelete}
              disabled={batchingTerms}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {batchingTerms ? '处理中…' : '删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
