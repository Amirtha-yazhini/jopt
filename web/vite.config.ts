/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The Java backend (`jopt playground` / `gradle playground`) serves the API on :7070.
const backend = process.env.JOPT_BACKEND ?? 'http://localhost:7070';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 4096, // Monaco is large by nature; it is split into its own chunk.
    rollupOptions: {
      output: { manualChunks: { monaco: ['monaco-editor/esm/vs/editor/edcore.main'] } },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
