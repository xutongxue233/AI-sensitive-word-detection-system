import * as React from 'react';
import { cn } from '@/lib/utils';

interface RingProgressProps {
  value: number;
  size?: number;
  strokeWidth?: number;
  status?: 'active' | 'success' | 'exception';
  className?: string;
  children?: React.ReactNode;
}

const STATUS_COLOR: Record<NonNullable<RingProgressProps['status']>, string> = {
  active: 'hsl(var(--primary))',
  success: 'hsl(var(--primary))',
  exception: 'hsl(var(--destructive))'
};

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
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (clamped / 100) * circumference;
  const color = STATUS_COLOR[status];

  return (
    <div className={cn('relative inline-flex items-center justify-center', className)} style={{ width: size, height: size }}>
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
