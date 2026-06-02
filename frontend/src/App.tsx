import { ChangeEvent, DragEvent, FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import dayjs from 'dayjs';
import {
  CircleAlert,
  Database,
  Download,
  Edit,
  FileSearch,
  Loader2,
  Plus,
  RefreshCw,
  Scissors,
  Search,
  Trash2,
  UploadCloud,
  Video
} from 'lucide-react';
import { toast, Toaster } from 'sonner';
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
import {
  ClipStatus,
  ClipSuggestion,
  DetectionJob,
  JobStatus,
  MatchType,
  ReviewStatus,
  Severity,
  TermHit,
  TimelineItem,
  TranscriptSegment,
  VideoFile,
  ViolationTerm
} from './types';
import { PlayerSeekCommand, VideoPlayer } from './VideoPlayer';
import { Alert, AlertDescription, AlertTitle } from './components/ui/alert';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger
} from './components/ui/alert-dialog';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from './components/ui/dialog';
import { Input } from './components/ui/input';
import { Label } from './components/ui/label';
import { Progress } from './components/ui/progress';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from './components/ui/select';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle
} from './components/ui/sheet';
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider
} from './components/ui/sidebar';
import { Separator } from './components/ui/separator';
import { Switch } from './components/ui/switch';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from './components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './components/ui/tabs';
import { Textarea } from './components/ui/textarea';
import { cn } from './lib/utils';

