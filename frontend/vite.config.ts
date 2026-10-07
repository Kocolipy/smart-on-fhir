import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react-swc";
import tailwindcss from "@tailwindcss/vite";
import { compression } from "vite-plugin-compression2";

export default defineConfig({
  resolve: {
    // `@/x` -> `src/x`. Kept in step with tsconfig.json `paths` and
    // vitest.config.ts; a change here that misses either one breaks a
    // different tool than the one you were editing.
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    proxy: {
      // Not the string shorthand, which also sets `changeOrigin: true`: the
      // backend must see the browser's own Host, as it does in production where
      // it serves the SPA itself, or its redirects — Epic Login's `302 /` among
      // them — name localhost:8080, where there is no SPA, instead of this server.
      "/api": { target: "http://localhost:8080", changeOrigin: false },
    },
    // Vite full-reloads *every* connected page when a watched `.html` under
    // the project root changes, and it watches the whole root. A finishing
    // Playwright run writes `playwright-report/index.html` and
    // `npm run test:coverage` writes `coverage/index.html`, so without this a
    // test run reloads whatever pages are open — including pages another test
    // is driving. A user `ignored` list is added to Vite's defaults rather
    // than replacing them, and Vite already ignores `**/test-results/**`.
    watch: {
      ignored: ["**/playwright-report/**", "**/coverage/**", "**/.claude/**"],
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: (id) => (id.includes("node_modules") ? "vendor" : undefined),
      },
    },
  },
  plugins: [tailwindcss(), react(), compression()],
});
