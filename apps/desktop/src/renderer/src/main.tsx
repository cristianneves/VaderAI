import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './index.css';

// Renderer failures otherwise only reach the devtools console, which nobody has
// open on a packaged build. Forwarded to the same local log the main process
// writes; nothing leaves the machine.
window.addEventListener('error', (event) =>
  window.vader.reportError(
    event.error instanceof Error ? (event.error.stack ?? event.message) : event.message,
  ),
);
window.addEventListener('unhandledrejection', (event) =>
  window.vader.reportError(
    event.reason instanceof Error
      ? (event.reason.stack ?? event.reason.message)
      : String(event.reason),
  ),
);

const container = document.getElementById('root');
if (!container) throw new Error('#root not found');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
