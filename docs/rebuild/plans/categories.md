# Plan: migrate `/api/organizers/categories` to the Java backend

Status: ready to implement. This file is self-contained. Read it fully before writing code.

## 1. Goal and scope

Move all eight endpoints under `/api/organizers/categories` from the Python backend (`mealie/`) to the Java
backend (`backend-java/`), then point the gateway at Java. Clients (the Nuxt frontend and API users) must not be
able to tell which backend answered: same paths, status codes, JSON bodies, error bodies, side effects and
notifications. It must work on both **SQLite** and **PostgreSQL**.

| Method | Path                                         | Python handler (`mealie/routes/organizers/controller_categories.py`) | Decorator line |
|--------|----------------------------------------------|---------------------------|----|
| GET    | `/api/organizers/categories`                 | `get_all`                 | 39 |
| POST   | `/api/organizers/categories`                 | `create_one`              | 51 |
| GET    | `/api/organizers/categories/empty`           | `get_all_empty`           | 72 |
| POST   | `/api/organizers/categories/merge`           | `merge_categories`        | 77 |
| GET    | `/api/organizers/categories/{item_id}`       | `get_one`                 | 92 |
| PUT    | `/api/organizers/categories/{item_id}`       | `update_one`              | 99 |
| DELETE | `/api/organizers/categories/{item_id}`       | `delete_one`              | 121 |
| GET    | `/api/organizers/categories/slug/{category_slug}` | `get_one_by_slug`    | 141 |

The decorator line numbers go into `@PythonEndpoint(line = ...)`. Python's dev-mode 422 message includes them
(confirmed: a bad `item_id` on GET reports `line 92, in get_one`).

**Out of scope. Leave these alone:**

- The public explore routes `/api/explore/groups/{slug}/organizers/categories...` (a different path; they stay on
  Python).
- Recipe writes that attach categories to recipes (still Python).
- Any schema change. Python/Alembic owns the schema, and Java never runs DDL.
- Refactoring tags/tools to share code with categories (see §4.1).

## 2. Background you need

Read these first, in this order:

1. `docs/rebuild/gateway.md`: how the gateway routes, the diff test, and the "Moving a route to Java" checklist.
2. `backend-java/README.md`: sections "Database access", "Auth", "Matching Python exactly" and "Events". The rules
   there are mandatory. In particular: every UUID, boolean and timestamp parameter goes through `db/SqlDialect`;
   always use bound parameters; don't use ILIKE, `::` casts or `CAST(... AS TIMESTAMP)`.
