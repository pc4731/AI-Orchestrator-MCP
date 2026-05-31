Design HTTP APIs to REST conventions:
- Resource-oriented, plural nouns; correct verbs (GET/POST/PUT/PATCH/DELETE) and status codes.
- Consistent JSON shapes; an error envelope with code + message; input validation on every endpoint.
- Pagination, filtering, and sorting for collections; idempotency where it matters.
- Versioning strategy (e.g. /v1); sensible rate limiting; no secrets in URLs.
- Document each endpoint: method, path, request/response schema, and error cases.
