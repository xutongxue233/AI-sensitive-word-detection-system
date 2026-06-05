import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { CheckCircle2, Loader2, PlugZap } from 'lucide-react';

import { getErrorMessage, getSettings, testAiConnection, updateSettings } from '@/api';
import type { AiApiType, AppSettings, AppSettingsUpdate } from '@/types';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select';
import { NumberField } from '@/components/ui/number-field';

/**
 * 表单态:在完整 {@link AppSettings} 基础上去掉只读标志 aiApiKeyConfigured。
 * 后端不下发明文 Key,该标志由 keyConfigured 单独存储,故不进入可编辑表单。
 */
type Form = Omit<AppSettings, 'aiApiKeyConfigured'>;

/** 接口形态枚举到中文说明的映射,须与后端 AiApiType 同步。 */
const API_TYPE_LABEL: Record<AiApiType, string> = {
  CHAT: 'Chat Completions（/v1/chat/completions）',
  RESPONSES: 'Responses（/v1/responses）'
};

/** 表单项容器:统一标签 + 控件 + 可选提示文案的纵向布局。 */
function Field({
  label,
  hint,
  children
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      {children}
      {hint && <p className="text-[12px] leading-snug text-muted-foreground">{hint}</p>}
    </div>
  );
}

/**
 * AI 复核与剪辑参数的运行时设置面板。
 *
 * 对话框 open 时调用 {@link getSettings} 拉取后端当前配置;保存后通过
 * {@link updateSettings} 落库,对**新建检测任务**即时生效,无需重启后端。
 *
 * API Key 采用 keyConfigured + apiKey 双状态:后端出于安全不下发明文 Key,
 * 仅以 aiApiKeyConfigured 标志告知是否已配置;apiKey 输入框留空即表示沿用
 * 后端已有 Key,仅在用户实际填入时才随保存载荷下发。
 *
 * @param open 对话框是否打开,关闭→打开切换时触发设置加载
 * @param onOpenChange 开关状态回调,保存成功或点击取消时关闭对话框
 */
