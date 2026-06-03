import * as React from 'react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import type {
  ClipStatus,
  JobStatus,
  MatchType,
  ReviewStatus,
  Severity,
  VideoStatus
} from '@/types';

export type Tone = 'neutral' | 'primary' | 'success' | 'info' | 'warn' | 'danger';

const TONE_CLASSES: Record<Tone, string> = {
  neutral: 'bg-muted text-muted-foreground',
  primary: 'bg-primary/12 text-primary',
  success: 'bg-emerald-500/14 text-emerald-600 dark:text-emerald-400',
  info: 'bg-[hsl(var(--sev-low)/0.14)] text-[hsl(var(--sev-low))]',
  warn: 'bg-[hsl(var(--sev-medium)/0.16)] text-[hsl(var(--sev-medium))]',
  danger: 'bg-destructive/12 text-destructive'
};

export function Pill({
  tone = 'neutral',
  dot = true,
  pulse = false,
  className,
  children
}: {
  tone?: Tone;
  dot?: boolean;
  pulse?: boolean;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium leading-none whitespace-nowrap',
        TONE_CLASSES[tone],
        className
      )}
    >
      {dot && (
        <span className={cn('h-1.5 w-1.5 rounded-full bg-current opacity-80', pulse && 'animate-pulse')} />
      )}
      {children}
    </span>
  );
}

export const SEVERITY: Record<Severity, { label: string; varName: string }> = {
  LOW: { label: '低', varName: '--sev-low' },
  MEDIUM: { label: '中', varName: '--sev-medium' },
  HIGH: { label: '高', varName: '--sev-high' },
  CRITICAL: { label: '严重', varName: '--sev-critical' }
};

export function SeverityBadge({ severity, className }: { severity: Severity; className?: string }) {
  const s = SEVERITY[severity];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium leading-none whitespace-nowrap',
        className
      )}
      style={{
        color: `hsl(var(${s.varName}))`,
        backgroundColor: `hsl(var(${s.varName}) / 0.13)`
      }}
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: `hsl(var(${s.varName}))` }} />
      {s.label}
    </span>
  );
}

export const MATCH_TYPE: Record<MatchType, string> = {
  EXACT: '精确',
  VARIANT: '变体',
  REGEX: '正则',
  SEMANTIC: '语义'
};

export const VIDEO_STATUS: Record<VideoStatus, { label: string; tone: Tone }> = {
  UPLOADED: { label: '待检测', tone: 'neutral' },
  DETECTING: { label: '检测中', tone: 'primary' },
  DETECTED: { label: '已检测', tone: 'success' },
  EXPORTED: { label: '已导出', tone: 'info' },
  FAILED: { label: '失败', tone: 'danger' }
};

export const JOB_STATUS: Record<JobStatus, { label: string; tone: Tone }> = {
  QUEUED: { label: '排队中', tone: 'neutral' },
  EXTRACTING_AUDIO: { label: '抽取音频', tone: 'primary' },
  TRANSCRIBING: { label: '语音转写', tone: 'primary' },
  MATCHING_TERMS: { label: '规则召回', tone: 'primary' },
  AI_REVIEWING: { label: 'AI 复核', tone: 'primary' },
  SUGGESTING_CLIPS: { label: '生成剪辑', tone: 'primary' },
  COMPLETED: { label: '已完成', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' }
};

export const REVIEW_STATUS: Record<ReviewStatus, { label: string; tone: Tone }> = {
  PENDING: { label: '待复核', tone: 'neutral' },
  VIOLATION: { label: '违规', tone: 'danger' },
  SAFE: { label: '安全', tone: 'success' },
  CONFIRMED: { label: '已确认', tone: 'warn' },
  IGNORED: { label: '已忽略', tone: 'neutral' }
};

export const CLIP_STATUS: Record<ClipStatus, { label: string; tone: Tone }> = {
  PENDING: { label: '待确认', tone: 'warn' },
  CONFIRMED: { label: '已确认', tone: 'success' },
  IGNORED: { label: '已忽略', tone: 'neutral' },
  EXPORTED: { label: '已导出', tone: 'info' }
};

export function Eyebrow({ className, children }: { className?: string; children: React.ReactNode }) {
  return <span className={cn('eyebrow', className)}>{children}</span>;
}

export function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  className
}: {
  label: string;
  value: React.ReactNode;
  hint?: string;
  icon: LucideIcon;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'group relative overflow-hidden rounded-lg border border-border bg-card p-4 shadow-soft transition-colors hover:border-accent/40',
        className
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <Eyebrow>{label}</Eyebrow>
          <div className="mt-2 telemetry text-3xl font-semibold leading-none tracking-tight text-foreground">
            {value}
          </div>
          {hint && <div className="mt-2 text-[12px] text-muted-foreground">{hint}</div>}
        </div>
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-primary/10 text-primary transition-transform group-hover:scale-105">
          <Icon className="h-[18px] w-[18px]" />
        </span>
      </div>
      <span className="pointer-events-none absolute -right-6 -top-10 h-24 w-24 rounded-full bg-accent/10 opacity-0 blur-2xl transition-opacity group-hover:opacity-100" />
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  className
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center gap-3 px-6 py-14 text-center', className)}>
      <span className="grid h-12 w-12 place-items-center rounded-full border border-dashed border-border bg-muted/40 text-muted-foreground">
        <Icon className="h-5 w-5" />
      </span>
      <div className="space-y-1">
        <div className="text-sm font-medium text-foreground">{title}</div>
        {description && <div className="mx-auto max-w-sm text-[13px] text-muted-foreground">{description}</div>}
      </div>
    </div>
  );
}

export function VideoFrame({
  src,
  label,
  icon: Icon
}: {
  src: string;
  label: string;
  icon: LucideIcon;
}) {
  const [errored, setErrored] = React.useState(false);
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-[#0a120f]">
      <div className="flex items-center justify-between border-b border-white/10 px-3 py-2">
        <span className="eyebrow text-white/55">{label}</span>
        <Icon className="h-3.5 w-3.5 text-white/40" />
      </div>
      {errored ? (
        <div className="flex aspect-video flex-col items-center justify-center gap-2 text-white/45">
          <Icon className="h-7 w-7" />
          <span className="text-[12px]">视频源不可用（后端未启动或地址错误）</span>
        </div>
      ) : (
        <video
          src={src}
          controls
          preload="metadata"
          className="aspect-video w-full bg-black"
          onError={() => setErrored(true)}
        />
      )}
    </div>
  );
}
