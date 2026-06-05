import { useEffect, useRef } from 'react';
import videojs from 'video.js';
import 'video.js/dist/video-js.css';

/**
 * 跳转指令:供审核台命令播放器跳到指定时间点。
 * time 为目标时间(秒);nonce 仅作为变化触发器——即使两次跳转的 time 相同,
 * 只要 nonce 不同就会让跳转 useEffect 重新执行,从而支持「重复点击同一时间轴命中再次跳转」。
 */
export interface PlayerSeekCommand {
  /** 目标跳转时间,单位秒 */
  time: number;
  /** 单调递增的触发标记;time 相同也靠它变化触发 useEffect 重跳 */
  nonce: number;
}

interface VideoPlayerProps {
  /** 无障碍标签,写入 video 元素的 aria-label */
  label: string;
  /** 视频源地址(原视频或导出片段) */
  src: string;
  /** 外部跳转指令;变化时触发 seek */
  seekCommand?: PlayerSeekCommand;
  /** 元数据就绪后回传时长(秒),供审核台同步时间轴 */
  onDuration?: (duration: number) => void;
}

/**
 * 视频播放器组件:封装 video.js,供审核台播放原视频/导出片段,
 * 并支持通过 {@link PlayerSeekCommand} 在点击时间轴命中时跳转到对应时间点。
 *
 * @param label 无障碍标签
 * @param src 视频源地址
 * @param seekCommand 外部跳转指令
 * @param onDuration 时长回调
 */
export function VideoPlayer({ label, src, seekCommand, onDuration }: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const playerRef = useRef<ReturnType<typeof videojs> | null>(null);
  // 用 ref 持有最新的 onDuration,避免把它列入初始化 effect 的依赖而导致播放器被反复重建。
  const onDurationRef = useRef(onDuration);

  // 每次 onDuration 变化时同步到 ref,初始化 effect 内通过 ref 读取最新回调。
  useEffect(() => {
    onDurationRef.current = onDuration;
  }, [onDuration]);

  // 初始化:空依赖数组保证播放器只创建一次;cleanup 中 dispose 防止内存泄漏。
  useEffect(() => {
    if (!containerRef.current) {
      return undefined;
    }

    const videoElement = document.createElement('video-js') as unknown as HTMLVideoElement;
    videoElement.classList.add('vjs-big-play-centered', 'moderation-video-js');
    videoElement.setAttribute('aria-label', label);
    containerRef.current.appendChild(videoElement);

    const player = videojs(videoElement, {
      controls: true,
      fluid: true,
      responsive: true,
      preload: 'metadata',
      playbackRates: [0.5, 0.75, 1, 1.25, 1.5, 2],
      controlBar: {
        pictureInPictureToggle: true
      },
      sources: [{ src, type: 'video/mp4' }]
    });

    playerRef.current = player;
    const reportDuration = () => {
      const duration = player.duration();
      if (duration !== undefined && Number.isFinite(duration) && duration > 0) {
        onDurationRef.current?.(duration);
      }
    };
    player.on('loadedmetadata', reportDuration);
    player.on('durationchange', reportDuration);

    return () => {
      if (!player.isDisposed()) {
        player.dispose();
      }
      playerRef.current = null;
    };
  }, []);

  // src 变化时切换视频源并重新加载,无需重建播放器实例。
  useEffect(() => {
    const player = playerRef.current;
    if (!player || player.isDisposed()) {
      return;
    }
    player.src({ src, type: 'video/mp4' });
    player.load();
  }, [src]);

  // 跳转:若元数据已就绪(readyState>0)立即 seek;否则等 once('loadedmetadata') 触发后再跳。
  useEffect(() => {
    const player = playerRef.current;
    if (!seekCommand || !player || player.isDisposed()) {
      return;
    }
    const target = Math.max(0, seekCommand.time);
    const seek = () => {
      player.currentTime(target);
    };
    if (player.readyState() > 0) {
      seek();
    } else {
      player.one('loadedmetadata', seek);
    }
  }, [seekCommand]);

  return (
    <div ref={containerRef} className="video-sdk-shell" data-vjs-player />
  );
}
