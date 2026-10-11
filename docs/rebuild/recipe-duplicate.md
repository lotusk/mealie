# Recipe duplication migration

Only `POST /api/recipes/{slug}/duplicate` moves to Java. Its required JSON object accepts `name: string | null`;
an omitted, null or empty name uses the source name. Other fields are ignored. A successful request returns the full
copied recipe with status 201. Missing/other-group sources return 404, invalid credentials 401, invalid bodies 422,
and names that cannot produce a slug 400. UUID spellings accepted by Python are supported.

Duplication is group-scoped rather than limited by recipe edit permissions. A user in another household in the same
group can copy a private/locked source, and ownership changes to that user and household. Admins cannot copy another
group's recipe. The source's settings are copied; the caller's household defaults do not overwrite them.

Java writes the new recipe and every private nested row in one transaction. New ingredient reference IDs and step
IDs make private rows independent. Notes, nutrition, settings, ingredient substitutions, assets and extras are copied.
Organizers, units, foods and referenced recipes remain links to their existing group records. Existing note reference
values and step ingredient-reference values remain unchanged, matching Python's current legacy behavior. Step text
replaces the source recipe UUID with the new UUID so embedded asset links point to copied files.

Last-made resets to null. Comments, timeline entries, household last-made associations, personal ratings/favorites,
share tokens and shopping/meal-plan associations are excluded. Stored aggregate rating, date-added and created-at
are copied, while updated-at/date-updated refresh. Empty ingredient/instruction lists receive localized defaults;
missing nutrition/settings receive Python's database-model defaults. Slug collisions retry the original name and
suffixes (1) through (9), rolling back each failed attempt. The tenth collision returns 400 with no partial rows.

After the database commit, Java copies the entire recipe directory, including images, timeline images, listed and
unlisted assets and subdirectories. It follows symbolic links as Python does, copies bytes and file metadata, and
creates standard empty image/assets directories for missing source files. Individual copy failures are logged, other
files continue copying, and the committed recipe remains with the same 201 response and notification. This preserves
Python's partial-copy behavior; it does not replace the recipe with an empty creation. No files are linked to the
original. An invalid event integration ID fails with Python's plain-text 500 after the committed database/file copy.

The approved signed event-only boundary delivers `recipe_created` with operation `create` and the localized
`notifications.generic-duplicated` message to the new owning household. It uses existing Apprise subscriptions,
integration IDs and best-effort background delivery. The adapter verifies ownership, rejects unsupported message
variants and replays, and performs no recipe, nested-row or filesystem writes. Java needs the already documented
`RECIPE_EVENT_ADAPTER_KEY` and `RECIPE_EVENT_ADAPTER_URL`; no additional notification destination is required.

`dev/rebuild/recipe_duplicate_parity.py` reuses the previous migration harness's isolated lifecycle and fixture
infrastructure. It compares Python, direct Java and gateway responses, rows and notifications, verifies independent
edits/files, file-failure behavior, defaults, collisions, authentication and group/household boundaries, and checks
all four prior migrated recipe endpoints. It disables only Python's duplication POST for the native-operation proof.
Use the commands in the backend README, on both SQLite and PostgreSQL and with `--production-validation`.

All fixture databases and recipe directories are retained. No shared development database is used. The harness
records all failures and stops only its own servers. Reports retain actual headers, including Spring Security's
additional no-cache protection and Tomcat's insignificant Content-Type whitespace. Media type and charset
values compare case-insensitively after trimming separator whitespace; body formats still compare in full. Generated IDs/timestamps and incidental unordered association-row order are
normalized only for cross-fixture comparison; ownership, copied timestamps, private IDs, instruction/ingredient
order, file content, file independence and reset fields have separate assertions.
