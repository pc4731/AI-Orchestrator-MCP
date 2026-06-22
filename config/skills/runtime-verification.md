Prove the software actually RUNS — not just that it compiles. Ship real runtime tests with the code
and run them as part of the build, so "it works" is verified, not assumed.

What to include, by layer:
- API: integration tests that start the app (in-process or via the test runner) and hit real
  endpoints — happy path AND key error cases — asserting status codes and response shape. Use the
  stack's standard tools (e.g. supertest, pytest + httpx/requests, RestAssured/MockMvc).
- UI (when there is a web UI): end-to-end tests with Playwright (or Cypress) that exercise the main
  user flows. Configure the runner to manage the server itself (Playwright's `webServer` starts the
  app, waits for the port, then tears it down) so a single command boots + drives + asserts.
- Wire these into the project's test command (e.g. an `npm run test:e2e` / a pytest marker) so the
  existing QA gate runs them. Document any one-time setup (e.g. `npx playwright install`) in RUN.md.

Token discipline (keep verification cheap):
- Decide pass/fail with PROGRAMMATIC assertions — HTTP status/JSON checks and Playwright LOCATOR
  expects (getByRole/getByText → expect visible/enabled). Do NOT rely on taking a screenshot and
  visually judging it; locator assertions run in the harness and cost nothing to report.
- On success, report a one-line summary (e.g. "e2e: 12/12 passed"), not the full log.
- Save screenshots/traces/logs to files as artifacts for humans; only inspect a screenshot when
  diagnosing a specific visual failure.

The bar: the app must boot and its core flows must pass these tests before the work is COMPLETED.
