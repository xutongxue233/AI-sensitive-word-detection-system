import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Layout,
  Menu,
  Modal,
  Popconfirm,
  Progress,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  Upload
} from 'antd';
import type { UploadRequestOption } from 'rc-upload/lib/interface';
import {
  CloudUploadOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  ExportOutlined,
  FileSearchOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  ScissorOutlined,
  VideoCameraOutlined
} from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import dayjs from 'dayjs';
import {
  createClipSuggestions,
  createTerm,
  deleteTerm,
  exportContentUrl,
  exportVideo,
  getErrorMessage,
  getJob,
  getVideo,
  importTerms,
  listClipSuggestions,
  listHits,
  listJobs,
  listSegments,
  listTerms,
  listTimeline,
  listVideos,
  startJob,
  updateClipSuggestion,
  updateTerm,
  uploadVideo,
  videoContentUrl
} from './api';
import {
  ClipSuggestion,
  ClipStatus,
  DetectionJob,
  MatchType,
  ReviewStatus,
  Severity,
  TermHit,
  TimelineItem,
  TranscriptSegment,
  VideoFile,
  ViolationTerm
} from './types';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

const severityMap: Record<Severity, { label: string; color: string }> = {
  LOW: { label: '低', color: 'blue' },
  MEDIUM: { label: '中', color: 'gold' },
  HIGH: { label: '高', color: 'orange' },
  CRITICAL: { label: '严重', color: 'red' }
};

const matchTypeMap: Record<MatchType, string> = {
  EXACT: '精确',
  VARIANT: '变体',
  REGEX: '正则',
  SEMANTIC: '语义'
};

const reviewMap: Record<ReviewStatus, { label: string; color: string }> = {
  PENDING: { label: '待复核', color: 'default' },
  VIOLATION: { label: '违规', color: 'red' },
  SAFE: { label: '安全', color: 'green' },
  CONFIRMED: { label: '已确认', color: 'volcano' },
  IGNORED: { label: '已忽略', color: 'default' }
};

const clipMap: Record<ClipStatus, { label: string; color: string }> = {
  PENDING: { label: '待确认', color: 'gold' },
  CONFIRMED: { label: '已确认', color: 'green' },
  IGNORED: { label: '已忽略', color: 'default' },
  EXPORTED: { label: '已导出', color: 'blue' }
};

function seconds(value?: number) {
  if (value === undefined || value === null) {
    return '-';
  }
  const minutes = Math.floor(value / 60);
  const remain = value % 60;
  return `${minutes.toString().padStart(2, '0')}:${remain.toFixed(2).padStart(5, '0')}`;
}

function App() {
  const [menuKey, setMenuKey] = useState('videos');

  return (
    <AntApp>
      <Layout className="app-shell">
        <Sider width={236} className="side-panel">
          <div className="brand-block">
            <div className="brand-mark">AI</div>
            <div>
              <div className="brand-title">视频违规词检测</div>
              <div className="brand-subtitle">Whisper 时间轴审核台</div>
            </div>
          </div>
          <Menu
            className="side-menu"
            theme="dark"
            mode="inline"
            selectedKeys={[menuKey]}
            onClick={(item) => setMenuKey(item.key)}
            items={[
              { key: 'videos', icon: <VideoCameraOutlined />, label: '视频检测' },
              { key: 'terms', icon: <DatabaseOutlined />, label: '违规词库' }
            ]}
          />
        </Sider>
        <Layout>
          <Header className="top-bar">
            <div>
              <Title level={4} style={{ margin: 0 }}>
                {menuKey === 'videos' ? '视频检测任务' : '违规词库管理'}
              </Title>
              <Text type="secondary">
                {menuKey === 'videos'
                  ? '上传视频或字幕，触发检测后查看违规词时间轴与剪辑建议'
                  : '维护规则召回词库，AI 只复核候选上下文'}
              </Text>
            </div>
          </Header>
          <Content className="workspace">
            {menuKey === 'videos' ? <VideosPage /> : <TermsPage />}
          </Content>
        </Layout>
      </Layout>
    </AntApp>
  );
}

