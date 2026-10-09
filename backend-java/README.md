# Mealie Java backend

Spring Boot 4 / Java 21 backend that takes over Mealie's API from the Python backend route by route, behind the
gateway described in [docs/rebuild/gateway.md](../docs/rebuild/gateway.md). It listens on **:9100**
(`JAVA_API_PORT`). The gateway currently sends `GET /api/app/about` and everything under `/api/organizers/tags` to
Java; other routes remain on Python.

```bash
task java           # run against the same database as `task py`
task java:test      # unit + integration tests (throwaway SQLite)
task java:test:db ENGINE=sqlite|postgres   # read-only dialect checks against the real dev database
```

The build uses the Maven wrapper (`./mvnw`); the only prerequisite is a JDK 21.

## Database access

The database layer uses MyBatis-Plus with mapper interfaces in `persistence/mapper/` and SQL XML files in
`src/main/resources/mapper/`. Complex queries stay in XML; repositories convert mapper projections into domain
records. There is no JPA or Hibernate. The rules:

- **The schema belongs to Python/Alembic.** Java never runs DDL. `spring.sql.init.mode=never`, and SQLite is opened
  without the CREATE flag, so a missing database file is an error, not a new empty DB.
- **Configuration is shared.** Java reads the same variables as `mealie/core/settings/db_providers.py`:
  `DB_ENGINE`, `DATA_DIR`, `PRODUCTION`, `TESTING`, `POSTGRES_USER/PASSWORD/SERVER/PORT/DB` and
  `POSTGRES_URL_OVERRIDE`. Process env wins over the repo's `.env`. The data dir is resolved the same way as in
  `mealie/core/config.py`. Secrets from `/run/secrets` are not read yet.
- **Engine differences live only in `db/SqlDialect`.** Write one SQL string. Pass every UUID, boolean, datetime and
  date through the dialect, both when binding parameters and when reading columns:

  | Type                   | Postgres                     | SQLite (as SQLAlchemy stores it)          |
  |------------------------|------------------------------|-------------------------------------------|
  | GUID                   | native `uuid`                | `CHAR(32)` lowercase hex, no dashes       |
  | Boolean                | `boolean`                    | INTEGER `0`/`1`                           |
  | NaiveDateTime (UTC)    | `timestamp without time zone`| TEXT `YYYY-MM-DD HH:MM:SS.ffffff`         |
  | Date                   | `date`                       | TEXT `YYYY-MM-DD`                         |

  Mapper parameters that refer to existing UUID columns must pass through `dialect.uuid(...)`. XML result maps use
  the shared UUID and timestamp type handlers for values read from either engine.

  Always bind values as parameters. Never put a UUID or boolean literal into SQL text. SQLite compares datetimes as
  strings, so they must be written in exactly SQLAlchemy's format, which `dialect.timestamp()` does.
- **Enums and JSON need nothing special.** Postgres uses native enum types (e.g. `users.auth_method`). The Postgres
  connection sets `stringtype=unspecified`, so a plain `String` parameter binds to enum and JSON columns on both
  engines.
- **Prefer SQL both engines accept.** `LIMIT/OFFSET`, `ON CONFLICT … DO UPDATE` and `RETURNING` work on both. Postgres
  `LIKE` is case-sensitive and SQLite's isn't, so use `LOWER(col) LIKE LOWER(:q)`. Avoid `ILIKE`, `::casts` and
  `CAST(x AS TIMESTAMP)` (SQLite turns that into a number).

## Auth

Spring Security owns the authentication flow. `MealieAuthenticationFilter` extracts credentials,
`MealieAuthenticationProvider` delegates Mealie-compatible JWT validation to `auth/AuthService`, and the resulting
`AuthUser` principal is stored in the `SecurityContext`. `auth/AuthService` verifies Mealie's HS256 JWTs exactly like
`get_current_user()` in
`mealie/core/dependencies/dependencies.py`:

- The token comes from `Authorization: Bearer`, with the `mealie.access_token` cookie as fallback.
- `exp`, `nbf` and a future `iat` are rejected with no leeway.
- API tokens (`long_token`) must exist in `long_live_tokens`.
- Tokens issued before `users.tokens_valid_after` are rejected.

The secret is `<DATA_DIR>/.secret` in production, and `shh-secret-test-key` when `PRODUCTION=false`, as in Python.
It's read-only from Java and reloaded if the file changes. Errors use Python's body shape, `{"detail": ...}`, with
the same status codes and headers.

To require a user in a migrated controller, declare a parameter: `public Foo get(AuthUser user)`. The MVC bridge reads
that principal from Spring Security rather than authenticating the request itself. Public routes remain public even if
they receive bad credentials; a protected controller rejects them when it requests the current user.

## Matching Python exactly

A migrated route must answer like the Python route did, including its errors, because the frontend and API clients
can't tell which backend served them. The helpers for that:

- **Validation (`web/validation/`).** Controllers validate parameters by hand with `ValidationErrors`, in FastAPI's
  order (path, query, body), and throw them together as one 422. `ApiExceptionHandler` renders the 422 the way
  Python does: FastAPI's `{"detail": [...]}` in production, and Mealie's debug body (`status_code`/`message`/`data`)
  otherwise. The debug message includes the Python endpoint's source location, so annotate each method with
  `@PythonEndpoint`. Declare `PyRequestBody` *before* `AuthUser`, because FastAPI parses the body before it
  authenticates.
- **Python behaviour (`compat/`).** These are ports of `json.loads` (with its error messages), pydantic's UUID4 and int
  parsing, `repr()`, python-slugify (with text-unidecode's own table), and `random.seed(str)` + `shuffle`.
  `PythonCompatibilityTest` checks them against outputs recorded from Python by `dev/rebuild/compat_vectors.py`.
- **Pagination and query filters (`query/`).** `PaginationQuery`, `PageRequest` and `Pagination` reproduce
  `RepositoryGeneric.page_all()`. `QueryFilterSql` ports QueryFilterBuilder; attribute paths resolve through the
  model descriptions in `FilterEntities`. A path that goes through a relationship not described there gets a 400
  saying so (Python would follow any relationship), so add descriptions as routes need them.
- **Unhandled errors** are a plain-text `Internal Server Error` 500, like Starlette. Python routes that crash on bad
  input (e.g. `PUT /api/organizers/tags/{unknown id}`) crash the same way in Java on purpose.
- **SQL that relies on row order.** Where Python returns rows without an ORDER BY (eager-loaded relationships), copy
  SQLAlchemy's statement exactly, including its select list. On SQLite the select list decides which automatic index
  the planner builds, and that changes the order.

## Events

Notifications (Household > Notifiers) go through Python's event bus, which owns the Apprise integration. After a
write commits, `events/EventBridge` posts the event to Python's `POST /api/internal/events`
(`mealie/routes/internal/controller_events.py`). It sends the event type, the document data, and a translation key
with parameters, and forwards the request's `Accept-Language`, so Python renders the same message it would have sent.
Requests are signed with an HMAC derived from the shared secret, and the gateway answers `/api/internal/*` with a 404.
Delivery is asynchronous and failures are only logged, like Python's background tasks. Java finds Python at
`MEALIE_PYTHON_URL`, which defaults to `http://127.0.0.1:${API_PORT:-9000}`. To publish a new kind of event, add its
document type (and URL builder, if any) to that Python module.
