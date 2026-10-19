import { fileURLToPath } from "node:url";
import { configDefaults, coverageConfigDefaults, defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react-swc";

// Pinned so a time assertion means the same thing on a developer's machine as
// in CI, and pinned to a zone that is neither UTC nor Asia/Singapore: the SPA
// reads and shows every time in Singapore whatever the machine's zone
// (`src/lib/time.ts`), and only a machine zone hours away (fifteen to sixteen
// here, as it keeps daylight saving) lets a test see code that slipped back to
// the machine's.
process.env.TZ = "America/Los_Angeles";

export default defineConfig({
  // Deliberately not @tailwindcss/vite: unit tests stub CSS imports, so
  // generating the utility stylesheet for every test file would be pure cost.
  // The one thing that needs real CSS is the E2E suite, which runs against the
  // dev server and therefore against vite.config.ts.
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    exclude: [...configDefaults.exclude, "**/.claude/**", "**/.stryker-tmp/**", "**/e2e/**"],
    coverage: {
      exclude: [
        ...coverageConfigDefaults.exclude,
        // Files with zero executable statements. v8 reports 0/0 as 0%, so
        // leaving them in drags the report down with rows no test can cover.
        "**/*.css",
        "**/*.d.ts",
        "**/e2e/**",
        "**/scripts/**",
        "playwright.config.ts",
        // The composition root: it calls createRoot against the real document
        // and has no branch to assert. The smoke E2E covers that it mounts.
        "src/main.tsx",
      ],
    },
    environment: "happy-dom",
    globals: true,
    setupFiles: ["./test/setup.ts"],
  },
});