function TermsPage() {
  const { message } = AntApp.useApp();
  const [terms, setTerms] = useState<ViolationTerm[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ViolationTerm | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setTerms(await listTerms(keyword));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({
      term: '',
      category: '',
      severity: 'MEDIUM',
      matchType: 'EXACT',
      enabled: true,
      variants: ''
    });
    setModalOpen(true);
  };

  const openEdit = (term: ViolationTerm) => {
    setEditing(term);
    form.setFieldsValue(term);
    setModalOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    try {
      if (editing) {
        await updateTerm(editing.id, values);
        message.success('违规词已更新');
      } else {
        await createTerm(values);
        message.success('违规词已创建');
      }
      setModalOpen(false);
      load();
    } catch (error) {
      message.error(getErrorMessage(error, '保存违规词失败'));
    }
  };

  return (
    <div className="page-stack">
      <Card className="tool-card">
        <Space wrap>
          <Input.Search
            placeholder="搜索词条或分类"
            allowClear
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onSearch={load}
            style={{ width: 280 }}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增违规词
          </Button>
          <Upload
            showUploadList={false}
            accept=".csv,text/csv"
            customRequest={async (options) => {
              try {
                const result = await importTerms(options.file as File);
                options.onSuccess?.(result, new XMLHttpRequest());
                message.success(`已导入 ${result.importedCount} 条，跳过 ${result.skippedCount} 条`);
                load();
              } catch (error) {
                options.onError?.(error as Error);
                message.error(getErrorMessage(error, '导入失败'));
              }
            }}
          >
            <Button icon={<CloudUploadOutlined />}>导入 CSV</Button>
          </Upload>
        </Space>
      </Card>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={terms}
        pagination={{ pageSize: 10 }}
        columns={[
          { title: '词条', dataIndex: 'term', render: (value) => <Text strong>{value}</Text> },
          { title: '分类', dataIndex: 'category', render: (value) => value || '-' },
          {
            title: '严重级别',
            dataIndex: 'severity',
            render: (value: Severity) => <Tag color={severityMap[value].color}>{severityMap[value].label}</Tag>
          },
          {
            title: '匹配方式',
            dataIndex: 'matchType',
            render: (value: MatchType) => matchTypeMap[value]
          },
          {
            title: '状态',
            dataIndex: 'enabled',
            render: (value: boolean) => <Tag color={value ? 'green' : 'default'}>{value ? '启用' : '停用'}</Tag>
          },
          {
            title: '变体词',
            dataIndex: 'variants',
            ellipsis: true,
            render: (value) => value || '-'
          },
          {
            title: '更新时间',
            dataIndex: 'updatedAt',
            render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm')
          },
          {
            title: '操作',
            width: 150,
            render: (_, record) => (
              <Space>
                <Button icon={<EditOutlined />} onClick={() => openEdit(record)} />
                <Popconfirm
                  title="删除违规词"
                  description="删除后不会再参与后续检测。"
                  onConfirm={async () => {
                    await deleteTerm(record.id);
                    message.success('已删除');
                    load();
                  }}
                >
                  <Button danger icon={<DeleteOutlined />} />
                </Popconfirm>
              </Space>
            )
          }
        ]}
      />
      <Modal
        title={editing ? '编辑违规词' : '新增违规词'}
        open={modalOpen}
        onOk={submit}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="term" label="词条/正则表达式" rules={[{ required: true, message: '请输入词条' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="category" label="分类">
            <Input placeholder="例如：广告、辱骂、敏感内容" />
          </Form.Item>
          <Space.Compact block>
            <Form.Item name="severity" label="严重级别" rules={[{ required: true }]} style={{ width: '50%' }}>
              <Select
                options={[
                  { value: 'LOW', label: '低' },
                  { value: 'MEDIUM', label: '中' },
                  { value: 'HIGH', label: '高' },
                  { value: 'CRITICAL', label: '严重' }
                ]}
              />
            </Form.Item>
            <Form.Item name="matchType" label="匹配方式" rules={[{ required: true }]} style={{ width: '50%' }}>
              <Select
                options={[
                  { value: 'EXACT', label: '精确' },
                  { value: 'VARIANT', label: '变体' },
                  { value: 'REGEX', label: '正则' },
                  { value: 'SEMANTIC', label: '语义' }
                ]}
              />
            </Form.Item>
          </Space.Compact>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="价格类规则可选择“语义”，词条填写“价格”或“金额”。系统会召回 1块钱、1块、29.9、¥29.9 等候选，再由 AI 按上下文判断。"
          />
          <Form.Item name="enabled" label="状态">
            <Select
              options={[
                { value: true, label: '启用' },
                { value: false, label: '停用' }
              ]}
            />
          </Form.Item>
          <Form.Item name="variants" label="变体词">
            <Input.TextArea rows={4} placeholder="仅变体匹配使用，多个词用逗号或换行分隔" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function VideosPage() {
  const { message } = AntApp.useApp();
  const [videos, setVideos] = useState<VideoFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [subtitleFile, setSubtitleFile] = useState<File | undefined>();
  const [selected, setSelected] = useState<{ videoId: number; jobId?: number } | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      setVideos(await listVideos());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const uploadRequest = async (options: UploadRequestOption) => {
    const video = options.file;
    setUploading(true);
    try {
      if (!(video instanceof File)) {
        throw new Error('请选择有效的视频文件');
      }
      const result = await uploadVideo(video, subtitleFile, (percent) => {
        options.onProgress?.({ percent });
      });
      options.onSuccess?.(result, new XMLHttpRequest());
      message.success('视频已上传');
      setSubtitleFile(undefined);
      load();
    } catch (error) {
      const uploadError = error instanceof Error ? error : new Error(getErrorMessage(error, '上传失败'));
      options.onError?.(uploadError);
      message.error(getErrorMessage(error, '上传失败'));
    } finally {
      setUploading(false);
    }
  };

  const triggerJob = async (video: VideoFile) => {
    const job = await startJob(video.id);
    message.success('检测任务已启动');
    setSelected({ videoId: video.id, jobId: job.id });
    load();
  };

  const exportModerated = async (video: VideoFile) => {
    const result = await exportVideo(video.id);
    message.success(`已导出，删除片段 ${result.removedClipCount} 个`);
    load();
  };

  return (
    <div className="page-stack">
      <div className="stats-grid">
        <Card>
          <Statistic title="视频总数" value={videos.length} prefix={<VideoCameraOutlined />} />
        </Card>
        <Card>
          <Statistic
            title="已完成检测"
            value={videos.filter((item) => item.status === 'DETECTED' || item.status === 'EXPORTED').length}
            prefix={<FileSearchOutlined />}
          />
        </Card>
        <Card>
          <Statistic
            title="已导出视频"
            value={videos.filter((item) => item.status === 'EXPORTED').length}
            prefix={<ScissorOutlined />}
          />
        </Card>
      </div>
      <Card className="upload-card">
        <div className="subtitle-picker">
          <Upload
            maxCount={1}
            accept=".srt,.vtt"
            beforeUpload={(file) => {
              setSubtitleFile(file);
              return false;
            }}
            onRemove={() => setSubtitleFile(undefined)}
          >
            <Button icon={<FileSearchOutlined />}>选择字幕文件</Button>
          </Upload>
          <Text type="secondary">可选。选择后再上传视频；未选择字幕时会调用 Whisper/WhisperX ASR。</Text>
        </div>
        <Upload.Dragger
          multiple={false}
          showUploadList={false}
          customRequest={uploadRequest}
          accept="video/*"
          disabled={uploading}
        >
          <CloudUploadOutlined className="upload-icon" />
          <div className="upload-title">拖拽视频到此处，或点击选择文件</div>
          <Text type="secondary">系统优先解析字幕文件；没有字幕时抽取音频并调用本地 Whisper ASR 服务。</Text>
        </Upload.Dragger>
      </Card>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={videos}
        pagination={{ pageSize: 8 }}
        columns={[
          { title: '文件名', dataIndex: 'originalFilename', render: (value) => <Text strong>{value}</Text> },
          {
            title: '时长',
            dataIndex: 'durationSeconds',
            width: 100,
            render: (value) => seconds(value)
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 110,
            render: (value: VideoFile['status']) => {
              const color = value === 'FAILED' ? 'red' : value === 'EXPORTED' ? 'blue' : value === 'DETECTED' ? 'green' : 'gold';
              return <Tag color={color}>{value}</Tag>;
            }
          },
          {
            title: '上传时间',
            dataIndex: 'createdAt',
            width: 170,
            render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm')
          },
          {
            title: '操作',
            width: 300,
            render: (_, record) => (
              <Space wrap>
                <Button icon={<PlayCircleOutlined />} onClick={() => triggerJob(record)}>
                  开始检测
                </Button>
                <Button icon={<FileSearchOutlined />} onClick={() => setSelected({ videoId: record.id })}>
                  详情
                </Button>
                <Button icon={<ExportOutlined />} onClick={() => exportModerated(record)}>
                  导出
                </Button>
              </Space>
            )
          }
        ]}
      />
      <Drawer
        width="min(1120px, 96vw)"
        open={!!selected}
        onClose={() => {
          setSelected(null);
          load();
        }}
        destroyOnClose
        title="检测详情"
      >
        {selected && <JobDetail videoId={selected.videoId} initialJobId={selected.jobId} />}
      </Drawer>
    </div>
  );
}

function JobDetail({ videoId, initialJobId }: { videoId: number; initialJobId?: number }) {
  const { message } = AntApp.useApp();
  const [video, setVideo] = useState<VideoFile | null>(null);
  const [job, setJob] = useState<DetectionJob | null>(null);
  const [segments, setSegments] = useState<TranscriptSegment[]>([]);
  const [hits, setHits] = useState<TermHit[]>([]);
  const [timeline, setTimeline] = useState<TimelineItem[]>([]);
  const [suggestions, setSuggestions] = useState<ClipSuggestion[]>([]);
  const [loading, setLoading] = useState(false);

  const activeJobId = job?.id ?? initialJobId;

  const load = async () => {
    setLoading(true);
    try {
      const currentVideo = await getVideo(videoId);
      setVideo(currentVideo);
      let jobId = activeJobId;
      if (!jobId) {
        const jobs = await listJobs(videoId);
        jobId = jobs[0]?.id;
      }
      if (jobId) {
        const currentJob = await getJob(jobId);
        setJob(currentJob);
        if (currentJob.status === 'COMPLETED' || currentJob.status === 'FAILED') {
          const [nextSegments, nextHits, nextTimeline, nextSuggestions] = await Promise.all([
            listSegments(jobId),
            listHits(jobId),
            listTimeline(jobId),
            listClipSuggestions(jobId)
          ]);
          setSegments(nextSegments);
          setHits(nextHits);
          setTimeline(nextTimeline);
          setSuggestions(nextSuggestions);
        }
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [videoId, initialJobId]);

  useEffect(() => {
    if (!job || job.status === 'COMPLETED' || job.status === 'FAILED') {
      return undefined;
    }
    const timer = window.setInterval(load, 2500);
    return () => window.clearInterval(timer);
  }, [job?.id, job?.status]);

  const duration = video?.durationSeconds || Math.max(1, ...timeline.map((item) => item.endTime + 1), ...segments.map((item) => item.endTime));

  const regenerateSuggestions = async () => {
    if (!activeJobId) {
      return;
    }
    const next = await createClipSuggestions(activeJobId);
    setSuggestions(next);
    message.success('剪辑建议已重新生成');
  };

  const saveSuggestion = async (record: ClipSuggestion, status: ClipStatus) => {
    const next = await updateClipSuggestion(record.id, {
      startTime: record.startTime,
      endTime: record.endTime,
      status
    });
    setSuggestions((items) => items.map((item) => (item.id === record.id ? next : item)));
    message.success('剪辑建议已更新');
  };

  const exportedSuggestion = suggestions.find((item) => item.status === 'EXPORTED');

  return (
    <div className="detail-stack">
      {video && (
        <div className="video-panel">
          <video className="video-preview" src={videoContentUrl(video.id)} controls />
          {exportedSuggestion && (
            <video className="video-preview" src={exportContentUrl(exportedSuggestion.id)} controls />
          )}
        </div>
      )}
      {job ? (
        <Card>
          <Space align="center" size="large" wrap>
            <Progress type="circle" percent={job.progress} size={72} status={job.status === 'FAILED' ? 'exception' : 'active'} />
            <div>
              <Text strong>任务 #{job.id}</Text>
              <div className="job-status-line">
                <Tag color={job.status === 'FAILED' ? 'red' : job.status === 'COMPLETED' ? 'green' : 'blue'}>{job.status}</Tag>
                <Text type="secondary">{job.startedAt ? `开始于 ${dayjs(job.startedAt).format('HH:mm:ss')}` : '等待启动'}</Text>
              </div>
              {job.errorMessage && <Alert type="error" message={job.errorMessage} showIcon style={{ marginTop: 10 }} />}
            </div>
          </Space>
        </Card>
      ) : (
        <Alert type="info" message="该视频还没有检测任务，可以在视频列表点击开始检测。" showIcon />
      )}
      <Card title="违规词时间轴" loading={loading}>
        {timeline.length === 0 ? (
          <Text type="secondary">暂无 AI 判定违规的命中。</Text>
        ) : (
          <>
            <div className="timeline-track">
              {timeline.map((item) => (
                <div
                  key={item.hitId}
                  className="timeline-hit"
                  style={{
                    left: `${Math.max(0, (item.startTime / duration) * 100)}%`,
                    width: `${Math.max(1, ((item.endTime - item.startTime) / duration) * 100)}%`
                  }}
                  title={`${item.matchedText} ${seconds(item.startTime)}-${seconds(item.endTime)}`}
                />
              ))}
            </div>
            <div className="timeline-list">
              {timeline.map((item) => (
                <div className="timeline-row" key={item.hitId}>
                  <Tag color={severityMap[item.severity].color}>{severityMap[item.severity].label}</Tag>
                  <Text strong>{item.matchedText}</Text>
                  <Text>{seconds(item.startTime)} - {seconds(item.endTime)}</Text>
                  <Text type="secondary" ellipsis>{item.contextText}</Text>
                </div>
              ))}
            </div>
          </>
        )}
      </Card>
      <Card
        title="剪辑建议"
        extra={
          <Button icon={<ScissorOutlined />} onClick={regenerateSuggestions} disabled={!activeJobId}>
            重新生成
          </Button>
        }
      >
        <Table
          rowKey="id"
          dataSource={suggestions}
          pagination={false}
          columns={[
            { title: '违规词', dataIndex: 'matchedText' },
            {
              title: '开始',
              dataIndex: 'startTime',
              render: (_, record) => (
                <InputNumber
                  min={0}
                  precision={2}
                  value={record.startTime}
                  onChange={(value) =>
                    setSuggestions((items) =>
                      items.map((item) => (item.id === record.id ? { ...item, startTime: Number(value || 0) } : item))
                    )
                  }
                />
              )
            },
            {
              title: '结束',
              dataIndex: 'endTime',
              render: (_, record) => (
                <InputNumber
                  min={0}
                  precision={2}
                  value={record.endTime}
                  onChange={(value) =>
                    setSuggestions((items) =>
                      items.map((item) => (item.id === record.id ? { ...item, endTime: Number(value || 0) } : item))
                    )
                  }
                />
              )
            },
            {
              title: '状态',
              dataIndex: 'status',
              render: (value: ClipStatus) => <Tag color={clipMap[value].color}>{clipMap[value].label}</Tag>
            },
            {
              title: '操作',
              render: (_, record) => (
                <Space>
                  <Button onClick={() => saveSuggestion(record, 'CONFIRMED')}>确认</Button>
                  <Button onClick={() => saveSuggestion(record, 'IGNORED')}>忽略</Button>
                </Space>
              )
            }
          ]}
        />
      </Card>
      <Card title="命中与 AI 复核">
        <Table
          rowKey="id"
          dataSource={hits}
          pagination={{ pageSize: 6 }}
          columns={[
            { title: '词', dataIndex: 'matchedText' },
            {
              title: '时间',
              render: (_, record) => `${seconds(record.startTime)} - ${seconds(record.endTime)}`
            },
            {
              title: '判定',
              dataIndex: 'reviewStatus',
              render: (value: ReviewStatus) => <Tag color={reviewMap[value].color}>{reviewMap[value].label}</Tag>
            },
            {
              title: '置信度',
              dataIndex: 'aiConfidence',
              render: (value?: number) => (value === undefined ? '-' : `${Math.round(value * 100)}%`)
            },
            {
              title: 'AI 原因',
              render: (_, record) => record.aiReview?.reason || '-'
            }
          ]}
        />
      </Card>
      <Card title="字幕片段">
        <div className="segment-list">
          {segments.map((segment) => (
            <div className="segment-row" key={segment.id}>
              <Text code>{seconds(segment.startTime)} - {seconds(segment.endTime)}</Text>
              <Text>{segment.text}</Text>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}

export default App;
