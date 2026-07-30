import { resolve } from 'node:path';
import { defineConfig, externalizeDepsPlugin } from 'electron-vite';
import react from '@vitejs/plugin-react';

// `@vaderai/protocol` is a workspace package, so pnpm links it into node_modules
// as a symlink into the store. Left external it would have to be resolved at
// runtime from inside the asar, which is the classic way a packaged Electron app
// dies with "Cannot find module" on a machine that has no workspace. Bundling it
// instead means main and preload ship with no runtime dependencies at all.
const bundleWorkspacePackages = { exclude: ['@vaderai/protocol'] };

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin(bundleWorkspacePackages)],
  },
  preload: {
    plugins: [externalizeDepsPlugin(bundleWorkspacePackages)],
  },
  renderer: {
    root: resolve(__dirname, 'src/renderer'),
    // One .env at the monorepo root serves both apps; without this Vite would
    // look for it next to the renderer sources.
    envDir: resolve(__dirname, '../..'),
    build: {
      rollupOptions: {
        input: resolve(__dirname, 'src/renderer/index.html'),
      },
    },
    plugins: [react()],
  },
});
