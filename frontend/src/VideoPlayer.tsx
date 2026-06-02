import { useEffect, useRef } from 'react';
import videojs from 'video.js';
import 'video.js/dist/video-js.css';

export interface PlayerSeekCommand {
  time: number;
  nonce: number;
}

interface VideoPlayerProps {
  label: string;
  src: string;
  seekCommand?: PlayerSeekCommand;
  onDuration?: (duration: number) => void;
}

export function VideoPlayer({ label, src, seekCommand, onDuration }: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const playerRef = useRef<ReturnType<typeof videojs> | null>(null);
  const onDurationRef = useRef(onDuration);

  useEffect(() => {
    onDurationRef.current = onDuration;
  }, [onDuration]);

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

  useEffect(() => {
    const player = playerRef.current;
    if (!player || player.isDisposed()) {
      return;
    }
    player.src({ src, type: 'video/mp4' });
    player.load();
  }, [src]);

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
