import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * {@link RingProgress} 组件的属性。
 */
interface RingProgressProps {
  /** 进度值 0-100,内部会被钳制到该区间。 */
  value: number;
  /** 环的外径(像素)。 */
  size?: number;
  /** 圆环描边宽度(像素)。 */
  strokeWidth?: number;
  /** 任务状态,决定进度环颜色。 */
  status?: 'active' | 'success' | 'exception';
  className?: string;
  /** 环心内容;不传时默认显示百分比文本。 */
  children?: React.ReactNode;
}

/**
 * 状态-颜色映射。
 *
 * active(进行中)与 success(成功)共用 primary 主题色是有意为之:检测任务从进行到完成
 * 视觉上保持一致、不做色彩切换;仅 exception(异常)用 destructive 醒目区分。
 */
const STATUS_COLOR: Record<NonNullable<RingProgressProps['status']>, string> = {
  active: 'hsl(var(--primary))',
  success: 'hsl(var(--primary))',
  exception: 'hsl(var(--destructive))'
};

/**
 * 检测任务进度环:以 SVG 圆环展示 0-100 的进度,环心默认显示百分比。
 *
 * 用于审核工作台轮询任务状态时展示检测进度。
 */
export function RingProgress({
  value,
  size = 76,
  strokeWidth = 6,
  status = 'active',
  className,
  children
}: RingProgressProps) {
  const clamped = Math.max(0, Math.min(100, value));
  const radius = (size - strokeWidth) / 2;
  // 圆周长:作为 strokeDasharray 的基准长度
  const circumference = 2 * Math.PI * radius;
  // 用 strokeDashoffset 表示进度——剩余未完成部分对应的偏移量,进度越高偏移越小
  const offset = circumference - (clamped / 100) * circumference;
  const color = STATUS_COLOR[status];

  return (
    <div className={cn('relative inline-flex items-center justify-center', className)} style={{ width: size, height: size }}>
      {/* -rotate-90 使进度起点位于顶端(12 点方向),而非 SVG 默认的 3 点方向 */}
      <svg width={size} height={size} className="-rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="hsl(var(--muted))"
          strokeWidth={strokeWidth}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          style={{ transition: 'stroke-dashoffset 0.6s cubic-bezier(0.22,1,0.36,1)' }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        {children ?? (
          <span className="telemetry text-sm font-semibold text-foreground">{Math.round(clamped)}%</span>
        )}
      </div>
    </div>
  );
}
