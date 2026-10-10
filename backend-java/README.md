# Mealie Java backend

Spring Boot 4 / Java 21 backend that takes over Mealie's API from the Python backend route by route, behind the
gateway described in [docs/rebuild/gateway.md](../docs/rebuild/gateway.md). It listens on **:9100**
(`JAVA_API_PORT`). The gateway sends everything under `/api/organizers/tags`, the public app-about endpoints,
authenticated `GET /api/recipes` listing, `GET /api/recipes/{slug}` detail, exact manual `POST /api/recipes`
creation, and `PATCH /api/recipes/{slug}/last-made` to Java. Detail accepts a recipe slug or UUID. Other application
endpoints remain
on Python. `POST /api/auth/token` now uses Java for local Mealie password login. Authenticated
`GET /api/users/self` also uses Java; its sibling ratings/favorites routes and user writes remain on Python.
Authenticated `GET /api/groups/self` uses Java too; group preferences and AI-provider summaries are read from the
shared database without returning provider API keys. Other group routes remain on Python.
`GET /api/organizers/tools` is also served by Java, including pagination, search, query filters, recipe counts and
household slugs. Tool writes and tool detail/empty/merge routes remain on Python. After pulling new Java routes,
restart `task java` to register them; the gateway watches its Caddyfile automatically.

After `task java:package`, compare tools listing against Python with a fresh temporary SQLite database:
`uv run --frozen python dev/rebuild/tool_list_parity.py --report /tmp/tools-parity.json`.
Add `--gateway /path/to/caddy` to also verify the gateway's GET-only routing. The script stops its temporary
servers on completion and never uses the development database.

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


## Manual recipe creation

Only exact `POST /api/recipes` moves to Java. The request remains `{"name": "My recipe"}`; additional fields are
ignored, as in Python. Java performs validation, legacy slug generation (including its 250-character truncation),
ten creation attempts for duplicate names, authenticated group/household ownership, localized ingredient/step
text, household settings, empty nutrition and the first system timeline entry. MyBatis XML writes the unchanged
Python/Alembic schema with parameters converted through `SqlDialect`. Creation and timeline commits retain the
original Python failure boundary. A successful response is HTTP 201 with a JSON string containing the slug.

Python still owns recipe imports, duplicate/edit/delete routes, timeline APIs and notification configuration.
The approved event-only adapter preserves the existing Apprise delivery destinations and subscriber rules.
Java sends signed metadata about an already committed recipe to `/internal/recipe-created`; the adapter verifies
ownership, reads the recipe and dispatches `recipe_created` using the original EventBus/Apprise implementation.
It never creates or updates recipe/timeline rows. The gateway rejects `/internal/*`, the adapter is absent from
OpenAPI, and a dedicated HMAC key (minimum 32 UTF-8 bytes), timestamp window and process-local replay guard protect
direct service calls. Original API tokens keep their integration ID in notification metadata.

Configure both backend processes with the same dedicated key; keep it out of Git and experiment reports:

```bash
export RECIPE_EVENT_ADAPTER_KEY=$(uv run --frozen python -c 'import secrets; print(secrets.token_hex(32))')
export RECIPE_EVENT_ADAPTER_URL=http://localhost:9000/internal/recipe-created
task stack:sqlite
```

The key can also be supplied in the existing ignored repository `.env`; process environment wins. Java loads it
at startup, so restart Java after changing it. Without a configured key, Java creation returns 503 before writing
anything; other migrated endpoints continue working. Notification delivery runs after committed creation,
matching FastAPI background delivery: delivery failures cannot undo creation and are explicitly logged as
`RECIPE_CREATED_EVENT_FAILED`. Successful native creation logs `JAVA_RECIPE_CREATED`; the adapter logs
`RECIPE_CREATED_EVENT_ADAPTER`. There is no durable notification queue or automatic retry, matching the original
best-effort behavior; replay protection is local to one Python worker, not a cross-worker durable deduplication store.

Reproducible tests use new databases only and preserve every fixture. They check raw POST responses, validation,
authentication, ownership, defaults, actual Apprise HTTP delivery, concurrent names, signed adapter access,
existing Java endpoints and gateway boundaries. Python's POST creation is then deliberately blocked while its
event adapter stays available: Java creation, timeline insertion and real notifications must still succeed.

