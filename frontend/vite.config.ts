import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

// The dev server (and `vite preview`) forward API calls to the backend so the browser stays same-origin.
// The target comes from VITE_DEV_PROXY_TARGET (see .env.example); the default suits a backend on this machine.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const target = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/graphql': { target },
        '/graphiql': { target },
      },
    },
  };
});
