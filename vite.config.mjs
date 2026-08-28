import { defineConfig } from 'vite';
import { resolve } from 'node:path';

const frontendRoot = resolve(import.meta.dirname, 'frontend');
const pageRoutes = new Map([
  ['/login', '/login.html'],
  ['/admin', '/admin.html'],
  ['/lobby', '/lobby.html'],
  ['/player', '/player.html'],
  ['/sandbox-player', '/sandbox-player.html']
]);

export default defineConfig({
  root: frontendRoot,
  base: '/',
  plugins: [{
    name: 'ace-dice-page-routes',
    configureServer(server) {
      server.middlewares.use((request, _response, next) => {
        const url = new URL(request.url || '/', 'http://vite.local');
        const htmlPath = pageRoutes.get(url.pathname);
        if (htmlPath) request.url = htmlPath + url.search;
        next();
      });
    }
  }],
  build: {
    outDir: resolve(frontendRoot, 'dist'),
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      input: {
        index: resolve(frontendRoot, 'index.html'),
        login: resolve(frontendRoot, 'login.html'),
        admin: resolve(frontendRoot, 'admin.html'),
        lobby: resolve(frontendRoot, 'lobby.html'),
        player: resolve(frontendRoot, 'player.html'),
        sandboxPlayer: resolve(frontendRoot, 'sandbox-player.html')
      }
    }
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true
      }
    }
  }
});
