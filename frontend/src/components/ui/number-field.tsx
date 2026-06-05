import * as React from 'react';
import { cn } from '@/lib/utils';

interface NumberFieldProps {
  value: number;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
  precision?: number;
  suffix?: string;
  className?: string;
  name?: string;
  'aria-label'?: string;
}

export function NumberField({
  value,
  onChange,
  min,
  max,
  step = 0.1,
  precision = 2,
  suffix,
  className,
  ...rest
}: NumberFieldProps) {
  const [text, setText] = React.useState(String(value ?? ''));
  const focused = React.useRef(false);

  React.useEffect(() => {
    // 仅在未处于聚焦编辑时同步外部值，避免打断/回弹用户输入；按 precision 取整显示，避免多位小数
    if (focused.current) return;
    setText(value === undefined || value === null ? '' : String(Number(value.toFixed(precision))));
  }, [value, precision]);

  const clamp = (n: number) => {
    let next = n;
    if (min !== undefined) next = Math.max(min, next);
    if (max !== undefined) next = Math.min(max, next);
    return next;
  };

  const commit = (raw: string) => {
    const parsed = Number(raw);
    if (raw === '' || Number.isNaN(parsed)) {
      setText(value === undefined ? '' : String(value));
      return;
    }
    const next = clamp(Number(parsed.toFixed(precision)));
    onChange(next);
    setText(String(next));
  };

  return (
    <div
      className={cn(
        'inline-flex h-9 items-center rounded-md border border-input bg-card/50 shadow-sm transition-colors focus-within:border-ring focus-within:ring-2 focus-within:ring-ring/30',
        className
      )}
    >
      <input
        {...rest}
        type="number"
        inputMode="decimal"
        value={text}
        min={min}
        max={max}
        step={step}
        onChange={(e) => setText(e.target.value)}
        onFocus={() => {
          focused.current = true;
        }}
        onBlur={(e) => {
          focused.current = false;
          commit(e.target.value);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
        }}
        className="telemetry h-full w-full bg-transparent px-2.5 text-sm tabular-nums outline-none [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
      />
      {suffix && <span className="pr-2.5 text-xs text-muted-foreground">{suffix}</span>}
    </div>
  );
}
