import { desktopCapturer, session } from 'electron';

/**
 * Answers the renderer's getDisplayMedia call with the primary screen plus
 * Windows loopback audio. Without a handler Electron denies the request, and
 * `audio: 'loopback'` is what makes system audio available at all — it needs
 * Electron >= 39 and a video source in the same request.
 *
 * The renderer drops the video track immediately; we never look at the screen.
 */
export function registerDisplayMediaHandler(): void {
  session.defaultSession.setDisplayMediaRequestHandler(
    (_request, callback) => {
      void desktopCapturer.getSources({ types: ['screen'] }).then(([screen]) => {
        // An empty response denies the request, which surfaces as a rejected
        // getDisplayMedia promise in the renderer.
        callback(screen ? { video: screen, audio: 'loopback' } : {});
      });
    },
    // The system picker cannot grant loopback audio, and we do not want a
    // picker dialog in front of a meeting anyway.
    { useSystemPicker: false },
  );
}