```bash
# Build to an independent directory if a preview is running from the normal target jar.
task java:package
uv run --frozen python dev/rebuild/recipe_create_parity.py --engine sqlite \
  --jar backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar --gateway /path/to/caddy --report /tmp/create-sqlite.json
uv run --frozen python dev/rebuild/recipe_create_parity.py --engine postgres --port-offset 200 \
  --jar backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar --gateway /path/to/caddy --report /tmp/create-postgres.json
```

The PostgreSQL harness creates a fresh `compx574_recipe_create_003_*` database on the existing isolated server
(localhost:55432, disposable mealie/mealie credentials); it refuses a nonempty database. SQLite uses a retained
fresh directory. The script stops only its own temporary servers and never deletes databases or fixtures.
`--production-validation` verifies the production 422 envelope; `--keep-serving` leaves the isolated stack available
for UI review. Default isolated ports are 9400/9410/9480/9490 and can all be moved with `--port-offset`.

## Recipe last-made updates

`PATCH /api/recipes/{slug}/last-made` accepts a slug or recipe ID and a required `timestamp`. Java validates the
body, authenticates the caller, writes the shared tables through MyBatis, and returns the full recipe DTO. Dates
without a timezone use UTC; ISO offsets and numeric second/millisecond timestamps follow the current Python
validator, including microsecond precision and its historical negative-float behavior. Both development and
production validation envelopes are preserved.

The caller can update a locked recipe or a recipe in another household of the same group. Other-group recipes
remain inaccessible, including for administrators. Only the caller's `households_to_recipes` record changes.
The recipe's overall `last_made` only advances; lowering a household date does not lower the recipe date.
An identical household timestamp preserves that association's ID and timestamps. The update does not create a
timeline event, change `date_updated`, or edit any recipe content. PostgreSQL retains Python's `500 DataError`
for the unsupported raw `urn:uuid:` spelling; canonical and compact recipe IDs work on both engines.

After the database transaction commits, Java sends signed event metadata to the approved event-only adapter's
`/internal/recipe-updated` route. The adapter verifies the actor's group and recipe ownership and delivers the
existing `recipe_updated` event to the **recipe owner's household**, including when another household made the
update. All existing Apprise destinations, subscription options, integration metadata and translations remain
in the original event bus. The adapter performs no recipe, household-association or timeline writes. Creation
continues to use `/internal/recipe-created` unchanged.

The same `RECIPE_EVENT_ADAPTER_KEY` is required in both backends. Java derives the update URL as the sibling of
`RECIPE_EVENT_ADAPTER_URL`; set `RECIPE_UPDATE_EVENT_ADAPTER_URL` to override it. The gateway blocks `/internal/*`.
Notification delivery is asynchronous and best effort, matching Python background delivery. Inspect
`JAVA_RECIPE_LAST_MADE_UPDATED`, `RECIPE_UPDATED_EVENT_ACCEPTED` and `RECIPE_UPDATED_EVENT_ADAPTER` in the logs.

After building, run the isolated HTTP comparisons with an explicit artifact (the harness refuses an occupied
port or a nonempty fixture database):

```bash
uv run --frozen python dev/rebuild/recipe_last_made_parity.py --engine sqlite --jar /path/to/backend.jar --gateway /path/to/caddy --report /tmp/last-made-sqlite.json
uv run --frozen python dev/rebuild/recipe_last_made_parity.py --engine postgres --port-offset 200 --jar /path/to/backend.jar --gateway /path/to/caddy --report /tmp/last-made-postgres.json
# Add --production-validation for production 422 envelopes, or --keep-serving for isolated UI review.
```

The harness creates and retains disposable databases (`/tmp/compx574-recipe-last-made-004-*` or a unique
`compx574_recipe_last_made_004_*` PostgreSQL database on localhost:55432). It uses ports 10400/10410/10480/10490
plus the supplied offset and stops only its own services, unless retained for review. It never deletes fixtures,
uses the normal development database, or runs destructive cleanup. Reports contain actual responses, stored
state, notifications, failures, artifact hashes and service commands. Native updates and notifications must still
work directly and through the gateway after only Python's matching PATCH is disabled. Creation, detail, listing,
pagination, last-made filters and unmigrated route boundaries are checked in the same fixture stack.
