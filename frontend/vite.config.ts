/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발: npm run dev (http://localhost:5173), /api 요청은 스프링 부트(8080)로 넘긴다.
// 배포: npm run build 결과(dist/)를 frontend/Dockerfile의 nginx가 내준다. /api는 앞단 nginx가 백엔드로 넘긴다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
  },
});
