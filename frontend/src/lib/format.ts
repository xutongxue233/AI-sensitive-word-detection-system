/**
 * 前端展示层格式化工具集:时间轴秒、日期时间、时钟、文件大小。
 * 统一约定:输入为无效值/空值时返回占位符(如 '--:--' 或 '-'),避免界面渲染出 NaN。
 */

/**
 * 把秒数格式化为 mm:ss.ss(秒保留两位小数)用于时间轴展示;无效输入返回 '--:--'。
 * 注意:这是一个格式化函数(返回字符串),并非返回秒数的取值函数。
 * @param value 时间值(秒),可为 undefined/null/NaN
 * @returns mm:ss.ss 形式的字符串,无效时为占位符 '--:--'
 */
export function seconds(value?: number | null): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '--:--';
  const total = Math.max(0, value);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${s.toFixed(2).padStart(5, '0')}`;
}

/**
 * 把 ISO 时间字符串格式化为「YYYY-MM-DD HH:mm」;空值或无法解析时返回 '-'。
 * @param iso ISO 8601 时间字符串
 */
export function formatDateTime(iso?: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 把 Date 格式化为「HH:mm:ss」时钟形式。
 * @param d 日期对象
 */
export function formatClock(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 把 ISO 时间字符串格式化为「HH:mm:ss」短时钟;空值或无法解析时返回 '-'。
 * @param iso ISO 8601 时间字符串
 */
export function formatTimeShort(iso?: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return formatClock(d);
}

/**
 * 把字节数格式化为带单位的可读大小,按 1024 进制选取单位(B/KB/MB/GB/TB)。
 * 0 与无效值(undefined/null/NaN/负数)返回占位符;B 档不保留小数,其余档保留一位小数。
 * @param value 字节数
 */
export function formatBytes(value?: number | null): string {
  if (value === undefined || value === null || Number.isNaN(value) || value < 0) return '-';
  if (value === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
  const size = value / Math.pow(1024, i);
  return `${size.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}
