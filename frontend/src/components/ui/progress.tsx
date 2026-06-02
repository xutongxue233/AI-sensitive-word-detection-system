import * as React from 'react';
import { cn } from '../../lib/utils';

export interface ProgressProps extends React.HTMLAttributes<HTMLDivElement> {
  value?: number;
}

const Progress = React.forwardRef<HTMLDivElement, ProgressProps>(({ className, value = 0, ...props }, ref) => {
  const safeValue = Math.max(0, Math.min(100, value));
  return (
    <div
      ref={ref}
      className={cn('relative h-2 w-full overflow-hidden rounded-full bg-secondary', className)}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={safeValue}
      {...props}
    >
      <div
        className="h-full w-full flex-1 bg-primary transition-transform duration-300"
        style={{ transform: `translateX(-${100 - safeValue}%)` }}
      />
    </div>
  );
});
Progress.displayName = 'Progress';

export { Progress };
