# Mealie Java backend

Spring Boot 4 / Java 21 backend that takes over Mealie's API from the Python backend route by route, behind the
gateway described in [docs/rebuild/gateway.md](../docs/rebuild/gateway.md). It listens on **:9100**
(`JAVA_API_PORT`). The gateway sends everything under `/api/organizers/tags`, the public app-about endpoints, authenticated `GET /api/recipes` listing, and
`GET /api/recipes/{slug}` detail to Java. Detail accepts a recipe slug or UUID. Other application endpoints remain
on Python. `POST /api/auth/token` now uses Java for local Mealie password login. Authenticated
`GET /api/users/self` also uses Java; its sibling ratings/favorites routes and user writes remain on Python.
Authenticated `GET /api/groups/self` uses Java too; group preferences and AI-provider summaries are read from the
shared database without returning provider API keys. Other group routes remain on Python.

The password login shares Python's user table, failed-attempt lockout, HS256 secret, token lifetime, and session
cookie format. LDAP authentication still runs in Python: for LDAP deployments, start the gateway with
`AUTH_TOKEN_UPSTREAM=host.docker.internal:9000`.
Changing this gateway environment variable requires recreating the gateway container.

```bash
task java           # run against the same database as `task py`
task java:test      # unit + integration tests (throwaway SQLite)
task java:test:db ENGINE=sqlite|postgres   # read-only dialect checks against the real dev database
```

The build uses the Maven wrapper (`./mvnw`); the only prerequisite is a JDK 21.

Every request handled by a Java controller writes an access marker to the Java log, for example:

```text
JAVA_BACKEND_ACCESS time=14:32:08 method=GET path=/api/app/about
```

This complements the gateway's `X-Mealie-Backend` response header when checking whether a route has moved to Java.

## Database access

The shared database layer uses MyBatis-Plus with mapper interfaces in `persistence/mapper/` and SQL XML files in
`src/main/resources/mapper/`. Repositories convert mapper projections into domain records. The migrated recipe reads
use Spring JDBC with bound SQL for their nested projections and dynamic filters. Both approaches use the same
configured database and SQL dialect. There is no JPA or Hibernate. The rules:

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

## Single-recipe reads

`recipe/RecipeController` delegates to a read-only service and JDBC repository. The response projects the existing
Python-owned schema, including nested ingredients, referenced recipes, instructions, organizers, notes, assets,
comments and calculated ingredient display. No request is forwarded to Python.

The existing `AuthUser` resolver accepts session JWTs, registered API tokens and the access-token cookie. Reads
are scoped to the authenticated user's group, including for admins. Python allows same-group reads across private
households; recipe `public` and `locked` settings do not change this authenticated route's read access.
Missing or other-group recipes return Python's 404 body; invalid authentication returns its 401 body and challenge.

After `task java:package`, the reproducible HTTP matrix starts isolated Python and Java servers, initializes the
schema with Python/Alembic, and compares actual status, full JSON and cache/auth headers:

```bash
uv run --frozen python dev/rebuild/recipe_get_parity.py --report /tmp/recipe-sqlite.json
uv run --frozen python dev/rebuild/recipe_get_parity.py --gateway /path/to/caddy --report /tmp/recipe-gateway.json
uv run --frozen python dev/rebuild/recipe_get_parity.py --engine postgres --gateway /path/to/caddy --report /tmp/recipe-postgres.json
```

For PostgreSQL, provide a fresh disposable database named `compx574_recipe_parity` on localhost:55432 with
user/password `mealie`; the harness never uses the normal dev database. The SQLite database is temporary.
The harness uses ports 9200/9210/9280, saves service logs beside its JSON report and stops its servers afterwards.
With Caddy, it also verifies other routes still use Python and proves recipe reads work after Python is stopped.

## Recipe listing

`GET /api/recipes` uses the existing authentication resolver, a read-only service transaction and JDBC queries.
It supports pagination (including -1 conventions), sorting/null positions, seeded random order, search, queryFilter,
category/tag/tool/food/household filters, requireAll flags and cookbooks. SQLite uses token and quoted phrase search;
PostgreSQL also uses the original pg_trgm operators with a transaction-local threshold. Food filters include
ingredient substitutions. Filterable fields and relationship joins are whitelisted in
`src/main/resources/recipe-list/schema.json`, copied from the existing Python SQLAlchemy metadata. Request values
are bound parameters. Organizer summary reads are batched; full recipe ingredients are not loaded for listing.

Listing remains group-scoped, including for administrators, and includes private households in the same group.
User-specific rating/lastMade expressions apply to sorting and query filters; projected summary values remain the
recipe's stored values, matching Python. Private attributes such as user.email/password remain unfilterable.

The response keeps Python's snake-case pagination envelope, camel-case recipe summaries, +00:00 summary dates,
and relative `/recipes?...` pagination links. The frontend already prefixes those links with /api. Unknown query
parameters and the last value of repeated parameters are retained in links, as in Starlette.

Build first, then run the disposable HTTP matrix:

```bash
task java:package
uv run --frozen python dev/rebuild/recipe_list_parity.py --gateway /path/to/caddy --report /tmp/list-sqlite.json
uv run --frozen python dev/rebuild/recipe_list_parity.py --engine postgres --postgres-database compx574_recipe_list_002_03 --gateway /path/to/caddy --report /tmp/list-postgres.json
```

PostgreSQL requires a newly created, empty database whose name is compx574_recipe_list_002 or begins with
compx574_recipe_list_002_. The test server is localhost:55432, with disposable test credentials mealie/mealie.
The harness refuses a non-experiment database name or a database that already has tables. SQLite uses a new
temporary directory. All fixtures, including token revocation tests, are confined to these databases. Nothing is
created or removed in the shared development database. Use --baseline-only to capture Python without starting Java.
If the default isolated ports (9200/9210/9280) are occupied, use `--port-offset 200` to select 9400/9410/9480.
The harness checks port availability before seeding fixtures, so it cannot accidentally test an older preview server.

The harness records full raw responses, failures and service logs. Recipe item order is compared exactly; sort/search
cases add the supported name secondary sort for tied values. Category/tag/tool associations have no declared Python
ordering, so their complete values are compared without relying on incidental SQL order. Redirect paths are compared
after removing each isolated server's origin. Tests also follow pagination links, check authentication and group
access, exercise detail reads, verify unmigrated gateway boundaries and stop Python to prove Java independence.
Spring Security retains its default no-cache response header where Python does not set a cache policy. The parity
harness records this additional protection explicitly; any cache policy set by Python, including detail reads,
still has to match exactly.

Search uses the unchanged text-unidecode 1.3 translation table from the Python dependency; the original Artistic
Licence is included beside the table. This is data consumed by Java, with no Python process or HTTP forwarding.
The table provenance and licence are documented in its resource directory.
