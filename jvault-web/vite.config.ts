/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // The SPA never talks to Jira. Everything goes through jvault, which is the only place
    // that knows what must not leave — proxying in dev keeps that true of the dev setup too.
    proxy: { '/api': 'http://localhost:8080', '/c': 'http://localhost:8080' },
  },
  test: { globals: true, environment: 'jsdom' },
});
