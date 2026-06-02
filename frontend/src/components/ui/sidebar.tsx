import * as React from 'react';
import { cn } from '../../lib/utils';

const SIDEBAR_WIDTH = '16rem';

function SidebarProvider({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('group/sidebar-wrapper flex min-h-screen w-full bg-background text-foreground', className)}
      style={{ '--sidebar-width': SIDEBAR_WIDTH } as React.CSSProperties}
      {...props}
    >
      {children}
    </div>
  );
}

function Sidebar({ className, children, ...props }: React.HTMLAttributes<HTMLElement>) {
  return (
    <aside
      className={cn(
        'hidden h-screen w-[var(--sidebar-width)] shrink-0 border-r bg-sidebar text-sidebar-foreground lg:sticky lg:top-0 lg:flex lg:flex-col',
        className
      )}
      {...props}
    >
      {children}
    </aside>
  );
}

function SidebarInset({ className, children, ...props }: React.HTMLAttributes<HTMLElement>) {
  return (
    <main className={cn('flex min-h-screen min-w-0 flex-1 flex-col bg-muted/30', className)} {...props}>
      {children}
    </main>
  );
}

function SidebarHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-2 p-3', className)} {...props} />;
}

function SidebarContent({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex min-h-0 flex-1 flex-col gap-2 overflow-auto p-2', className)} {...props} />;
}

function SidebarFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-2 p-2', className)} {...props} />;
}

function SidebarGroup({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('relative flex w-full min-w-0 flex-col p-2', className)} {...props} />;
}

function SidebarGroupLabel({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('flex h-8 shrink-0 items-center rounded-md px-2 text-xs font-medium text-sidebar-foreground/70', className)}
      {...props}
    />
  );
}

function SidebarMenu({ className, ...props }: React.HTMLAttributes<HTMLUListElement>) {
  return <ul className={cn('flex w-full min-w-0 flex-col gap-1', className)} {...props} />;
}

function SidebarMenuItem({ className, ...props }: React.HTMLAttributes<HTMLLIElement>) {
  return <li className={cn('group/menu-item relative', className)} {...props} />;
}

interface SidebarMenuButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  isActive?: boolean;
}

const SidebarMenuButton = React.forwardRef<HTMLButtonElement, SidebarMenuButtonProps>(
  ({ className, isActive, ...props }, ref) => (
    <button
      ref={ref}
      className={cn(
        'flex h-8 w-full cursor-pointer items-center gap-2 overflow-hidden rounded-md px-2 text-left text-sm outline-none ring-sidebar-ring transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:ring-2 disabled:pointer-events-none disabled:opacity-50 [&>svg]:size-4 [&>svg]:shrink-0',
        isActive && 'bg-sidebar-accent font-medium text-sidebar-accent-foreground',
        className
      )}
      {...props}
    />
  )
);
SidebarMenuButton.displayName = 'SidebarMenuButton';

export {
  Sidebar,
  SidebarProvider,
  SidebarInset,
  SidebarHeader,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuItem,
  SidebarMenuButton
};
