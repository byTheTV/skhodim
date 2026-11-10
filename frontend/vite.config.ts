import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Сборка кладётся туда, откуда её отдаёт Spring Boot, поэтому получается один JAR (NFR-2).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
