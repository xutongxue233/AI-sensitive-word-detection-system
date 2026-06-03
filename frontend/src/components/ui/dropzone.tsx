import * as React from 'react';
import { cn } from '@/lib/utils';

interface DropzoneProps {
  onFiles: (files: File[]) => void;
  accept?: string;
  disabled?: boolean;
  multiple?: boolean;
  className?: string;
  children?: React.ReactNode;
}

export function Dropzone({
  onFiles,
  accept,
  disabled,
  multiple = false,
  className,
  children
}: DropzoneProps) {
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = React.useState(false);

  const handleFiles = (list: FileList | null) => {
    if (!list || list.length === 0) return;
    onFiles(Array.from(list));
  };

  return (
    <div
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={(e) => {
        if (!disabled && (e.key === 'Enter' || e.key === ' ')) {
          e.preventDefault();
          inputRef.current?.click();
        }
      }}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) setDragging(true);
      }}
      onDragLeave={(e) => {
        e.preventDefault();
        setDragging(false);
      }}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        if (!disabled) handleFiles(e.dataTransfer.files);
      }}
      className={cn(
        'group relative flex flex-col items-center justify-center overflow-hidden rounded-lg border border-dashed px-6 py-10 text-center outline-none transition-all',
        'border-border bg-gradient-to-b from-muted/30 to-transparent hover:border-accent/60 hover:from-accent/[0.06]',
        'focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30',
        dragging && 'border-accent bg-accent/[0.08] ring-2 ring-accent/30',
        disabled && 'pointer-events-none opacity-60',
        className
      )}
    >
      {/* scanning beam */}
      <div className="pointer-events-none absolute inset-0 opacity-0 transition-opacity group-hover:opacity-100">
        <div className="absolute top-0 h-full w-16 -skew-x-12 bg-gradient-to-r from-transparent via-accent/10 to-transparent animate-scan-x" />
      </div>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        multiple={multiple}
        className="hidden"
        onChange={(e) => {
          handleFiles(e.target.files);
          e.target.value = '';
        }}
      />
      <div className="relative z-10">{children}</div>
    </div>
  );
}
