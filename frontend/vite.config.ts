import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    allowedHosts: ["*", "10428260mx6sn.vicp.fun"],
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY ?? 'http://localhost:8090',
        changeOrigin: true
      }
    }
  }
});
