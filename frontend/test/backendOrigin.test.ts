import { describe, expect, it } from "vitest";

import { backendOrigin } from "../backend-origin";

// The backend listens on SERVER_PORT, which application.yaml reads with a
// default of 8080 and the root scripts export from backend/.env. Everything
// here that reaches the backend — the Vite `/api` proxy and the E2E suite's
// direct SCIM and actuator calls — names it by this origin. That each of them
// does is the E2E suite's to show: `make integration-test` with a non-default
// SERVER_PORT fails every project through the proxy, and every SCIM call, when
// one of them still names 8080.
describe("the backend's local origin", () => {
  it("is port 8080 when SERVER_PORT is absent", () => {
    expect(backendOrigin(undefined)).toBe("http://localhost:8080");
  });

  it("is port 8080 when SERVER_PORT is blank, as the scripts read it", () => {
    expect(backendOrigin("")).toBe("http://localhost:8080");
  });

  it("is the port SERVER_PORT names", () => {
    expect(backendOrigin("8091")).toBe("http://localhost:8091");
  });
});
