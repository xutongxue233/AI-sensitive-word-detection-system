import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const alertVariants = cva(
  'relative w-full rounded-md border px-4 py-3 text-sm [&>svg]:absolute [&>svg]:left-4 [&>svg]:top-3.5 [&>svg]:size-4 [&>svg~*]:pl-7',
  {
    variants: {
      variant: {
        default: 'bg-card text-card-foreground border-border [&>svg]:text-muted-foreground',
        info: 'border-[hsl(var(--sev-low)/0.35)] bg-[hsl(var(--sev-low)/0.08)] text-foreground [&>svg]:text-[hsl(var(--sev-low))]',
        success:
          'border-emerald-500/35 bg-emerald-500/[0.08] text-foreground [&>svg]:text-emerald-600 dark:[&>svg]:text-emerald-400',
        warning:
          'border-[hsl(var(--sev-medium)/0.4)] bg-[hsl(var(--sev-medium)/0.1)] text-foreground [&>svg]:text-[hsl(var(--sev-medium))]',
        destructive:
          'border-destructive/40 bg-destructive/[0.08] text-foreground [&>svg]:text-destructive'
      }
    },
    defaultVariants: {
      variant: 'default'
    }
  }
);

const Alert = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement> & VariantProps<typeof alertVariants>
>(({ className, variant, ...props }, ref) => (
  <div ref={ref} role="alert" className={cn(alertVariants({ variant }), className)} {...props} />
));
Alert.displayName = 'Alert';

const AlertTitle = React.forwardRef<HTMLParagraphElement, React.HTMLAttributes<HTMLHeadingElement>>(
  ({ className, ...props }, ref) => (
    <h5 ref={ref} className={cn('mb-0.5 font-medium leading-none tracking-tight', className)} {...props} />
  )
);
AlertTitle.displayName = 'AlertTitle';

const AlertDescription = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLParagraphElement>
>(({ className, ...props }, ref) => (
  <div ref={ref} className={cn('text-[13px] leading-relaxed text-muted-foreground', className)} {...props} />
));
AlertDescription.displayName = 'AlertDescription';

export { Alert, AlertTitle, AlertDescription };
