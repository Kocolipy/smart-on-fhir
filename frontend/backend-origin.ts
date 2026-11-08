/**
 * The local backend's origin, for what reaches the backend from outside the
 * SPA: the Vite dev server's `/api` proxy (`vite.config.ts`) and the E2E
 * suite's direct SCIM and actuator calls (`test/e2e/scim.helpers.ts`).
 *
 * The port is `SERVER_PORT`, the one the backend itself listens on
 * (`server.port: ${SERVER_PORT:8080}` in `backend/src/main/resources/application.yaml`).
 * The root scripts export it from `backend/.env` before they start the backend,
 * Vite or Playwright, and read an unset or blank value as 8080, as this does.
 * Kept free of `@playwright/test` and of `process`, so a unit test can import it
 * (`test/backendOrigin.test.ts`).
 */
export function backendOrigin(serverPort: string | undefined): string {
  return `http://localhost:${serverPort || "8080"}`;
}