export function SettingsDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [form, setForm] = useState<Form | null>(null);
  const [keyConfigured, setKeyConfigured] = useState(false); // 后端已配置 Key 的只读标志,决定占位文案与提示
  const [apiKey, setApiKey] = useState(''); // 用户新输入的 Key;留空则保存时不改动后端原 Key
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setApiKey('');
    setTestResult(null);
    getSettings()
      .then((data) => {
        if (cancelled) return;
        const { aiApiKeyConfigured, ...rest } = data;
        setForm(rest);
        setKeyConfigured(aiApiKeyConfigured);
      })
      .catch((error) => toast.error(getErrorMessage(error, '加载设置失败')))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [open]);

  const patch = (next: Partial<Form>) => setForm((prev) => (prev ? { ...prev, ...next } : prev));

  /**
   * 修改表单字段并清空上次"测试连接"的结果。
   * 凡涉及 AI 连通性的字段(端点/形态/模型/温度/超时等)都走此函数,
   * 改动后旧的测试结论即失效,避免展示与当前配置不符的连通状态。
   */
  const patchAndClearTest = (next: Partial<Form>) => {
    setTestResult(null);
    patch(next);
  };

  /** 用当前表单值临时探测模型连通性;不保存设置,Key 留空时后端沿用已存 Key。 */
  const testConnection = async () => {
    if (!form) return;
    setTesting(true);
    setTestResult(null);
    try {
      const result = await testAiConnection({
        aiApiType: form.aiApiType,
        aiBaseUrl: form.aiBaseUrl,
        aiApiKey: apiKey.trim() || undefined,
        aiModel: form.aiModel,
        aiTemperature: form.aiTemperature,
        aiTimeoutSeconds: form.aiTimeoutSeconds
      });
      const message = `${result.message}，耗时 ${result.elapsedMs}ms`;
      setTestResult({ ok: result.ok, message });
      toast.success(message);
    } catch (error) {
      const message = getErrorMessage(error, '模型连通性测试失败');
      setTestResult({ ok: false, message });
      toast.error(message);
    } finally {
      setTesting(false);
    }
  };

  /** 保存设置:仅当 apiKey 非空才下发,否则保留后端原 Key;成功后关闭对话框。 */
  const save = async () => {
    if (!form) return;
    setSaving(true);
    try {
      const payload: AppSettingsUpdate = { ...form };
      if (apiKey.trim() !== '') {
        payload.aiApiKey = apiKey.trim();
      }
      const updated = await updateSettings(payload);
      setKeyConfigured(updated.aiApiKeyConfigured);
      setApiKey('');
      toast.success('设置已保存，下次检测即生效');
      onOpenChange(false);
    } catch (error) {
      toast.error(getErrorMessage(error, '保存设置失败'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>系统设置</DialogTitle>
          <DialogDescription>配置 AI 复核接口与剪辑参数，保存后对新检测任务即时生效，无需重启后端。</DialogDescription>
        </DialogHeader>

        {loading || !form ? (
          <div className="flex h-48 items-center justify-center text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin" />
          </div>
        ) : (
          <div className="max-h-[60vh] space-y-5 overflow-y-auto pr-1">
            <section className="space-y-4">
              <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-card px-3 py-2.5">
                <div>
                  <div className="text-sm font-medium">启用 AI 复核</div>
                  <p className="text-[12px] text-muted-foreground">关闭时仅按规则命中，全部保留交人工确认。</p>
                </div>
                <Switch checked={form.aiEnabled} onCheckedChange={(v) => patchAndClearTest({ aiEnabled: v })} />
              </div>

              <Field label="接口形态" hint="国产网关 / Ollama 多为 Chat Completions；Responses 为 OpenAI 新版接口。">
                <Select value={form.aiApiType} onValueChange={(v) => patchAndClearTest({ aiApiType: v as AiApiType })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(Object.keys(API_TYPE_LABEL) as AiApiType[]).map((key) => (
                      <SelectItem key={key} value={key}>
                        {API_TYPE_LABEL[key]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>

              <Field label="Base URL" hint="兼容端点根地址，例如 https://api.openai.com 或 http://localhost:11434。">
                <Input
                  value={form.aiBaseUrl}
                  placeholder="http://localhost:11434"
                  onChange={(e) => patchAndClearTest({ aiBaseUrl: e.target.value })}
                />
              </Field>

              <Field label="API Key" hint={keyConfigured ? '已配置，留空则保持不变。' : '未配置，如需鉴权请填写。'}>
                <Input
                  type="password"
                  value={apiKey}
                  autoComplete="off"
                  placeholder={keyConfigured ? '••••••••（留空保持不变）' : 'sk-...'}
                  onChange={(e) => {
                    setTestResult(null);
                    setApiKey(e.target.value);
                  }}
                />
              </Field>

              <Field label="模型" hint="测试连接会使用当前表单值，不会保存设置；API Key 留空时沿用已保存的 Key。">
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Input
                    value={form.aiModel}
                    placeholder="qwen2.5"
                    onChange={(e) => patchAndClearTest({ aiModel: e.target.value })}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    className="shrink-0"
                    onClick={testConnection}
                    disabled={testing || saving || loading || !form.aiBaseUrl.trim() || !form.aiModel.trim()}
                  >
                    {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <PlugZap className="h-4 w-4" />}
                    测试连接
                  </Button>
                </div>
                {testResult && (
                  <div
                    className={
                      testResult.ok
                        ? 'flex items-start gap-2 rounded-md border border-emerald-500/30 bg-emerald-500/[0.08] px-3 py-2 text-[12px] leading-relaxed text-emerald-700 dark:text-emerald-300'
                        : 'rounded-md border border-destructive/30 bg-destructive/[0.08] px-3 py-2 text-[12px] leading-relaxed text-destructive'
                    }
                  >
                    {testResult.ok && <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
                    <span>{testResult.message}</span>
                  </div>
                )}
              </Field>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <Field label="置信度阈值" hint="低于该值不进时间轴/剪辑。">
                  <NumberField
                    value={form.aiConfidenceThreshold}
                    min={0}
                    max={1}
                    step={0.05}
                    onChange={(v) => patch({ aiConfidenceThreshold: v })}
                  />
                </Field>
                <Field label="温度" hint="负数=不下发。">
                  <NumberField
                    value={form.aiTemperature}
                    min={-1}
                    max={2}
                    step={0.1}
                    onChange={(v) => patchAndClearTest({ aiTemperature: v })}
                  />
                </Field>
                <Field label="超时" hint="秒">
                  <NumberField
                    value={form.aiTimeoutSeconds}
                    min={1}
                    step={5}
                    precision={0}
                    suffix="s"
                    onChange={(v) => patchAndClearTest({ aiTimeoutSeconds: v })}
                  />
                </Field>
              </div>
            </section>

            <section className="space-y-4 border-t border-border pt-4">
              <div className="text-sm font-medium">剪辑</div>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="剪辑留白" hint="命中词前后各保留的秒数，越小切得越少。">
                  <NumberField
                    value={form.clipPaddingSeconds}
                    min={0}
                    step={0.05}
                    suffix="s"
                    onChange={(v) => patch({ clipPaddingSeconds: v })}
                  />
                </Field>
                <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-card px-3 py-2.5">
                  <div>
                    <div className="text-sm font-medium">精确导出</div>
                    <p className="text-[12px] text-muted-foreground">重编码以帧级精确切割（较慢）。</p>
                  </div>
                  <Switch
                    checked={form.clipPreciseExport}
                    onCheckedChange={(v) => patch({ clipPreciseExport: v })}
                  />
                </div>
              </div>
            </section>
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={saving}>
            取消
          </Button>
          <Button onClick={save} disabled={saving || testing || loading || !form}>
            {saving && <Loader2 className="h-4 w-4 animate-spin" />}
            保存
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
