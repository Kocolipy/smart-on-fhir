---
okf_version: "0.2"
---

# Frontend reference

The SPA's reference docs. The rules an agent must hold are in `../AGENTS.md`; these hold the detail and the reasoning.

# Architecture

- [Frontend architecture](ARCHITECTURE.md) - The SPA's layers file by file, the request and time seams, the gated read and write, the @/ alias, the token pipeline and routing.
- [Design system](DESIGN.md) - The component library's design tokens — colors, typography, spacing, shapes — and how its components use them.

# Contracts

- [Backend contract](BACKEND_CONTRACT.md) - The runtime contract with the backend: CSRF, 401 versus 403, the session bounds, Epic Login's landing, the FHIRcast release and teardown, and the CSP.

# Testing

- [Frontend testing guide](TESTING_GUIDE.md) - Running the suites, unit tests and the test adapter, architecture rules, scoped Stryker, Semgrep and fallow.
- [Frontend E2E guide](E2E_GUIDE.md) - The Playwright projects and their order, routing a spec, calling the API from a spec, forcing refusals, and the flakiness rules.
