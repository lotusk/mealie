# Mealie Java backend

Spring Boot 4 / Java 21 backend that takes over Mealie's API from the Python backend route by route, behind the
gateway described in [docs/rebuild/gateway.md](../docs/rebuild/gateway.md). It listens on **:9100**
(`JAVA_API_PORT`). The authenticated `GET /api/recipes/{slug}` now runs here, accepting a recipe slug or UUID. Other application
endpoints remain on Python.

```bash
task java           # run against the same database as `task py`
task java:test      # unit + integration tests (throwaway SQLite)
task java:test:db ENGINE=sqlite|postgres   # read-only dialect checks against the real dev database
```

The build uses the Maven wrapper (`./mvnw`); the only prerequisite is a JDK 21.

## Database access

The database layer is Spring JDBC (`JdbcTemplate` / `NamedParameterJdbcTemplate`) with plain SQL, with no JPA or
Hibernate. The rules:

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

  ```java
  jdbc.query("SELECT id, admin, created_at FROM users WHERE group_id = :groupId",
          new MapSqlParameterSource("groupId", dialect.uuid(groupId)),
          (rs, i) -> new Row(dialect.getUuid(rs, "id"), dialect.getBool(rs, "admin"),
                  dialect.getTimestamp(rs, "created_at")));
  ```

  Always bind values as parameters. Never put a UUID or boolean literal into SQL text. SQLite compares datetimes as
  strings, so they must be written in exactly SQLAlchemy's format, which `dialect.timestamp()` does.
- **Enums and JSON need nothing special.** Postgres uses native enum types (e.g. `users.auth_method`). The Postgres
  connection sets `stringtype=unspecified`, so a plain `String` parameter binds to enum and JSON columns on both
  engines.
- **Prefer SQL both engines accept.** `LIMIT/OFFSET`, `ON CONFLICT … DO UPDATE` and `RETURNING` work on both. Postgres
  `LIKE` is case-sensitive and SQLite's isn't, so use `LOWER(col) LIKE LOWER(:q)`. Avoid `ILIKE`, `::casts` and
  `CAST(x AS TIMESTAMP)` (SQLite turns that into a number).

## Auth

`auth/AuthService` verifies Mealie's HS256 JWTs exactly like `get_current_user()` in
`mealie/core/dependencies/dependencies.py`:

- The token comes from `Authorization: Bearer`, with the `mealie.access_token` cookie as fallback.
- `exp`, `nbf` and a future `iat` are rejected with no leeway.
- API tokens (`long_token`) must exist in `long_live_tokens`.
- Tokens issued before `users.tokens_valid_after` are rejected.

The secret is `<DATA_DIR>/.secret` in production, and `shh-secret-test-key` when `PRODUCTION=false`, as in Python.
It's read-only from Java and reloaded if the file changes. Errors use Python's body shape, `{"detail": ...}`, with
the same status codes and headers.

To require a user in a controller, declare a parameter: `public Foo get(AuthUser user)`.

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
