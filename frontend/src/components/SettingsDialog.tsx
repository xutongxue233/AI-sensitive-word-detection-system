import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import {
  AudioLines,
  Bot,
  CheckCircle2,
  Gauge,
  KeyRound,
  Loader2,
  PlugZap,
  Scissors,
  ShieldCheck,
  SlidersHorizontal
} from 'lucide-react';

import { getErrorMessage, getSettings, testAiConnection, updateSettings } from '@/api';
import type { AiApiType, AppSettings, AppSettingsUpdate, AsrProvider } from '@/types';
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
import { ScrollArea } from '@/components/ui/scroll-area';

/**
 * 表单态:在完整 {@link AppSettings} 基础上去掉只读标志 aiApiKeyConfigured/asrOnlineApiKeyConfigured。
 * 后端不下发明文 Key,两个标志分别由 keyConfigured/asrKeyConfigured 单独存储,故不进入可编辑表单。
 */
type Form = Omit<AppSettings, 'aiApiKeyConfigured' | 'asrOnlineApiKeyConfigured'>;

/** 接口形态枚举到中文说明的映射,须与后端 AiApiType 同步。 */
const API_TYPE_LABEL: Record<AiApiType, string> = {
  CHAT: 'Chat Completions（/v1/chat/completions）',
  RESPONSES: 'Responses（/v1/responses）'
};

/** ASR 引擎枚举到中文说明的映射,须与后端 AsrProvider 同步。 */
const ASR_PROVIDER_LABEL: Record<AsrProvider, string> = {
  LOCAL: '本地 Whisper（faster-whisper，离线）',
  ONLINE: '在线接口（OpenAI 兼容，如小米 MiMo）'
};

/** 表单项容器:统一标签 + 控件 + 可选提示文案的纵向布局。 */
function Field({
  label,
  hint,
  children,
  className
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={className ?? 'space-y-1.5'}>
      <Label>{label}</Label>
      {children}
      {hint && <p className="text-[12px] leading-snug text-muted-foreground">{hint}</p>}
    </div>
  );
}

function StatusChip({ active, label }: { active: boolean; label: string }) {
  return (
    <span
      className={
        active
          ? 'inline-flex items-center gap-1 rounded-md border border-emerald-500/25 bg-emerald-500/[0.08] px-2 py-1 text-[11px] font-medium text-emerald-700 dark:text-emerald-300'
          : 'inline-flex items-center gap-1 rounded-md border border-border bg-muted/50 px-2 py-1 text-[11px] font-medium text-muted-foreground'
      }
    >
      <span className={active ? 'h-1.5 w-1.5 rounded-full bg-emerald-500' : 'h-1.5 w-1.5 rounded-full bg-muted-foreground/45'} />
      {label}
    </span>
  );
}