3. **The tags migration is your template.** Categories are almost the same feature, and tags are already in Java:
   - `backend-java/src/main/java/io/mealie/backend/organizers/TagController.java`
   - `.../organizers/TagService.java`
   - `.../organizers/TagRepository.java`
   - `.../persistence/mapper/TagMapper.java` and `src/main/resources/mapper/TagMapper.xml`
   - `.../persistence/model/TagRow.java`
   - `backend-java/src/test/java/io/mealie/backend/organizers/TagIntegrationTest.java`
   - `dev/rebuild/write_diff_tags.py`, the tag cases in `dev/rebuild/diff_cases.json`, `task diff:tags-writes`
     in `Taskfile.yml`
   - `mealie/routes/internal/controller_events.py` (the event bridge's Python side)

   Python's tag and category controllers look alike, but **they differ in error handling**. Categories go through
   the `HttpRepo` mixin (`mealie/routes/_base/mixins.py`) for get/update/delete, and tags don't. Do not copy tag
   behaviour blindly. §3 is the contract.

Python sources for categories:

- Controller: `mealie/routes/organizers/controller_categories.py`
- Repository: `RepositoryCategories` in `mealie/repos/repository_factory.py` (count subquery, `get_empty`, `merge`),
  built on `mealie/repos/repository_generic.py`
- Model: `mealie/db/models/recipe/category.py`. Unique constraint `category_slug_group_id_key (slug, group_id)`.
  `__init__` sets `name = name.strip()` and `slug = slugify(name)`. The `@validates("name")` asserts that the
  stripped name is not `""`.
- Schemas: `mealie/schema/recipe/recipe_category.py` (`CategoryIn`, `CategoryBase`, `CategoryOut`,
  `CategoryMerge`, `RecipeCategoryResponse`), and `RecipeCategory` / `RecipeCategoryPagination` in
  `mealie/schema/recipe/recipe.py`. The controller-local `CategorySummary` (`id`, `slug`, `name`) has **no**
  camelCase alias generator.

Database tables involved: `categories (group_id, id, name, slug, created_at, update_at)` and
`recipes_to_categories (recipe_id, category_id)`. `cookbooks_to_categories`, `plan_rules_to_categories` and
`group_to_categories` also have plain foreign keys (no `ON DELETE`) to `categories.id`. Python never writes them on
category operations, so Java must not either (see §3.7).

Existing Java pieces you will reuse unchanged:

- `query/FilterEntities.CATEGORY` already exists (columns, `recipes` secondary, `group` relation), so query
  filters and `orderBy` on categories work without new descriptions.
- `query/PaginationQuery`, `PageRequest`, `Pagination`, `QueryFilterSql`, `SearchFilter`, `compat/PyRandom`
- `recipe/OrganizerSummary`: same shape as Python's `RecipeCategory` / `CategoryOut`
  (`id, groupId, name, slug, recipeCount`)
- `compat/PyStr`, `compat/PySlugify`, `compat/PyValidate`, `compat/PyRepr`
- `db/IntegrityViolation`, `web/ApiException` (`errorResponse(...)` builds Mealie's `ErrorResponse`)
- `events/EventBridge`
- `recipe/RecipeRepository.summaries(...)`: the same recipe-summary projection `GET /api/recipes` uses (needs a
  small change, §4.4)
- `auth/AuthUser` (`groupId()`, `canOrganize()`, `integrationId()`)

## 3. Behaviour contract (observed from Python)

The responses below were captured from the real Python backend (dev mode, SQLite, `TestClient`). Key order in
JSON doesn't matter (the diff tools ignore it). Values in `<...>` vary.

**Shared rules.**

- Every handler requires a user (401 with Python's body and `WWW-Authenticate` challenge, as `AuthUser` already
  produces). Use `TagController`'s order exactly: parse the body first (`PyRequestBody` *before* `AuthUser`), then
  resolve the user, then validate path/query/body fields together into one 422.
- Everything is scoped to `user.groupId()`, admins included. A category of another group behaves exactly like a
  missing one.
- "Can organize" check (`self.checks.can_organize()` → `403 {"detail": "Forbidden"}`) applies to create, merge,
  update and delete only. It runs **after** validation and before any lookup. Reads don't check it.
- `HttpRepo` 404 body for lookups: `{"detail": {"message": "Not found.", "error": true, "exception": null}}`.
- `HttpRepo.handle_exception` (create/update/delete write failures):
  - `NoResultFound` → **404** `{"detail": {"message": "An unexpected error occurred.", "error": true, "exception": "No row was found when one was required"}}`
  - `IntegrityError` → **409**. The message is `"This item already exists."` for a unique violation, otherwise
    `"An unexpected error occurred."`. `exception` is `str(IntegrityError)` (see §3.9).
  - anything else (incl. the empty-name `AssertionError`) → **400** `{"detail": {"message": "An unexpected error occurred.", "error": true, "exception": "<str(ex)>"}}`.
    For `AssertionError`, `str(ex)` is `""`.
- Trailing slash: `GET /api/organizers/categories/slug/x/` → `307` to the slash-less URL. The existing
  `web/TrailingSlashRedirect` does this automatically for any mapped Java route. Verify it with a test.

### 3.1 `GET /api/organizers/categories` (list)

Same as `GET /api/organizers/tags` with `tags` → `categories`. Uses `RepositoryGeneric.page_all` with
`override=RecipeCategory` and `search`.

- Query params: `page`, `perPage`, `orderBy`, `orderByNullPosition`, `orderDirection` (default `desc`),
  `queryFilter`, `paginationSeed`, `search`. Parse them with `PaginationQuery.parse(request, errors)` and
  `PaginationQuery.last(request, "search")`, exactly as `TagController.getAll`.
- Default order is `created_at` (desc) when neither `orderBy` nor `search` is given. Search ranks first.
- Search column: `categories.name` (`RecipeTag._searchable_properties = ["name"]`, inherited).
- Each item has a `recipeCount`: the correlated subquery
  `(SELECT count(recipes_to_categories.recipe_id) FROM recipes_to_categories WHERE recipes_to_categories.category_id = categories.id)`.
- Pagination links are relative to `router.url_path_for("get_all")` = **`/categories`** (cf. tags' `LIST_ROUTE = "/tags"`).

Observed:
```json
200 {"page": 1, "per_page": 2, "total": 2, "total_pages": 1,
     "items": [{"id": "<b>", "groupId": "<g>", "name": "Probe B", "slug": "probe-b", "recipeCount": 0},
               {"id": "<a>", "groupId": "<g>", "name": "Probe Café &amp; Co!", "slug": "probe-cafe-co", "recipeCount": 0}],
     "next": null, "previous": null}
```

### 3.2 `POST /api/organizers/categories` (create, 201)

Body `CategoryIn {name: str}`. Validate with `errors.bodyField(errors.bodyObject(body), "name", "name", PyValidate::str)`.
Then: can-organize check, `name = PyStr.strip(raw)`. If `name` is empty → 400 (AssertionError, see shared rules).
Otherwise set `slug = PySlugify.slugify(name)` and INSERT. On a DB error, map it per `handle_exception`.

Success: **201** with `CategoryOut` (camelCase, includes `recipeCount`, always 0 for a new row):
```json
201 {"name": "Probe Café &amp; Co!", "id": "<uuid>", "groupId": "<g>", "slug": "probe-cafe-co", "recipeCount": 0}
```
(The name was sent as `"  Probe Café &amp; Co!  "`. It's stripped, and HTML entities are *not* decoded in the name.)

Duplicate slug in the same group (SQLite):
```json
409 {"detail": {"message": "This item already exists.", "error": true,
     "exception": "(sqlite3.IntegrityError) UNIQUE constraint failed: categories.slug, categories.group_id\n[SQL: INSERT INTO categories (group_id, id, name, slug, created_at, update_at) VALUES (?, ?, ?, ?, ?, ?)]\n[parameters: ('<group hex>', '<new id hex>', 'Probe Café &amp; Co!', 'probe-cafe-co', '2026-10-10 20:27:30.695202', '2026-10-10 20:27:30.695203')]\n(Background on this error at: https://sqlalche.me/e/20/gkpj)"}}
```
Note the column order **`group_id, id, ...`**. It differs from tags (`id, group_id, ...`). Python calls
`get_utc_now()` separately for `created_at` and `update_at`, so they can differ by a microsecond. The write-diff
script normalises timestamps and ids, so using one `now` for both is fine.
Blank name (`"  "`): `400 {"detail": {"message": "An unexpected error occurred.", "error": true, "exception": ""}}`.

After commit, publish event `category_created` (§3.10).

### 3.3 `GET /api/organizers/categories/empty`

`response_model=list[CategoryBase]`, so it is **camelCase** with exactly the keys `name, id, groupId, slug`.
**This differs from tags**, where `/empty` returns raw snake_case ORM attributes. No `recipeCount`,
`createdAt` or `updatedAt`.
```json
200 [{"name": "Probe Café &amp; Co!", "id": "<a>", "groupId": "<g>", "slug": "probe-cafe-co"},
     {"name": "Probe B", "id": "<b>", "groupId": "<g>", "slug": "probe-b"}]
```
There is no ORDER BY, so row order is whatever the database returns. Copy SQLAlchemy's statement exactly,
including its select list. On SQLite the select list decides the planner's automatic index and therefore the
order (see README "SQL that relies on row order"). Captured from SQLAlchemy:
```sql
SELECT ? AS anon_1, categories.group_id, categories.id, categories.name, categories.slug, categories.created_at, categories.update_at
FROM categories
WHERE NOT (EXISTS (SELECT 1 FROM recipes, recipes_to_categories
                   WHERE categories.id = recipes_to_categories.category_id AND recipes.id = recipes_to_categories.recipe_id))
  AND categories.group_id = ?
```
(`?` for `anon_1` is the literal `0`. TagMapper.xml writes `0 AS recipe_count` in that position. Do the same.)

### 3.4 `POST /api/organizers/categories/merge`

Body `CategoryMerge {fromId: UUID4, toId: UUID4}`. Validate both like `TagController.merge`. Then:

1. Can-organize check.
2. `fromId == toId` → `400 {"detail": "from_id and to_id must be different"}`
3. `fromId` not in the user's group → `404 {"detail": "from_id category not found"}`
4. `toId` not in the user's group → `404 {"detail": "to_id category not found"}`
5. In one transaction: move links that `to` doesn't already have, delete the remaining `from` links, delete the
   `from` category (same SQL as `TagMapper.xml` `moveRecipeLinks` / `deleteAllRecipeLinks` / `deleteRecipeLinks` /
   `delete`, with `recipes_to_categories.category_id`).
6. Return `CategoryOut` of `to`, re-read with its new `recipeCount`. **No event** is published. Any failure after
   the checks is unhandled → plain-text 500.

Observed: `200 {"name": "Probe B", "id": "<b>", "groupId": "<g>", "slug": "probe-b", "recipeCount": 1}`

Bad body (`{"fromId": "bad"}`) → one 422 with two errors: `('body','fromId')` `uuid_parsing` and
`('body','toId')` `missing`, reported at `line 77, in merge_categories   POST /api/organizers/categories/merge`.

### 3.5 `GET /api/organizers/categories/{item_id}`

`item_id: UUID4` (path, 422 on bad input). `HttpRepo.get_one` → 404 "Not found." when missing.
Response is the controller-local **`CategorySummary`: exactly `id`, `slug`, `name`, with no `groupId` and no
`recipes`** (unlike tags, which return `RecipeTagResponse`).
```json
200 {"id": "<a>", "slug": "probe-cafe-co", "name": "Probe Café &amp; Co!"}
404 {"detail": {"message": "Not found.", "error": true, "exception": null}}
```
Note that `GET /api/organizers/categories/merge` hits this route and gives a 422 (`merge` is not a UUID). Spring's
`/{item_id}` mapping gives the same result. Keep it that way.

### 3.6 `PUT /api/organizers/categories/{item_id}`

Body `CategoryIn`. Order: validate path + body (422), can-organize (403), then `HttpRepo.update_one`:

1. Look the category up in the user's group. If it's missing → **404 "Not found."** (`exception: null`). (Tags
   return a 500 here. Categories don't.)
2. In a transaction, apply `Category.__init__`: `name = strip(raw)`, `slug = slugify(name)`, `group_id = user's group`.
   - Empty name after strip → **400** `exception: ""`.
   - SQLAlchemy only writes **changed** columns. If name and slug are unchanged, there is **no UPDATE** and
     `update_at` keeps its value. Otherwise it runs `UPDATE categories SET <changed of name, slug>, update_at=? WHERE categories.id = ?`.
     `group_id` never changes, because the row is already in the user's group.
   - A slug clash → **409** "This item already exists." Observed (SQLite, both name and slug changed):
     ```
     (sqlite3.IntegrityError) UNIQUE constraint failed: categories.slug, categories.group_id
     [SQL: UPDATE categories SET name=?, slug=?, update_at=? WHERE categories.id = ?]
     [parameters: ('Probe B', 'probe-b', '2026-10-10 20:27:30.780167', '<id hex>')]
     (Background on this error at: https://sqlalche.me/e/20/gkpj)
     ```
     To build this text, the statement must list only the columns that actually changed, in the order
     `name, slug, update_at`.
3. Response: `CategorySummary` `{"id", "slug", "name"}` with the new values. Observed:
   `200 {"id": "<a>", "slug": "probe-renamed", "name": "Probe Renamed"}` (sent `" Probe Renamed "`).
4. After commit, publish `category_updated` (§3.10), including when nothing changed.

### 3.7 `DELETE /api/organizers/categories/{item_id}`

Path validation (422), can-organize (403), then `HttpRepo.delete_one` → `repo.delete`:

1. `_query_one` in the user's group. If it's missing → **404** with
   `{"message": "An unexpected error occurred.", "error": true, "exception": "No row was found when one was required"}`.
   (Tags return a bare 400 here. Categories don't.)
2. In one transaction, do what the ORM does: delete `recipes_to_categories` rows for this category **whose recipe
   exists** (`... AND recipe_id IN (SELECT recipes.id FROM recipes)`, as in `TagMapper.xml` `deleteRecipeLinks`),
   then `DELETE FROM categories WHERE categories.id = ?`. Do **not** touch `cookbooks_to_categories`,
   `plan_rules_to_categories` or `group_to_categories`.
3. A DB error maps through `handle_exception`. Example: on PostgreSQL, a leftover row in one of those legacy
   tables, or an orphan link row, makes the `categories` DELETE fail with a FK violation. That gives **409**
   "An unexpected error occurred." with `str(IntegrityError)`. SQLite doesn't enforce FKs (neither Python nor Java
   enables `PRAGMA foreign_keys`), so the same delete succeeds there. Mirror the SQL and this falls out by itself.
   (Observed: a cookbook created through today's API uses `queryFilterString`, not `cookbooks_to_categories`, and
   deleting its category returned `200 null`.)
4. Response: `200` with body `null` (JSON). Return it like `TagController.delete` does.
5. After commit, publish `category_deleted` (§3.10).

### 3.8 `GET /api/organizers/categories/slug/{category_slug}`

No path validation (plain `str`). Steps:

1. `HttpRepo.get_one(slug, "slug")` in the user's group (exact, case-sensitive `=`). If it's missing → **404
   "Not found."** (`exception: null`). (Tags give a 500 here.)
2. Recipes: Python runs the **recipe listing** query
   (`RepositoryRecipes.page_all(PaginationQuery(per_page=-1, query_filter='recipe_category.id IN ["<id>"]'))`,
   group-scoped, `household_id=None`):
   - recipes with `group_id = user's group`,
   - whose owner has a household (`EXISTS (SELECT 1 FROM users owner WHERE owner.id = r.user_id AND owner.household_id IS NOT NULL)`,
     as in `RecipeListRepository.select`),
   - linked to this category in `recipes_to_categories`,
   - `ORDER BY r.created_at DESC`, no limit.
3. The response is `RecipeCategoryResponse.model_construct(id, slug, name, recipes)`, returned **without** a
   response model:
   - Keys: `name`, `id`, `groupId`, `slug`, `recipes`. **`groupId` is always `null`** (not passed to
     `model_construct`).
   - Each recipe is a `RecipeSummary` identical to an item of `GET /api/recipes`. The exception is the timestamps
     `dateUpdated`, `createdAt`, `updatedAt` and `lastMade`: they use the **`Z` suffix**
     (`"2026-10-10T20:27:58.324438Z"`), whereas `GET /api/recipes` items use `+00:00`. This was verified by
     comparing the two responses for the same recipe: those were the only differing keys.
     Nested `recipeCategory`/`tags` entries have `recipeCount: 0`. `tools` entries include `householdsWithTool`.

Observed (abridged):
```json
200 {"name": "Probe B", "id": "<b>", "groupId": null, "slug": "probe-b",
     "recipes": [{"id": "<r>", "userId": "<u>", "householdId": "<h>", "groupId": "<g>", "name": "Probe recipe",
                  "slug": "probe-recipe", "image": null, "recipeServings": 0.0, "recipeYieldQuantity": 0.0,
                  "recipeYield": null, "totalTime": null, "prepTime": null, "cookTime": null, "performTime": null,
                  "totalTimeSeconds": null, "prepTimeSeconds": null, "performTimeSeconds": null, "description": "",
                  "recipeCategory": [{"id": "<b>", "groupId": "<g>", "name": "Probe B", "slug": "probe-b", "recipeCount": 0}],
                  "tags": [], "tools": [], "rating": null, "orgURL": null, "dateAdded": "2026-10-10",
                  "dateUpdated": "2026-10-10T20:27:58.324438Z", "createdAt": "2026-10-10T20:27:30.885862Z",
                  "updatedAt": "2026-10-10T20:27:58.326519Z", "lastMade": null}]}
404 {"detail": {"message": "Not found.", "error": true, "exception": null}}
```

### 3.9 Integrity-error text for PostgreSQL

`TagService.insertStatement()/insertParameters()` show how the psycopg2 form is rendered. For categories, expect
the same pattern with the categories column order:

- INSERT: `INSERT INTO categories (group_id, id, name, slug, created_at, update_at) VALUES (%(group_id)s::UUID, %(id)s::UUID, %(name)s, %(slug)s, %(created_at)s, %(update_at)s)`
  with parameters `{'group_id': '<uuid>', 'id': '<uuid>', 'name': ..., 'slug': ..., 'created_at': datetime.datetime(...), 'update_at': datetime.datetime(...)}`
- UPDATE: `UPDATE categories SET name=%(name)s, slug=%(slug)s, update_at=%(update_at)s WHERE categories.id = %(categories_id)s::UUID`
  with `{'name': ..., 'slug': ..., 'update_at': datetime.datetime(...), 'categories_id': '<uuid>'}`

**These PostgreSQL strings are inferred, not captured.** Confirm them against Python on a disposable Postgres
database (step 7 in §6) and fix the Java strings to match. The SQLite strings in §3.2/§3.6 were captured. The
write-diff script replaces UUIDs, timestamps and the run suffix before comparing, so only the statement shape and
parameter layout must match.

### 3.10 Events

Python publishes after a successful create, update or delete. Mirror `TagService.publish`:

| Operation | `eventType`        | `documentData`                                                    | message key                              | url |
|-----------|--------------------|-------------------------------------------------------------------|------------------------------------------|-----|
| create    | `category_created` | `{"documentType": "category", "operation": "create", "categoryId": id}` | `notifications.generic-created-with-url` | type `category`, slug |
| update    | `category_updated` | `... "operation": "update" ...`                                   | `notifications.generic-updated-with-url` | type `category`, slug |
| delete    | `category_deleted` | `... "operation": "delete" ...`                                   | `notifications.generic-deleted`          | none |

`params = {"name": <category name>}`, `groupId = category.groupId`, `householdId = null`,
`integrationId = user.integrationId()`, and forward `Accept-Language`. Merge publishes nothing.

Python's `/api/internal/events` currently accepts only document type `tag`, so it must be extended (§4.6).
Otherwise Python answers 400 and Java only logs the failure. The response would be unaffected, but notifications
would silently stop.

## 4. Implementation

Follow the package layout and style of the tag code. Keep comments at the same density, and reference the Python
source each piece mirrors.

### 4.1 Approach

Create category-specific copies of the tag classes (`CategoryController`, `CategoryService`,
`CategoryRepository`, `CategoryMapper` + XML, `CategoryRow`). This is how tools were done (`ToolRepository`
copies `TagRepository`'s paging), and it keeps each migration independently reviewable and revertible. Don't
extract a shared organizer base class in this change.

### 4.2 Persistence

**`persistence/model/CategoryRow.java`**: a record like `TagRow` (`id, groupId, name, slug, createdAt, updateAt, recipeCount`).

**`persistence/mapper/CategoryMapper.java`** + **`src/main/resources/mapper/CategoryMapper.xml`**: copy
`TagMapper` and replace `tags`→`categories`, `recipes_to_tags.tag_id`→`recipes_to_categories.category_id`.
Methods: `count`, `page`, `ids`, `findById`, `findBySlug`, `findEmpty`, `insert`, `update`, `deleteRecipeLinks`,
`delete`, `moveRecipeLinks`, `deleteAllRecipeLinks`. Then adjust:

- `categoryColumns` select list: `(SELECT count(recipes_to_categories.recipe_id) FROM recipes_to_categories WHERE recipes_to_categories.category_id = categories.id) AS recipe_count, categories.group_id, categories.id, categories.name, categories.slug, categories.created_at, categories.update_at`
- `findEmpty`: the exact statement in §3.3 (with `0 AS recipe_count` in place of the bound `anon_1`).
- `insert`: column order `(group_id, id, name, slug, created_at, update_at)`.
- `update`: must write only changed columns (§3.6). Use MyBatis `<set>` with `<if test="name != null">name = #{name},</if>`,
  `<if test="slug != null">slug = #{slug},</if>` and an always-present `update_at = #{updateAt}`. The repository
  passes `null` for unchanged fields.
- No `recipeRows` method. The slug endpoint uses the recipe listing projection instead (§4.4).
- Add **`recipeIdsForCategory(groupId, categoryId)`** for §3.8 step 2:
  ```sql
  SELECT r.id FROM recipes r
  WHERE r.group_id = #{groupId}
    AND EXISTS (SELECT 1 FROM users owner WHERE owner.id = r.user_id AND owner.household_id IS NOT NULL)
    AND EXISTS (SELECT 1 FROM recipes_to_categories l WHERE l.recipe_id = r.id AND l.category_id = #{categoryId})
  ORDER BY r.created_at DESC
  ```
  Use the `idRow` result map (`IdRow`), like `ids`.

All UUID/timestamp parameters go through `dialect.uuid(...)` / `dialect.timestamp(...)` in the repository. Never
put them in SQL text.

### 4.3 `organizers/CategoryRepository.java`

Copy `TagRepository`, then change:

- `SEARCHABLE_COLUMNS = List.of("categories.name")`. Use `FilterEntities.CATEGORY` in `page(...)` and
  `orderBy(...)`. Use `CASE categories.id` in `randomOrder`. The `where` starts with `categories.group_id = `.
- `insert(groupId, name, slug)` as in tags (returns a row with `recipeCount 0`).
- `update(id, String nameOrNull, String slugOrNull)`: bind `dialect.timestamp(now())` for `update_at`.
- `delete(id)`: `deleteRecipeLinks` then `delete`.
- `merge(fromId, toId)`: `moveRecipeLinks`, `deleteAllRecipeLinks`, `delete(fromId)`.
- `recipeIdsForCategory(groupId, categoryId)` → `List<UUID>`.

### 4.4 Recipe summaries with `Z` timestamps

`RecipeRepository.summaries(ids, groupId)` (in `recipe/RecipeRepository.java`) rewrites `...Z` into `...+00:00`
for `dateUpdated, createdAt, updatedAt, lastMade`, because `GET /api/recipes` needs that. The slug endpoint needs
the `Z` form (§3.8). Split it without changing current behaviour:

- Move the body into a private `summaries(List<UUID> ids, UUID groupId, boolean pydanticOffset)` that does the
  `Z → +00:00` rewrite only when `pydanticOffset` is true.
- Keep `public summaries(ids, groupId)` delegating with `true`. Its existing callers stay unchanged.
- Add `public List<Map<String, Object>> jsonableSummaries(List<UUID> ids, UUID groupId)` delegating with `false`.
  Add a one-line comment: it's for responses FastAPI serialises with `jsonable_encoder` (no response model).

Run `RecipeListTest` afterwards to prove listing output is unchanged.

### 4.5 `organizers/CategoryService.java` and `organizers/CategoryController.java`

Service (`@Service`, constructor-injected `CategoryRepository`, `RecipeRepository`, `EventBridge`,
`TransactionTemplate`, `SqlDialect`):

- `static final String LIST_ROUTE = "/categories";`
- Records:
  - `CategorySummary(UUID id, String slug, String name)` for GET by id and PUT.
  - `CategoryBase(String name, UUID id, UUID groupId, String slug)` for `/empty`.
  - `CategoryResponse(String name, UUID id, UUID groupId, String slug, List<Map<String, Object>> recipes)` for slug.
    `groupId` is always `null`. Make sure Jackson writes `"groupId": null` and does not omit it. Check the global
    Jackson inclusion setting in `application.yml` / existing records, since tags' `TagResponse` already relies on
    the default.
  - Create and merge return `OrganizerSummary`.
- `list` / `empty` / `get` / `getBySlug` / `create` / `update` / `delete` / `merge` implement §3 exactly. Specifics:
  - `create`: same structure as `TagService.create` + `createError`, with the categories INSERT text (§3.2/§3.9).
  - `update`: requireOrganizer → `findById` (404 `errorResponse(NOT_FOUND, "Not found.", null)`) → in a
    transaction: strip/validate (empty → `errorResponse(BAD_REQUEST, "An unexpected error occurred.", "")`), slugify,
    compute which of name/slug changed, call `repository.update` only if any changed. Catch DB errors and map them
    via a `handle_exception` helper (409 unique / 409 other / 400). Use the UPDATE text from §3.6/§3.9, built from
    the changed columns. Then publish and return `CategorySummary`.
  - `delete`: requireOrganizer → in a transaction: `findById`, then if missing throw the 404 "No row was found when
    one was required" `errorResponse`. Otherwise delete. Map DB errors via `handle_exception` (the statement for the
    409 text is the categories DELETE; confirm its exact text on Postgres). Publish and return.
  - `getBySlug`: find → 404 → `repository.recipeIdsForCategory` → `recipeRepository.jsonableSummaries(ids, groupId)`.
  - Put the shared `handle_exception` mapping in one private method used by create/update/delete. Don't
    duplicate it three times.
- `publish(...)`: like `TagService.publish` with `documentType "category"`, key `categoryId`, url type `category`.

Controller: copy `TagController`, with `@RequestMapping("/api/organizers/categories")`, `SOURCE =
"mealie/routes/organizers/controller_categories.py"`, the function names and line numbers from §1, and the path
variable names `item_id` and `category_slug`. The return types follow the service records above. Delete returns
`ResponseEntity.ok().contentType(APPLICATION_JSON).body("null")`.

### 4.6 Python: allow category events through the bridge

In `mealie/routes/internal/controller_events.py`, import `EventCategoryData` and extend:
```python
DOCUMENT_TYPES = {"tag": EventTagData, "category": EventCategoryData}
URL_BUILDERS = {"tag": urls.tag_url, "category": urls.category_url}
```
(`urls.category_url` exists in `mealie/services/urls/url_constructors.py` and returns `<base>/recipes/categories/<slug>`.)
Add a test to `tests/integration_tests/test_internal_events.py`, modelled on
`test_signed_event_is_dispatched_like_a_python_event`. It should send a `category_created` event and assert
`EventCategoryData` and a message ending in `/recipes/categories/<slug>`. This is the only Python change.

### 4.7 Gateway

Add to `gateway/Caddyfile`, next to the tags line:
```caddyfile
reverse_proxy /api/organizers/categories* {$JAVA_UPSTREAM}
```
All methods move, as with tags. Do this **last**, only after §6 passes on both engines. Rollback = delete the line.

### 4.8 Docs and tasks

- `docs/rebuild/gateway.md`: mention categories alongside tags in the intro, and add a write-diff note next to the
  tags one ("Tags have one ...").
- `backend-java/README.md`: add `/api/organizers/categories` to the list of Java-served routes in the first
  paragraph.
- `Taskfile.yml`: add `diff:categories-writes` next to `diff:tags-writes`, running
  `uv run python dev/rebuild/write_diff_categories.py {{ .CLI_ARGS }}`.

## 5. Tests to add

### 5.1 Java: `backend-java/src/test/java/io/mealie/backend/organizers/CategoryIntegrationTest.java`

Model it on `TagIntegrationTest`: SQLite temp DB built in `@BeforeAll`, JWTs signed with `shh-secret-test-key`,
and `MEALIE_PYTHON_URL` pointing at a dead port. The schema needs `users`, `categories` (with
`CONSTRAINT category_slug_group_id_key UNIQUE (slug, group_id)`), `recipes`, `recipes_to_categories`, `tags`,
`recipes_to_tags`, `tools`, `recipes_to_tools`, `households`, `households_to_tools`, since summaries read all of
these. Cover at least:

- list: group scoping, the pagination shape, `recipeCount`, a 422 on `page=x`
- `/empty`: camelCase keys, no `recipeCount`
- GET by id: only `id/slug/name`. Another group's id gives 404 `"Not found."`
- slug: `groupId` null, recipe present, a timestamp ending in `Z`, unknown slug gives 404 `"Not found."`
- create: strip/slugify, 201, duplicate gives 409 `"This item already exists."`, blank gives 400 with `exception ""`
- update: rename, unchanged name (`updatedAt`/`update_at` unchanged in the DB), unknown gives 404 `"Not found."`,
  slug clash gives 409, blank gives 400
- delete: 200 `null`, the link rows are removed, a second delete gives 404 with
  `"No row was found when one was required"`
- merge: same id gives 400, unknown from/to give 404 with the right messages, a successful merge moves links
  without duplicating them and returns `recipeCount`
- permissions/ordering: a viewer gets 403 on writes, no token gives 401, a bad JSON body with no token gives 422
  (body before auth), the trailing slash gives 307

Also run the full suite (`task java:test`) to be sure `RecipeListTest` still passes after §4.4.

### 5.2 Read-only diff cases: `dev/rebuild/diff_cases.json`

Add category equivalents of the existing tag cases: default list, `perPage=-1` by name, pagination edge cases
(page 2, `page=-1`, `page=0`, `perPage=0`, `perPage=-2`, past end, duplicate params, int forms), multi-order with
nulls, `orderBy=updatedAt`, `orderBy=recipes.name`, `orderBy=group.name`, random with seeds and without,
search (words, quoted, punctuation only, search + order), the queryFilter cases (like/not like/in/not in/eq
case/and-or/groups/$NOW/recipes.*/group.name/recipes.user.username/recipes.householdId/recipes.tags.slug/id), the
filter error cases, `/empty`, slug (2 existing and 1 missing), and GET by id for one existing and one random UUID.
Use slugs/ids that exist in the dev database. Look first (`GET /api/organizers/categories?perPage=-1`). If the dev DB
has fewer than ~3 categories with recipes, create some through the normal UI (Python). Only read-only cases go
here.

### 5.3 Write scenario: `dev/rebuild/write_diff_categories.py`

Copy `write_diff_tags.py`, with `T = "/api/organizers/categories"`, the non-organizer user named e.g.
`categories-noorg`, and recipes linked via `recipe["recipeCategory"]` instead of `recipe["tags"]`. Keep every tag
step. Add steps for the category-specific behaviour: `get unknown` (404), `slug unknown` (404), `empty`
(after creating an unused category), `update unknown` (now 404, not 500), `delete unknown` (now 404 with the
NoResultFound body, not bare 400), `slug with recipes` with two recipes (checks `created_at DESC` order). Update
the docstring.

## 6. Verification (definition of done)

1. `task java:test`: all green, including the new test and the unchanged `RecipeListTest` / `TagIntegrationTest`.
2. `task py:test -- tests/integration_tests/test_internal_events.py`: green. Also run
   `task py:lint` and `task py:format`.
3. SQLite: `task stack:sqlite`, then `task diff -- --expect-engine sqlite` (all cases pass) and
   `task diff:categories-writes` (all steps match).
4. Postgres: `task stack:postgres`, then `task diff -- --expect-engine postgres` and `task diff:categories-writes`.
5. Restart Java after pulling new routes (`task java`). The gateway reloads its Caddyfile on its own.
6. Only now add the Caddyfile line (§4.7). Check:
   `curl -s -D - -o /dev/null -H "Authorization: Bearer $TOKEN" localhost:8080/api/organizers/categories | grep X-Mealie-Backend`
   should print `java`. `/api/organizers/tags` should still print `java`,
   `/api/explore/groups/home/organizers/categories` should print `python`, and `/api/internal/events` should still
   return 404.
7. Postgres integrity text (§3.9): against a **disposable** Postgres database (never the dev DB), trigger a
   duplicate create and a slug-clash update on Python and copy the exact `exception` strings into
   `CategoryService`. The write-diff on Postgres (step 4) also catches a mismatch.
8. UI check through the gateway (`task ui`, browser Network tab, `X-Mealie-Backend: java`):
   - Group > Data Management > Categories (`/group/data/categories`): list, create, edit, delete, delete-unused
     (uses `/empty` + delete), and merge.
   - `/g/home/recipes/categories`: list and open a category (slug endpoint) to check that its recipes show up.
   - With a notifier subscribed to category events, create a category and confirm the notification arrives (proves
     §4.6).

## 7. Pitfalls checklist

- [ ] `/empty` is camelCase `CategoryBase`, not tags' snake_case dump.
- [ ] GET by id / PUT return only `{id, slug, name}`.
- [ ] Slug response has `"groupId": null` and `Z` timestamps in recipes.
- [ ] Unknown id on GET/PUT gives 404 "Not found." Unknown on DELETE gives 404 "An unexpected error occurred." +
      "No row was found when one was required". Unknown slug gives 404 "Not found." (tags differ on all of these).
- [ ] Update writes only changed columns, and none if nothing changed.
- [ ] INSERT column order is `group_id, id, ...` in the 409 text.
- [ ] Every UUID/timestamp is bound through `SqlDialect`. No literals in SQL. No Postgres-only syntax.
- [ ] Events go out after commit, and Python's bridge accepts `category`.
- [ ] Caddyfile line added last, after both engines pass.
