import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the browser talks to Vite, and Vite forwards /api to the gateway,
// so the app and the API share one origin and no CORS setup is needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8000',
    },
  },
});