function SettingsSection({
  icon: Icon,
  title,
  description,
  children
}: {
  icon: typeof Bot;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-card/80 shadow-sm">
      <div className="flex items-start gap-3 border-b border-border px-4 py-3">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-primary/10 text-primary">
          <Icon className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <div className="text-sm font-semibold">{title}</div>
          <p className="mt-0.5 text-[12px] leading-snug text-muted-foreground">{description}</p>
        </div>
      </div>
      <div className="p-4">{children}</div>
    </section>
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
 * 后端已有 Key,仅在用户实际填入时才随保存载荷下发。更换在线 ASR 端点时需重新填写在线密钥。
 *
 * @param open 对话框是否打开,关闭→打开切换时触发设置加载
 * @param onOpenChange 开关状态回调,保存成功或点击取消时关闭对话框
 */
export function SettingsDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [form, setForm] = useState<Form | null>(null);
  const [keyConfigured, setKeyConfigured] = useState(false); // 后端已配置 Key 的只读标志,决定占位文案与提示
  const [apiKey, setApiKey] = useState(''); // 用户新输入的 Key;留空则保存时不改动后端原 Key
  const [asrKeyConfigured, setAsrKeyConfigured] = useState(false); // 在线 ASR Key 已配置的只读标志
  const [asrApiKey, setAsrApiKey] = useState(''); // 用户新输入的在线 ASR Key;更换端点时必须填写
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setApiKey('');
    setAsrApiKey('');
    setTestResult(null);
    getSettings()
      .then((data) => {
        if (cancelled) return;
        const { aiApiKeyConfigured, asrOnlineApiKeyConfigured, ...rest } = data;
        setForm(rest);
        setKeyConfigured(aiApiKeyConfigured);
        setAsrKeyConfigured(asrOnlineApiKeyConfigured);
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

  /** 用当前表单值临时探测模型连通性;不保存设置,Key 留空时后端沿用已存 AI Key。 */
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

  /** 保存设置:两个 apiKey 均仅当非空才下发;在线端点变更时后端会要求新 Key。 */
  const save = async () => {
    if (!form) return;
    setSaving(true);
    try {
      const payload: AppSettingsUpdate = { ...form };
      if (apiKey.trim() !== '') {
        payload.aiApiKey = apiKey.trim();
      }
      if (asrApiKey.trim() !== '') {
        payload.asrOnlineApiKey = asrApiKey.trim();
      }
      const updated = await updateSettings(payload);
      setKeyConfigured(updated.aiApiKeyConfigured);
      setAsrKeyConfigured(updated.asrOnlineApiKeyConfigured);
      setApiKey('');
      setAsrApiKey('');
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
      <DialogContent className="max-h-[88vh] max-w-[760px] gap-0 overflow-hidden p-0">
        <DialogHeader className="border-b border-border px-6 py-5 pr-12">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <DialogTitle>系统设置</DialogTitle>
              <DialogDescription className="mt-1">
                AI 配置用于后续检测，剪辑参数用于后续生成建议和导出。
              </DialogDescription>
            </div>
            {form && (
              <div className="flex shrink-0 items-center gap-2 pt-0.5">
                <StatusChip active={form.aiEnabled} label={form.aiEnabled ? 'AI 已启用' : 'AI 未启用'} />
                <StatusChip active={keyConfigured} label={keyConfigured ? 'Key 已配置' : 'Key 未配置'} />
              </div>
            )}
          </div>
        </DialogHeader>

        {loading || !form ? (
          <div className="flex h-72 items-center justify-center text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin" />
          </div>
        ) : (
          <ScrollArea className="max-h-[calc(88vh-142px)]">
            <div className="space-y-4 px-6 py-5">
              <SettingsSection
                icon={Bot}
                title="AI 复核接口"
                description="配置模型端点、密钥与连通性测试。"
              >
                <div className="space-y-4">
                  <div className="flex items-center justify-between gap-4 rounded-md border border-border bg-muted/30 px-3 py-2.5">
                    <div className="flex min-w-0 items-start gap-2.5">
                      <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                      <div>
                        <div className="text-sm font-medium">启用 AI 复核</div>
                        <p className="text-[12px] text-muted-foreground">关闭后仅保留规则召回结果。</p>
                      </div>
                    </div>
                    <Switch checked={form.aiEnabled} onCheckedChange={(v) => patchAndClearTest({ aiEnabled: v })} />
                  </div>

                  <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                    <Field label="接口形态" hint="兼容国产网关、Ollama 与 OpenAI 风格端点。">
                      <Select
                        value={form.aiApiType}
                        onValueChange={(v) => patchAndClearTest({ aiApiType: v as AiApiType })}
                      >
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

                    <Field label="模型" hint="测试连接使用当前表单值。">
                      <div className="flex gap-2">
                        <Input
                          value={form.aiModel}
                          placeholder="qwen2.5"
                          onChange={(e) => patchAndClearTest({ aiModel: e.target.value })}
                        />
                        <Button
                          type="button"
                          variant="outline"
                          size="icon"
                          className="shrink-0"
                          onClick={testConnection}
                          disabled={testing || saving || loading || !form.aiBaseUrl.trim() || !form.aiModel.trim()}
                          aria-label="测试连接"
                          title="测试连接"
                        >
                          {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <PlugZap className="h-4 w-4" />}
                        </Button>
                      </div>
                    </Field>
                  </div>

                  <Field label="Base URL" hint="例如 https://api.openai.com 或 http://localhost:11434。">
                    <Input
                      value={form.aiBaseUrl}
                      placeholder="http://localhost:11434"
                      onChange={(e) => patchAndClearTest({ aiBaseUrl: e.target.value })}
                    />
                  </Field>

                  <Field label="API Key" hint={keyConfigured ? '已配置，留空则保持不变。' : '未配置，如需鉴权请填写。'}>
                    <div className="relative">
                      <KeyRound className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                      <Input
                        className="pl-9"
                        type="password"
                        value={apiKey}
                        autoComplete="off"
                        placeholder={keyConfigured ? '••••••••（留空保持不变）' : 'sk-...'}
                        onChange={(e) => {
                          setTestResult(null);
                          setApiKey(e.target.value);
                        }}
                      />
                    </div>
                  </Field>

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
                </div>
              </SettingsSection>

              <SettingsSection
                icon={SlidersHorizontal}
                title="判定参数"
                description="控制 AI 复核的阈值、随机性与等待时间。"
              >
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <Field label="置信度阈值" hint="低于该值不进时间轴。">
                    <NumberField
                      value={form.aiConfidenceThreshold}
                      min={0}
                      max={1}
                      step={0.05}
                      className="w-full"
                      onChange={(v) => patch({ aiConfidenceThreshold: v })}
                    />
                  </Field>
                  <Field label="温度" hint="负数表示不下发。">
                    <NumberField
                      value={form.aiTemperature}
                      min={-1}
                      max={2}
                      step={0.1}
                      className="w-full"
                      onChange={(v) => patchAndClearTest({ aiTemperature: v })}
                    />
                  </Field>
                  <Field label="超时" hint="单次 AI 请求。">
                    <NumberField
                      value={form.aiTimeoutSeconds}
                      min={1}
                      step={5}
                      precision={0}
                      suffix="s"
                      className="w-full"
                      onChange={(v) => patchAndClearTest({ aiTimeoutSeconds: v })}
                    />
                  </Field>
                </div>
              </SettingsSection>

              <SettingsSection
                icon={AudioLines}
                title="语音识别（ASR）"
                description="选择检测管线音频腿使用的转写引擎，对新建任务生效。"
              >
                <div className="space-y-4">
                  <Field label="转写引擎" hint="在线接口仅返回文本，系统会按静音切片并估算词级时间戳，定位精度略低于本地引擎。">
                    <Select
                      value={form.asrProvider}
                      onValueChange={(v) => patch({ asrProvider: v as AsrProvider })}
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {(Object.keys(ASR_PROVIDER_LABEL) as AsrProvider[]).map((key) => (
                          <SelectItem key={key} value={key}>
                            {ASR_PROVIDER_LABEL[key]}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>

                  {form.asrProvider === 'ONLINE' && (
                    <>
                      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                        <Field label="Base URL" hint="OpenAI Chat Completions 兼容端点，例如 https://api.xiaomimimo.com。">
                          <Input
                            value={form.asrOnlineBaseUrl}
                            placeholder="https://api.xiaomimimo.com"
                            onChange={(e) => patch({ asrOnlineBaseUrl: e.target.value })}
                          />
                        </Field>
                        <Field label="模型" hint="例如 mimo-v2.5-asr。">
                          <Input
                            value={form.asrOnlineModel}
                            placeholder="mimo-v2.5-asr"
                            onChange={(e) => patch({ asrOnlineModel: e.target.value })}
                          />
                        </Field>
                      </div>
                  <Field label="API Key" hint={asrKeyConfigured ? '已配置，留空则保持不变；更换端点需重新填写。' : '未配置，在线识别必须填写。'}>
                        <div className="relative">
                          <KeyRound className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                          <Input
                            className="pl-9"
                            type="password"
                            value={asrApiKey}
                            autoComplete="off"
                            placeholder={asrKeyConfigured ? '••••••••（更换端点需重填）' : 'sk-...'}
                            onChange={(e) => setAsrApiKey(e.target.value)}
                          />
                        </div>
                      </Field>
                    </>
                  )}
                </div>
              </SettingsSection>

              <SettingsSection
                icon={Scissors}
                title="剪辑与导出"
                description="控制剪辑建议留白与最终视频导出方式。"
              >
                <div className="grid grid-cols-1 gap-4 md:grid-cols-[minmax(0,1fr)_minmax(260px,0.9fr)]">
                  <Field label="剪辑留白" hint="命中词前后各保留的秒数。">
                    <NumberField
                      value={form.clipPaddingSeconds}
                      min={0}
                      step={0.05}
                      suffix="s"
                      className="w-full"
                      onChange={(v) => patch({ clipPaddingSeconds: v })}
                    />
                  </Field>
                  <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-muted/30 px-3 py-2.5">
                    <div className="flex min-w-0 items-start gap-2.5">
                      <Gauge className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                      <div>
                        <div className="text-sm font-medium">精确导出</div>
                        <p className="text-[12px] leading-snug text-muted-foreground">重编码以帧级精确切割。</p>
                      </div>
                    </div>
                    <Switch
                      checked={form.clipPreciseExport}
                      onCheckedChange={(v) => patch({ clipPreciseExport: v })}
                    />
                  </div>
                </div>
              </SettingsSection>
            </div>
          </ScrollArea>
        )}

        <DialogFooter className="border-t border-border bg-muted/20 px-6 py-4">
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
