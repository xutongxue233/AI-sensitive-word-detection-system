import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';

/**
 * {@link DataPagination} 组件的属性。
 */
interface DataPaginationProps {
  /** 当前页码,从 1 开始。 */
  page: number;
  /** 每页条数,与 total 一起决定是否需要分页。 */
  pageSize: number;
  /** 数据总条数。 */
  total: number;
  /** 翻页回调,入参为目标页码。 */
  onPageChange: (page: number) => void;
}

/**
 * 数据分页条:展示「N–M / 总数 条」并提供上一页/下一页按钮。
 *
 * 当数据不足一页(total <= pageSize)时整体不渲染,避免在只有寥寥几条结果时
 * 出现多余的分页控件。供审核工作台的命中列表、字幕分段等长列表复用。
 */
export function DataPagination({ page, pageSize, total, onPageChange }: DataPaginationProps) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  // 数据不足一页时不渲染分页条
  if (total <= pageSize) return null;

  // 当前页覆盖的条目区间 [from, to],用于「N–M / 总数」展示
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);

  return (
    <div className="flex items-center justify-between gap-3 pt-3">
      <span className="text-xs text-muted-foreground">
        <span className="telemetry text-foreground">{from}–{to}</span>
        <span className="px-1">/</span>
        <span className="telemetry">{total}</span> 条
      </span>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="icon-sm"
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
          aria-label="上一页"
        >
          <ChevronLeft />
        </Button>
        <span className="telemetry min-w-[3.5rem] text-center text-xs text-muted-foreground">
          {page} / {pageCount}
        </span>
        <Button
          variant="outline"
          size="icon-sm"
          disabled={page >= pageCount}
          onClick={() => onPageChange(page + 1)}
          aria-label="下一页"
        >
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}