const severityMap: Record<Severity, { label: string; className: string }> = {
  LOW: { label: '低', className: 'border-sky-200 bg-sky-50 text-sky-700' },
  MEDIUM: { label: '中', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  HIGH: { label: '高', className: 'border-orange-200 bg-orange-50 text-orange-700' },
  CRITICAL: { label: '严重', className: 'border-rose-200 bg-rose-50 text-rose-700' }
};

const matchTypeMap: Record<MatchType, string> = {
  EXACT: '精确',
  VARIANT: '变体',
  REGEX: '正则',
  SEMANTIC: '语义'
};

const reviewMap: Record<ReviewStatus, { label: string; className: string }> = {
  PENDING: { label: '待复核', className: 'border-transparent bg-muted text-muted-foreground' },
  VIOLATION: { label: '违规', className: 'border-rose-200 bg-rose-50 text-rose-700' },
  SAFE: { label: '安全', className: 'border-transparent bg-primary/10 text-primary' },
  CONFIRMED: { label: '已确认', className: 'border-orange-200 bg-orange-50 text-orange-700' },
  IGNORED: { label: '已忽略', className: 'border-transparent bg-muted text-muted-foreground' }
};

const clipMap: Record<ClipStatus, { label: string; className: string }> = {
  PENDING: { label: '待确认', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  CONFIRMED: { label: '已确认', className: 'border-transparent bg-primary/10 text-primary' },
  IGNORED: { label: '已忽略', className: 'border-transparent bg-muted text-muted-foreground' },
  EXPORTED: { label: '已导出', className: 'border-sky-200 bg-sky-50 text-sky-700' }
};

const videoStatusMap: Record<VideoFile['status'], { label: string; className: string }> = {
  UPLOADED: { label: '待检测', className: 'border-transparent bg-muted text-muted-foreground' },
  DETECTING: { label: '检测中', className: 'border-indigo-200 bg-indigo-50 text-indigo-700' },
  DETECTED: { label: '已检测', className: 'border-transparent bg-primary/10 text-primary' },
  EXPORTED: { label: '已导出', className: 'border-sky-200 bg-sky-50 text-sky-700' },
  FAILED: { label: '失败', className: 'border-rose-200 bg-rose-50 text-rose-700' }
};

const jobStatusMap: Record<JobStatus, { label: string; className: string }> = {
  QUEUED: { label: '排队中', className: 'border-transparent bg-muted text-muted-foreground' },
  EXTRACTING_AUDIO: { label: '抽取音频', className: 'border-indigo-200 bg-indigo-50 text-indigo-700' },
  TRANSCRIBING: { label: '语音转写', className: 'border-indigo-200 bg-indigo-50 text-indigo-700' },
  MATCHING_TERMS: { label: '规则匹配', className: 'border-indigo-200 bg-indigo-50 text-indigo-700' },
  AI_REVIEWING: { label: 'AI 复核', className: 'border-indigo-200 bg-indigo-50 text-indigo-700' },
  SUGGESTING_CLIPS: { label: '生成剪辑', className: 'border-indigo-200 bg-indigo-50 text-indigo-700' },
  COMPLETED: { label: '已完成', className: 'border-transparent bg-primary/10 text-primary' },
  FAILED: { label: '失败', className: 'border-rose-200 bg-rose-50 text-rose-700' }
};

interface TermFormState {
  term: string;
  category: string;
  severity: Severity;
  matchType: MatchType;
  enabled: boolean;
  variants: string;
}

function isVideoBusy(video: VideoFile) {
  return video.status === 'DETECTING';
}

function canExportVideo(video: VideoFile) {
  return video.status === 'DETECTED' || video.status === 'EXPORTED';
}

function seconds(value?: number) {
  if (value === undefined || value === null) {
    return '-';
  }
  const minutes = Math.floor(value / 60);
  const remain = value % 60;
  return `${minutes.toString().padStart(2, '0')}:${remain.toFixed(2).padStart(5, '0')}`;
}

function seekButtonLabel(text: string, startTime: number) {
  return `跳转到 ${text} ${seconds(startTime)}`;
}

function App() {
  const [menuKey, setMenuKey] = useState<'videos' | 'terms'>('videos');

  return (
    <SidebarProvider>
      <Toaster richColors position="top-right" />
      <Sidebar>
        <SidebarHeader>
          <div className="flex items-center gap-3 rounded-lg border bg-card p-3">
            <div className="grid h-9 w-9 place-items-center rounded-md bg-primary text-sm font-semibold text-primary-foreground">
              AI
            </div>
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold">视频违规词检测</div>
              <div className="truncate text-xs text-muted-foreground">Whisper 审核工作台</div>
            </div>
          </div>
        </SidebarHeader>
        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupLabel>工作区</SidebarGroupLabel>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton isActive={menuKey === 'videos'} onClick={() => setMenuKey('videos')}>
                  <Video />
                  <span>视频检测</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton isActive={menuKey === 'terms'} onClick={() => setMenuKey('terms')}>
                  <Database />
                  <span>违规词库</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroup>
        </SidebarContent>
        <SidebarFooter>
          <Card className="rounded-lg border-sidebar-border bg-sidebar-accent/35">
            <CardContent className="p-3 text-xs text-muted-foreground">
              shadcn/ui dashboard layout
            </CardContent>
          </Card>
        </SidebarFooter>
      </Sidebar>
      <SidebarInset>
        <header className="sticky top-0 z-20 flex min-h-16 items-center border-b bg-background/95 px-4 backdrop-blur lg:px-6">
          <div className="flex w-full flex-col gap-3 py-3 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <h1 className="text-xl font-semibold tracking-tight">
                {menuKey === 'videos' ? '视频检测任务' : '违规词库管理'}
              </h1>
              <p className="mt-1 text-sm text-muted-foreground">
                {menuKey === 'videos'
                  ? '上传视频或字幕，触发检测后查看违规词时间轴与剪辑建议'
                  : '维护规则召回词库，AI 只复核候选上下文'}
              </p>
            </div>
            <Tabs value={menuKey} onValueChange={(value) => setMenuKey(value as 'videos' | 'terms')} className="lg:hidden">
              <TabsList className="grid w-full grid-cols-2 sm:w-[320px]">
                <TabsTrigger value="videos">
                  <Video className="mr-2 h-4 w-4" />
                  视频检测
                </TabsTrigger>
                <TabsTrigger value="terms">
                  <Database className="mr-2 h-4 w-4" />
                  词库
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
        </header>
        <section className="p-4 lg:p-6">
          <Tabs value={menuKey} onValueChange={(value) => setMenuKey(value as 'videos' | 'terms')} className="space-y-4">
            <div className="hidden items-center justify-between lg:flex">
              <TabsList>
                <TabsTrigger value="videos">
                  <Video className="mr-2 h-4 w-4" />
                  视频检测
                </TabsTrigger>
                <TabsTrigger value="terms">
                  <Database className="mr-2 h-4 w-4" />
                  违规词库
                </TabsTrigger>
              </TabsList>
            </div>
            <Separator className="hidden lg:block" />
            <TabsContent value="videos" className="mt-0">
              <VideosPage />
            </TabsContent>
            <TabsContent value="terms" className="mt-0">
              <TermsPage />
            </TabsContent>
          </Tabs>
        </section>
      </SidebarInset>
    </SidebarProvider>
  );
}

function TermsPage() {
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const [terms, setTerms] = useState<ViolationTerm[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ViolationTerm | null>(null);
  const [saving, setSaving] = useState(false);
  const [importing, setImporting] = useState(false);
  const [form, setForm] = useState<TermFormState>(emptyTermForm());

  const load = async () => {
    setLoading(true);
    try {
      setTerms(await listTerms(keyword));
    } catch (error) {
      toast.error(getErrorMessage(error, '加载违规词失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openCreate = () => {
    setEditing(null);
    setForm(emptyTermForm());
    setModalOpen(true);
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
    setModalOpen(true);
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
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
      setModalOpen(false);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '保存违规词失败'));
    } finally {
      setSaving(false);
    }
  };

  const handleImport = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) {
      return;
    }
    setImporting(true);
    try {
      const result = await importTerms(file);
      toast.success(`已导入 ${result.importedCount} 条，跳过 ${result.skippedCount} 条`);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导入失败'));
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="page-stack">
      <Card>
        <CardContent className="flex flex-col gap-3 p-4 lg:flex-row lg:items-center lg:justify-between">
          <form
            className="flex flex-1 flex-col gap-2 sm:flex-row"
            onSubmit={(event) => {
              event.preventDefault();
              load();
            }}
          >
            <div className="relative min-w-0 flex-1 lg:max-w-sm">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-9"
                placeholder="搜索词条或分类"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
              />
            </div>
            <Button type="submit" variant="outline" disabled={loading}>
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              刷新
            </Button>
          </form>
          <div className="flex flex-wrap gap-2">
            <Button type="button" onClick={openCreate}>
              <Plus className="h-4 w-4" />
              新增违规词
            </Button>
            <Button type="button" variant="outline" onClick={() => importInputRef.current?.click()} disabled={importing}>
              {importing ? <Loader2 className="h-4 w-4 animate-spin" /> : <UploadCloud className="h-4 w-4" />}
              导入 CSV
            </Button>
            <input
              ref={importInputRef}
              type="file"
              accept=".csv,text/csv"
              hidden
              aria-hidden="true"
              tabIndex={-1}
              onChange={handleImport}
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between gap-3">
          <div>
            <CardTitle>违规词条</CardTitle>
            <CardDescription>{loading ? '正在加载词库' : `当前 ${terms.length} 条规则`}</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/50 hover:bg-muted/50">
                <TableHead>词条</TableHead>
                <TableHead>分类</TableHead>
                <TableHead>严重级别</TableHead>
                <TableHead>匹配方式</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>变体词</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[132px] text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {terms.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8}>
                    <EmptyState title={loading ? '加载中' : '暂无违规词'} description="导入 CSV 或手动新增词条后会显示在这里。" />
                  </TableCell>
                </TableRow>
              ) : (
                terms.map((term) => (
                  <TableRow key={term.id}>
                    <TableCell className="font-medium">{term.term}</TableCell>
                    <TableCell>{term.category || '-'}</TableCell>
                    <TableCell>
                      <Badge variant="outline" className={severityMap[term.severity].className}>
                        {severityMap[term.severity].label}
                      </Badge>
                    </TableCell>
                    <TableCell>{matchTypeMap[term.matchType]}</TableCell>
                    <TableCell>
                      <Badge
                        variant="outline"
                        className={
                          term.enabled
                            ? 'border-transparent bg-primary/10 text-primary'
                            : 'border-transparent bg-muted text-muted-foreground'
                        }
                      >
                        {term.enabled ? '启用' : '停用'}
                      </Badge>
                    </TableCell>
                    <TableCell className="max-w-[220px] truncate text-muted-foreground" title={term.variants || undefined}>
                      {term.variants || '-'}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {dayjs(term.updatedAt).format('YYYY-MM-DD HH:mm')}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-2">
                        <Button type="button" variant="outline" size="icon" onClick={() => openEdit(term)} aria-label="编辑违规词">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <AlertDialog>
                          <AlertDialogTrigger asChild>
                            <Button type="button" variant="outline" size="icon" aria-label="删除违规词">
                              <Trash2 className="h-4 w-4 text-rose-600" />
                            </Button>
                          </AlertDialogTrigger>
                          <AlertDialogContent>
                            <AlertDialogHeader>
                              <AlertDialogTitle>删除违规词</AlertDialogTitle>
                              <AlertDialogDescription>删除后不会再参与后续检测。</AlertDialogDescription>
                            </AlertDialogHeader>
                            <AlertDialogFooter>
                              <AlertDialogCancel>取消</AlertDialogCancel>
                              <AlertDialogAction
                                className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                                onClick={async () => {
                                  try {
                                    await deleteTerm(term.id);
                                    toast.success('已删除');
                                    await load();
                                  } catch (error) {
                                    toast.error(getErrorMessage(error, '删除失败'));
                                  }
                                }}
                              >
                                删除
                              </AlertDialogAction>
                            </AlertDialogFooter>
                          </AlertDialogContent>
                        </AlertDialog>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={modalOpen} onOpenChange={setModalOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? '编辑违规词' : '新增违规词'}</DialogTitle>
            <DialogDescription>语义规则适合价格、金额等需要上下文判断的候选召回。</DialogDescription>
          </DialogHeader>
          <form className="grid gap-4" onSubmit={submit}>
            <Field label="词条/正则表达式" htmlFor="term-word">
              <Input id="term-word" value={form.term} onChange={(event) => setForm({ ...form, term: event.target.value })} />
            </Field>
            <Field label="分类" htmlFor="term-category">
              <Input
                id="term-category"
                placeholder="例如：广告、辱骂、敏感内容"
                value={form.category}
                onChange={(event) => setForm({ ...form, category: event.target.value })}
              />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="严重级别" htmlFor="term-severity">
                <Select value={form.severity} onValueChange={(value) => setForm({ ...form, severity: value as Severity })}>
                  <SelectTrigger id="term-severity">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="LOW">低</SelectItem>
                    <SelectItem value="MEDIUM">中</SelectItem>
                    <SelectItem value="HIGH">高</SelectItem>
                    <SelectItem value="CRITICAL">严重</SelectItem>
                  </SelectContent>
                </Select>
              </Field>
              <Field label="匹配方式" htmlFor="term-match-type">
                <Select value={form.matchType} onValueChange={(value) => setForm({ ...form, matchType: value as MatchType })}>
                  <SelectTrigger id="term-match-type">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="EXACT">精确</SelectItem>
                    <SelectItem value="VARIANT">变体</SelectItem>
                    <SelectItem value="REGEX">正则</SelectItem>
                    <SelectItem value="SEMANTIC">语义</SelectItem>
                  </SelectContent>
                </Select>
              </Field>
            </div>
            <Alert className="border-sky-200 bg-sky-50 text-sky-900">
              <AlertTitle>语义规则提示</AlertTitle>
              <AlertDescription>价格类规则可选择“语义”，词条填写“价格”或“金额”。系统会召回 1块钱、29.9、¥29.9 等候选，再由 AI 按上下文判断。</AlertDescription>
            </Alert>
            <div className="flex items-center justify-between rounded-md border border-input px-3 py-2">
              <div>
                <Label htmlFor="term-enabled">状态</Label>
                <p className="mt-1 text-xs text-muted-foreground">关闭后不会参与后续检测。</p>
              </div>
              <Switch
                id="term-enabled"
                checked={form.enabled}
                onCheckedChange={(checked) => setForm({ ...form, enabled: checked })}
              />
            </div>
            <Field label="变体词" htmlFor="term-variants">
              <Textarea
                id="term-variants"
                rows={4}
                placeholder="仅变体匹配使用，多个词用逗号或换行分隔"
                value={form.variants}
                onChange={(event) => setForm({ ...form, variants: event.target.value })}
              />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setModalOpen(false)}>
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving && <Loader2 className="h-4 w-4 animate-spin" />}
                保存
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function VideosPage() {
  const videoInputRef = useRef<HTMLInputElement | null>(null);
  const subtitleInputRef = useRef<HTMLInputElement | null>(null);
  const [videos, setVideos] = useState<VideoFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [videoAction, setVideoAction] = useState<{ videoId: number; type: 'detect' | 'export' | 'delete' } | null>(null);
  const [subtitleFile, setSubtitleFile] = useState<File | undefined>();
  const [selected, setSelected] = useState<{ videoId: number; jobId?: number } | null>(null);
  const [dragActive, setDragActive] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const nextVideos = await listVideos();
      setVideos([...nextVideos].sort((left, right) => dayjs(right.createdAt).valueOf() - dayjs(left.createdAt).valueOf()));
    } catch (error) {
      toast.error(getErrorMessage(error, '加载视频失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const uploadSelectedVideo = async (file: File) => {
    setUploading(true);
    setUploadPercent(0);
    try {
      await uploadVideo(file, subtitleFile, setUploadPercent);
      toast.success('视频已上传');
      setSubtitleFile(undefined);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '上传失败'));
    } finally {
      setUploading(false);
      setUploadPercent(0);
    }
  };

  const handleVideoInput = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file) {
      uploadSelectedVideo(file);
    }
  };

  const handleDrop = (event: DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    setDragActive(false);
    const file = Array.from(event.dataTransfer.files).find((item) => item.type.startsWith('video/'));
    if (file) {
      uploadSelectedVideo(file);
    } else {
      toast.error('请拖入视频文件');
    }
  };

  const triggerJob = async (video: VideoFile) => {
    setVideoAction({ videoId: video.id, type: 'detect' });
    try {
      const job = await startJob(video.id);
      toast.success('检测任务已启动');
      setSelected({ videoId: video.id, jobId: job.id });
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '启动检测失败'));
    } finally {
      setVideoAction(null);
    }
  };

  const exportModerated = async (video: VideoFile) => {
    setVideoAction({ videoId: video.id, type: 'export' });
    try {
      const result = await exportVideo(video.id);
      toast.success(`已导出，删除片段 ${result.removedClipCount} 个`);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '导出失败'));
    } finally {
      setVideoAction(null);
    }
  };

  const removeVideo = async (video: VideoFile) => {
    setVideoAction({ videoId: video.id, type: 'delete' });
    try {
      await deleteVideo(video.id);
      toast.success('视频已删除');
      if (selected?.videoId === video.id) {
        setSelected(null);
      }
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, '删除视频失败'));
    } finally {
      setVideoAction(null);
    }
  };

  const stats = useMemo(
    () => [
      { label: '视频总数', value: videos.length, icon: Video },
      {
        label: '已完成检测',
        value: videos.filter((item) => item.status === 'DETECTED' || item.status === 'EXPORTED').length,
        icon: FileSearch
      },
      { label: '已导出视频', value: videos.filter((item) => item.status === 'EXPORTED').length, icon: Scissors },
      { label: '失败任务', value: videos.filter((item) => item.status === 'FAILED').length, icon: CircleAlert }
    ],
    [videos]
  );

  return (
    <div className="page-stack">
      <div className="stats-grid">
        {stats.map((item) => {
          const Icon = item.icon;
          return (
            <Card key={item.label}>
              <CardContent className="flex items-center justify-between p-4">
                <div>
                  <p className="text-sm text-muted-foreground">{item.label}</p>
                  <p className="mt-1 text-2xl font-semibold tracking-normal">{item.value}</p>
                </div>
                <div className="grid h-10 w-10 place-items-center rounded-md bg-primary/10 text-primary">
                  <Icon className="h-5 w-5" />
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>

      <Card>
        <CardHeader className="flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <CardTitle>上传检测素材</CardTitle>
            <CardDescription>先选择字幕可跳过 ASR；未选择字幕时使用本地 Whisper large-v3。</CardDescription>
          </div>
          <Button type="button" variant="outline" onClick={load} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新列表
          </Button>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-2 border-b pb-4">
            <Button type="button" variant="outline" onClick={() => subtitleInputRef.current?.click()}>
              <FileSearch className="h-4 w-4" />
              {subtitleFile ? '更换字幕' : '选择字幕文件'}
            </Button>
            <input
              ref={subtitleInputRef}
              type="file"
              accept=".srt,.vtt"
              hidden
              aria-hidden="true"
              tabIndex={-1}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (file) {
                  setSubtitleFile(file);
                }
              }}
            />
            {subtitleFile ? (
              <Badge variant="outline" className="gap-2 border-cyan-200 bg-cyan-50 text-cyan-700">
                {subtitleFile.name}
                <button
                  type="button"
                  className="cursor-pointer rounded-sm text-cyan-700 hover:text-cyan-950 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  aria-label="移除字幕文件"
                  onClick={() => setSubtitleFile(undefined)}
                >
                  x
                </button>
              </Badge>
            ) : (
              <span className="text-sm text-muted-foreground">未选择字幕，将进行语音转写。</span>
            )}
          </div>
          <button
            type="button"
            className={cn(
              'flex min-h-[168px] w-full cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed bg-muted/20 px-4 text-center transition-colors duration-200 hover:border-primary hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60',
              dragActive && 'border-primary bg-muted/50'
            )}
            disabled={uploading}
            onClick={() => videoInputRef.current?.click()}
            onDragOver={(event) => {
              event.preventDefault();
              setDragActive(true);
            }}
            onDragLeave={() => setDragActive(false)}
            onDrop={handleDrop}
          >
            <UploadCloud className="h-9 w-9 text-primary" />
            <span className="mt-3 font-semibold">拖拽视频到此处，或点击选择文件</span>
            <span className="mt-1 text-sm text-muted-foreground">
              系统优先解析字幕文件；没有字幕时抽取音频并调用本地 Whisper ASR 服务。
            </span>
          </button>
          <input
            ref={videoInputRef}
            type="file"
            accept="video/*"
            hidden
            aria-hidden="true"
            tabIndex={-1}
            onChange={handleVideoInput}
          />
          {uploading && (
            <div className="space-y-2">
              <div className="flex justify-between text-sm">
                <span>上传中</span>
                <span>{uploadPercent}%</span>
              </div>
              <Progress value={uploadPercent} />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>视频列表</CardTitle>
          <CardDescription>{loading ? '正在加载视频' : `当前 ${videos.length} 个视频`}</CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/50 hover:bg-muted/50">
                <TableHead>文件</TableHead>
                <TableHead className="w-[105px]">时长</TableHead>
                <TableHead className="w-[112px]">状态</TableHead>
                <TableHead className="w-[170px]">上传时间</TableHead>
                <TableHead className="w-[365px] text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {videos.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5}>
                    <EmptyState title={loading ? '加载中' : '还没有上传视频'} description="上传视频后可以启动检测并查看时间轴。" />
                  </TableCell>
                </TableRow>
              ) : (
                videos.map((record) => {
                  const busy = isVideoBusy(record);
                  const currentAction = videoAction?.videoId === record.id ? videoAction.type : null;
                  const status = videoStatusMap[record.status];
                  return (
                    <TableRow key={record.id}>
                      <TableCell>
                        <div className="flex min-w-0 flex-col gap-1">
                          <span className="max-w-[360px] truncate font-medium" title={record.originalFilename}>
                            {record.originalFilename}
                          </span>
                          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                            <span>#{record.id}</span>
                            {record.subtitlePath && (
                              <Badge variant="outline" className="border-cyan-200 bg-cyan-50 text-cyan-700">
                                字幕
                              </Badge>
                            )}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>{seconds(record.durationSeconds)}</TableCell>
                      <TableCell>
                        <Badge variant="outline" className={status.className}>
                          {status.label}
                        </Badge>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-muted-foreground">
                        {dayjs(record.createdAt).format('YYYY-MM-DD HH:mm')}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap justify-end gap-2">
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            onClick={() => triggerJob(record)}
                            disabled={busy || currentAction === 'detect'}
                          >
                            {currentAction === 'detect' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Video className="h-4 w-4" />}
                            开始检测
                          </Button>
                          <Button type="button" variant="outline" size="sm" onClick={() => setSelected({ videoId: record.id })}>
                            <FileSearch className="h-4 w-4" />
                            详情
                          </Button>
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            onClick={() => exportModerated(record)}
                            disabled={!canExportVideo(record) || busy || currentAction === 'export'}
                          >
                            {currentAction === 'export' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
                            导出
                          </Button>
                          <AlertDialog>
                            <AlertDialogTrigger asChild>
                              <Button
                                type="button"
                                variant="outline"
                                size="sm"
                                disabled={busy || currentAction === 'delete'}
                              >
                                {currentAction === 'delete' ? (
                                  <Loader2 className="h-4 w-4 animate-spin" />
                                ) : (
                                  <Trash2 className="h-4 w-4 text-rose-600" />
                                )}
                                删除
                              </Button>
                            </AlertDialogTrigger>
                            <AlertDialogContent>
                              <AlertDialogHeader>
                                <AlertDialogTitle>删除视频</AlertDialogTitle>
                                <AlertDialogDescription>
                                  将从列表中移除该视频及其检测记录，并清理对应文件。
                                </AlertDialogDescription>
                              </AlertDialogHeader>
                              <AlertDialogFooter>
                                <AlertDialogCancel>取消</AlertDialogCancel>
                                <AlertDialogAction
                                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                                  onClick={() => removeVideo(record)}
                                >
                                  删除
                                </AlertDialogAction>
                              </AlertDialogFooter>
                            </AlertDialogContent>
                          </AlertDialog>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
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
        <SheetContent side="right" className="w-[min(1120px,96vw)] overflow-y-auto p-0 sm:max-w-none">
          <SheetHeader className="border-b p-5 pr-12">
            <SheetTitle>检测详情</SheetTitle>
            <SheetDescription>查看视频、违规词时间轴、AI 复核结果和剪辑建议。</SheetDescription>
          </SheetHeader>
          <div className="p-5">
            {selected && <JobDetail videoId={selected.videoId} initialJobId={selected.jobId} />}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  );
}

function JobDetail({ videoId, initialJobId }: { videoId: number; initialJobId?: number }) {
  const [video, setVideo] = useState<VideoFile | null>(null);
  const [job, setJob] = useState<DetectionJob | null>(null);
  const [segments, setSegments] = useState<TranscriptSegment[]>([]);
  const [hits, setHits] = useState<TermHit[]>([]);
  const [timeline, setTimeline] = useState<TimelineItem[]>([]);
  const [suggestions, setSuggestions] = useState<ClipSuggestion[]>([]);
  const [playerDuration, setPlayerDuration] = useState<number>();
  const [seekCommand, setSeekCommand] = useState<PlayerSeekCommand>();
  const [loading, setLoading] = useState(false);

  const activeJobId = job?.id ?? initialJobId;

  const load = async () => {
    setLoading(true);
    try {
      const currentVideo = await getVideo(videoId);
      setVideo(currentVideo);
      let jobId = activeJobId;
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
  }, [videoId, initialJobId]);

  useEffect(() => {
    if (!job || job.status === 'COMPLETED' || job.status === 'FAILED') {
      return undefined;
    }
    const timer = window.setInterval(load, 2500);
    return () => window.clearInterval(timer);
  }, [job?.id, job?.status]);

  const duration =
    video?.durationSeconds ||
    playerDuration ||
    Math.max(1, ...timeline.map((item) => item.endTime + 1), ...segments.map((item) => item.endTime));

  const jumpToTime = (startTime: number) => {
    setSeekCommand({ time: Math.max(0, startTime), nonce: Date.now() });
  };

  const handlePlayerDuration = (nextDuration: number) => {
    setPlayerDuration(nextDuration);
    setVideo((current) =>
      current && (!current.durationSeconds || current.durationSeconds <= 0)
        ? { ...current, durationSeconds: nextDuration }
        : current
    );
  };

  const regenerateSuggestions = async () => {
    if (!activeJobId) {
      return;
    }
    try {
      const next = await createClipSuggestions(activeJobId);
      setSuggestions(next);
      toast.success('剪辑建议已重新生成');
    } catch (error) {
      toast.error(getErrorMessage(error, '生成剪辑建议失败'));
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
      toast.error(getErrorMessage(error, '保存剪辑建议失败'));
    }
  };

  const exportedSuggestion = suggestions.find((item) => item.status === 'EXPORTED');

  return (
    <div className="detail-layout">
      <aside className="detail-video-column">
        {video ? (
          <div className="video-panel">
            <VideoPlayer
              label="原始视频"
              src={videoContentUrl(video.id)}
              seekCommand={seekCommand}
              onDuration={handlePlayerDuration}
            />
            {exportedSuggestion && <VideoPlayer label="导出视频" src={exportContentUrl(exportedSuggestion.id)} />}
          </div>
        ) : (
          <EmptyState title="视频不存在" description="无法加载该视频文件。" />
        )}
      </aside>
      <section className="detail-content-column">
        {job ? (
          <Card>
            <CardContent className="p-5">
              <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
                <div
                  className={cn(
                    'grid h-[72px] w-[72px] shrink-0 place-items-center rounded-full border-4 bg-white text-sm font-semibold',
                    job.status === 'FAILED'
                      ? 'border-rose-200 text-rose-700'
                      : job.status === 'COMPLETED'
                        ? 'border-primary/20 text-primary'
                        : 'border-indigo-200 text-indigo-700'
                  )}
                >
                  {job.progress}%
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">任务 #{job.id}</span>
                    <Badge variant="outline" className={jobStatusMap[job.status].className}>
                      {jobStatusMap[job.status].label}
                    </Badge>
                    <span className="text-sm text-muted-foreground">
                      {job.startedAt ? `开始于 ${dayjs(job.startedAt).format('HH:mm:ss')}` : '等待启动'}
                    </span>
                  </div>
                  <Progress value={job.progress} className="mt-3" />
                  {job.errorMessage && (
                    <Alert variant="destructive" className="mt-3">
                      <AlertTitle>任务失败</AlertTitle>
                      <AlertDescription>{job.errorMessage}</AlertDescription>
                    </Alert>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        ) : (
          <Alert className="border-sky-200 bg-sky-50 text-sky-900">
            <AlertTitle>还没有检测任务</AlertTitle>
            <AlertDescription>该视频还没有检测任务，可以在视频列表点击开始检测。</AlertDescription>
          </Alert>
        )}

        <Card>
          <CardHeader>
            <CardTitle>违规词时间轴</CardTitle>
            <CardDescription>{loading ? '正在加载时间轴' : '点击红色片段或列表行可跳转到对应时间。'}</CardDescription>
          </CardHeader>
          <CardContent>
            {timeline.length === 0 ? (
              <MutedPanel>暂无 AI 判定违规的命中。</MutedPanel>
            ) : (
              <>
                <div className="timeline-track">
                  {timeline.map((item) => (
                    <button
                      type="button"
                      key={item.hitId}
                      className="timeline-hit"
                      aria-label={seekButtonLabel(item.matchedText, item.startTime)}
                      onClick={() => jumpToTime(item.startTime)}
                      style={{
                        left: `${Math.max(0, (item.startTime / duration) * 100)}%`,
                        width: `${Math.max(1, ((item.endTime - item.startTime) / duration) * 100)}%`
                      }}
                      title={`${item.matchedText} ${seconds(item.startTime)}-${seconds(item.endTime)}`}
                    />
                  ))}
                </div>
                <div className="timeline-list">
                  {timeline.map((item) => (
                    <button
                      type="button"
                      className="timeline-row timeline-row-button"
                      key={item.hitId}
                      onClick={() => jumpToTime(item.startTime)}
                      aria-label={seekButtonLabel(item.matchedText, item.startTime)}
                    >
                      <Badge variant="outline" className={severityMap[item.severity].className}>
                        {severityMap[item.severity].label}
                      </Badge>
                      <span className="font-semibold">{item.matchedText}</span>
                      <span>{seconds(item.startTime)} - {seconds(item.endTime)}</span>
                      <span className="truncate text-muted-foreground">{item.contextText}</span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <CardTitle>剪辑建议</CardTitle>
              <CardDescription>可调整片段起止时间并确认或忽略。</CardDescription>
            </div>
            <Button type="button" variant="outline" onClick={regenerateSuggestions} disabled={!activeJobId}>
              <Scissors className="h-4 w-4" />
              重新生成
            </Button>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/50 hover:bg-muted/50">
                  <TableHead>违规词</TableHead>
                  <TableHead>开始</TableHead>
                  <TableHead>结束</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {suggestions.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={5}>
                      <MutedPanel>暂无剪辑建议。</MutedPanel>
                    </TableCell>
                  </TableRow>
                ) : (
                  suggestions.map((record) => (
                    <TableRow key={record.id}>
                      <TableCell>
                        <Button
                          type="button"
                          variant="link"
                          className="time-jump-link"
                          onClick={() => jumpToTime(record.startTime)}
                          aria-label={seekButtonLabel(record.matchedText, record.startTime)}
                        >
                          {record.matchedText}
                        </Button>
                      </TableCell>
                      <TableCell>
                        <Input
                          type="number"
                          min={0}
                          step={0.01}
                          className="w-24"
                          value={record.startTime.toFixed(2)}
                          onChange={(event) =>
                            setSuggestions((items) =>
                              items.map((item) =>
                                item.id === record.id ? { ...item, startTime: Number(event.target.value || 0) } : item
                              )
                            )
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <Input
                          type="number"
                          min={0}
                          step={0.01}
                          className="w-24"
                          value={record.endTime.toFixed(2)}
                          onChange={(event) =>
                            setSuggestions((items) =>
                              items.map((item) =>
                                item.id === record.id ? { ...item, endTime: Number(event.target.value || 0) } : item
                              )
                            )
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className={clipMap[record.status].className}>
                          {clipMap[record.status].label}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-2">
                          <Button type="button" variant="outline" size="sm" onClick={() => saveSuggestion(record, 'CONFIRMED')}>
                            确认
                          </Button>
                          <Button type="button" variant="outline" size="sm" onClick={() => saveSuggestion(record, 'IGNORED')}>
                            忽略
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>命中与 AI 复核</CardTitle>
            <CardDescription>点击违规词可跳转到视频对应位置。</CardDescription>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/50 hover:bg-muted/50">
                  <TableHead>词</TableHead>
                  <TableHead>时间</TableHead>
                  <TableHead>判定</TableHead>
                  <TableHead>置信度</TableHead>
                  <TableHead>AI 原因</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {hits.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={5}>
                      <MutedPanel>暂无命中记录。</MutedPanel>
                    </TableCell>
                  </TableRow>
                ) : (
                  hits.map((record) => (
                    <TableRow key={record.id}>
                      <TableCell>
                        <Button
                          type="button"
                          variant="link"
                          className="time-jump-link"
                          onClick={() => jumpToTime(record.startTime)}
                          aria-label={seekButtonLabel(record.matchedText, record.startTime)}
                        >
                          {record.matchedText}
                        </Button>
                      </TableCell>
                      <TableCell>{seconds(record.startTime)} - {seconds(record.endTime)}</TableCell>
                      <TableCell>
                        <Badge variant="outline" className={reviewMap[record.reviewStatus].className}>
                          {reviewMap[record.reviewStatus].label}
                        </Badge>
                      </TableCell>
                      <TableCell>{record.aiConfidence === undefined ? '-' : `${Math.round(record.aiConfidence * 100)}%`}</TableCell>
                      <TableCell className="max-w-[320px] text-muted-foreground">{record.aiReview?.reason || '-'}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>字幕片段</CardTitle>
            <CardDescription>点击字幕行可跳转到该片段开头。</CardDescription>
          </CardHeader>
          <CardContent>
            {segments.length === 0 ? (
              <MutedPanel>暂无字幕片段。</MutedPanel>
            ) : (
              <div className="segment-list">
                {segments.map((segment) => (
                  <button
                    type="button"
                    className="segment-row segment-row-button"
                    key={segment.id}
                    onClick={() => jumpToTime(segment.startTime)}
                    aria-label={`跳转到字幕片段 ${seconds(segment.startTime)}`}
                  >
                    <code>{seconds(segment.startTime)} - {seconds(segment.endTime)}</code>
                    <span>{segment.text}</span>
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </section>
    </div>
  );
}

function emptyTermForm(): TermFormState {
  return {
    term: '',
    category: '',
    severity: 'MEDIUM',
    matchType: 'EXACT',
    enabled: true,
    variants: ''
  };
}

function Field({
  label,
  htmlFor,
  children
}: {
  label: string;
  htmlFor: string;
  children: React.ReactNode;
}) {
  return (
    <div className="grid gap-2">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  );
}

function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 py-12 text-center">
      <div className="grid h-10 w-10 place-items-center rounded-md bg-muted text-muted-foreground">
        <FileSearch className="h-5 w-5" />
      </div>
      <div className="mt-2 font-medium">{title}</div>
      <div className="text-sm text-muted-foreground">{description}</div>
    </div>
  );
}

function MutedPanel({ children }: { children: React.ReactNode }) {
  return <div className="rounded-md border border-dashed bg-muted/20 p-4 text-sm text-muted-foreground">{children}</div>;
}

export default App;
