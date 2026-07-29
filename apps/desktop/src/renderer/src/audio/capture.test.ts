import { describe, expect, it, vi } from 'vitest';
import { stripVideoTracks } from './capture';

type FakeTrack = { kind: 'audio' | 'video'; stop: () => void };

function fakeStream(kinds: FakeTrack['kind'][]): {
  stream: MediaStream;
  tracks: FakeTrack[];
  removed: FakeTrack[];
} {
  const tracks: FakeTrack[] = kinds.map((kind) => ({ kind, stop: vi.fn() }));
  const removed: FakeTrack[] = [];
  const stream = {
    getVideoTracks: () => tracks.filter((track) => track.kind === 'video'),
    removeTrack: (track: FakeTrack) => removed.push(track),
  } as unknown as MediaStream;
  return { stream, tracks, removed };
}

describe('stripVideoTracks', () => {
  it('stops and removes every video track', () => {
    const { stream, tracks, removed } = fakeStream(['video', 'audio', 'video']);

    stripVideoTracks(stream);

    const video = tracks.filter((track) => track.kind === 'video');
    expect(removed).toEqual(video);
    for (const track of video) expect(track.stop).toHaveBeenCalledOnce();
  });

  it('leaves audio tracks running — they are the whole point of the stream', () => {
    const { stream, tracks, removed } = fakeStream(['video', 'audio']);

    stripVideoTracks(stream);

    const audio = tracks.filter((track) => track.kind === 'audio');
    expect(removed).not.toContain(audio[0]);
    expect(audio[0]?.stop).not.toHaveBeenCalled();
  });

  it('is a no-op on an audio-only stream', () => {
    const { stream, removed } = fakeStream(['audio']);

    stripVideoTracks(stream);

    expect(removed).toEqual([]);
  });
});
