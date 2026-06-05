import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * {@link Dropzone} 组件的属性。
 */
interface DropzoneProps {
  /** 选中或拖入文件后的回调,入参为文件数组(已转为 File[])。 */
  onFiles: (files: File[]) => void;
  /** 接受的文件类型,透传给底层 input 的 accept 属性。 */
  accept?: string;
  /** 禁用时不响应点击/键盘/拖放,并降低透明度。 */
  disabled?: boolean;
  /** 是否允许多选。 */
  multiple?: boolean;
  className?: string;
  /** 上传区内的提示内容(图标、文案等)。 */
  children?: React.ReactNode;
}

/**
 * 文件上传区:支持点击与拖放两种方式上传,并带键盘可访问性
 * (role=button + tabIndex,Enter/Space 触发文件选择)。
 *
 * 内部隐藏一个原生 file input,所有交互最终都转化为对它的 click。
 * 供审核工作台上传视频/字幕文件复用。
 */
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
      {/* 扫描光束:hover 时浮现的横向掠过动画装饰 */}
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
          // 重置 input 值,确保重复选择同一文件仍能触发 onChange
          e.target.value = '';
        }}
      />
      <div className="relative z-10">{children}</div>
    </div>
  );
}
