"""Recipe duplicate parity: native Java, gateway and original Python on retained isolated databases."""

import argparse
import contextlib
import hashlib
import hmac
import json
import os
import signal
import socket
import subprocess
import tempfile
import threading
import time
import traceback
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import quote, unquote_plus
from uuid import UUID, uuid4

import jwt
import psycopg2
import requests
from sqlalchemy import create_engine, text

REPO = Path(os.environ.get("MEALIE_PARITY_REPO", str(Path(__file__).resolve().parents[2])))
SECRET = "shh-secret-test-key"


def main() -> None:  # noqa: C901, PLR0915 - one lifecycle owns only the temporary services it starts.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=("sqlite", "postgres"), default="sqlite")
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--gateway", type=Path, required=True)
    parser.add_argument("--port-offset", type=int, default=0)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--production-validation", action="store_true", help="Check production 422 response envelopes")
    parser.add_argument("--keep-serving", action="store_true", help="Keep this isolated stack available for UI review")
    args = parser.parse_args()

    py_port, java_port, gateway_port, sink_port = (p + args.port_offset for p in (11400, 11410, 11480, 11490))
    for port in (py_port, java_port, gateway_port, sink_port):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", port))
    assert args.jar.is_file() and args.gateway.is_file()
    data = Path(tempfile.mkdtemp(prefix="compx574-recipe-duplicate-005-"))
    adapter_key = uuid4().hex + uuid4().hex
    env = {
        **os.environ,
        "PRODUCTION": "false",
        "TESTING": "true",
        "DATA_DIR": str(data),
        "DB_ENGINE": args.engine,
        "API_PORT": str(py_port),
        "JAVA_API_PORT": str(java_port),
        "MEALIE_BASE_DIR": str(REPO),
        "ALLOW_SIGNUP": "false",
        "BASE_URL": "http://isolated-duplicate-review.invalid",
        "RECIPE_EVENT_ADAPTER_KEY": adapter_key,
        "RECIPE_EVENT_ADAPTER_URL": f"http://localhost:{py_port}/internal/recipe-created",
        "DUPLICATE_PARITY_BLOCK_FILE": str(data / "block-python-duplicate"),
    }
    if args.production_validation:
        env.update(PRODUCTION="true", TESTING="false")
        (data / ".secret").write_text(SECRET)
    env["POSTGRES_URL_OVERRIDE"] = ""
    database = str(data / "mealie.db")
    if args.engine == "postgres":
        database = "compx574_recipe_duplicate_005_" + uuid4().hex[:12]
        assert database.startswith("compx574_recipe_duplicate_005_")
        connection = psycopg2.connect(host="localhost", port=55432, user="mealie", password="mealie", dbname="postgres")
        try:
            connection.autocommit = True
            from psycopg2 import sql

            with connection.cursor() as cursor:
                cursor.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
        finally:
            connection.close()
        env.update(
            POSTGRES_SERVER="localhost",
            POSTGRES_PORT="55432",
            POSTGRES_USER="mealie",
            POSTGRES_PASSWORD="mealie",
            POSTGRES_DB=database,
        )
    db = create_engine(
        "sqlite:///" + database
        if args.engine == "sqlite"
        else "postgresql+psycopg2://mealie:mealie@localhost:55432/" + database
    )
    with db.connect() as connection:
        tables = connection.execute(
            text(
                "SELECT name FROM sqlite_master WHERE type='table'"
                if args.engine == "sqlite"
                else "SELECT tablename FROM pg_tables WHERE schemaname='public'"
            )
        ).all()
        assert not tables, "Refusing nonempty fixture database"
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {
        "started_utc": datetime.now(UTC).isoformat(),
        "engine": args.engine,
        "production_validation": args.production_validation,
        "database": database,
        "data_dir": str(data),
        "fresh_database_verified": True,
        "normal_development_database_used": False,
        "fixtures_preserved": True,
        "destructive_cleanup_performed": False,
        "cases": [],
        "notifications": [],
        "failures": [],
        "result": "in_progress",
        "java_jar": str(args.jar),
        "java_jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        "comparison_policy": (
            "Compare full responses and stored defaults. Normalize generated identifiers and timestamps only; "
            "assert ownership, UUID4, timestamp bounds and timeline correlation separately. "
            "Additional Spring Security no-cache protection and insignificant Content-Type spacing/case are recorded. "
            "Full raw headers remain in evidence. No feature is omitted."
        ),
    }
    processes: list[subprocess.Popen[bytes]] = []
    logs: list[Any] = []
    python, java, gateway = (f"http://localhost:{p}" for p in (py_port, java_port, gateway_port))

    class Sink(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
            try:
                body = json.loads(raw)
            except ValueError:
                body = raw
            report["notifications"].append({"path": self.path, "body": body})
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")

        def log_message(self, *_: object) -> None:
            pass

    sink = ThreadingHTTPServer(("localhost", sink_port), Sink)
    threading.Thread(target=sink.serve_forever, daemon=True).start()

    def start(name: str, command: list[str], service_env: dict[str, str] = env) -> subprocess.Popen[bytes]:
        log = args.report.with_suffix("." + name + ".log").open("w")
        logs.append(log)
        process = subprocess.Popen(
            command, cwd=REPO, env=service_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
        )
        processes.append(process)
        report.setdefault("service_commands", []).append({"name": name, "command": command, "pid": process.pid})
        return process

    def wait(origin: str, path: str, process: subprocess.Popen[bytes]) -> Any:
        for _ in range(200):
            assert process.poll() is None, f"Service exited: {origin}"
            with contextlib.suppress(requests.RequestException):
                response = requests.get(origin + path, timeout=1)
                if response.status_code == 200:
                    return response.json()
            time.sleep(0.2)
        raise RuntimeError("Service not ready: " + origin)

    def api(method: str, path: str, token: str | None = None, origin: str = python, **kwargs: Any) -> Any:
        response = requests.request(
            method, origin + path, headers={"Authorization": "Bearer " + token} if token else {}, timeout=30, **kwargs
        )
        assert response.status_code < 400, (method, path, response.status_code, response.text)
        return response.json() if response.content else None

    def login(email: str, password: str, origin: str = python) -> str:
        return api("POST", "/api/auth/token", origin=origin, data={"username": email, "password": password})[
            "access_token"
        ]

    def scalar(value: str) -> str | UUID:
        return UUID(value).hex if args.engine == "sqlite" else UUID(value)

    def execute(statement: str, values: dict[str, Any]) -> None:
        with db.begin() as connection:
            connection.execute(text(statement), values)

    def capture(response: requests.Response) -> dict[str, Any]:
        try:
            body = response.json()
        except ValueError:
            body = response.text
        return {
            "status": response.status_code,
            "body": body,
            "headers": {
                k.lower(): v
                for k, v in response.headers.items()
                if k.lower() in ("content-type", "www-authenticate", "cache-control", "location", "x-mealie-backend")
            },
        }

    def canonical(value: Any, context: dict[str, Any], key: str = "") -> Any:
        if isinstance(value, dict):
            return {canonical(k, context): canonical(v, context, k) for k, v in value.items()}
        if isinstance(value, list):
            items = [canonical(v, context, key) for v in value]
            return (
                sorted(items, key=lambda v: json.dumps(v, sort_keys=True, default=str))
                if key == "households_to_recipes"
                else items
            )
        if isinstance(value, (UUID, datetime)):
            value = str(value)
        if key == "id" and isinstance(value, int):
            return "<generated-id>"
        if not isinstance(value, str):
            return value
        for field in ("user_id", "group_id", "household_id", "actor_id", "actor_household_id"):
            uid = context.get(field, "00000000-0000-4000-8000-000000000000")
            value = value.replace(uid, "<" + field + ">").replace(UUID(uid).hex, "<" + field + ">")
        value = value.replace("/g/" + context["group_slug"] + "/", "/g/<group_slug>/")
        if key in ("createdAt", "updatedAt", "dateUpdated", "created_at", "update_at", "date_updated", "timestamp"):
            return "<generated-time>"
        if key == "id":
            return "<generated-id>"
        try:
            UUID(value)
            return "<generated-uuid>"
        except ValueError:
            return value

    def snapshot(recipe: dict[str, Any]) -> dict[str, Any]:
        with db.connect() as connection:
            values = {"id": scalar(recipe["id"])}
            result = {
                "recipe": dict(connection.execute(text("SELECT * FROM recipes WHERE id=:id"), values).mappings().one()),
                "related": {
                    t: [
                        dict(r)
                        for r in connection.execute(
                            text(f"SELECT * FROM {t} WHERE recipe_id=:id ORDER BY id"), values
                        ).mappings()
                    ]
                    for t in (
                        "households_to_recipes",
                        "recipe_timeline_events",
                        "recipes_ingredients",
                        "recipe_instructions",
                        "recipe_settings",
                        "recipe_nutrition",
                        "notes",
                        "recipe_assets",
                        "recipe_comments",
                        "users_to_recipes",
                        "recipe_share_tokens",
                    )
                },
            }
            result["related"]["api_extras"] = [
                dict(r)
                for r in connection.execute(
                    text("SELECT * FROM api_extras WHERE recipee_id=:id ORDER BY id"), values
                ).mappings()
            ]
            for table in ("recipe_ingredient_ref_link", "recipe_note_ref_link"):
                result["related"][table] = [
                    dict(r)
                    for r in connection.execute(
                        text(
                            f"SELECT l.* FROM {table} l JOIN recipe_instructions i ON i.id=l.instruction_id "
                            "WHERE i.recipe_id=:id ORDER BY l.id"
                        ),
                        values,
                    ).mappings()
                ]
            result["related"]["recipes_ingredients_substitutions"] = [
                dict(r)
                for r in connection.execute(
                    text(
                        "SELECT s.* FROM recipes_ingredients_substitutions s JOIN recipes_ingredients i ON "
                        "i.id=s.ingredient_id WHERE i.recipe_id=:id ORDER BY s.position"
                    ),
                    values,
                ).mappings()
            ]
            for table in ("recipes_to_tags", "recipes_to_categories", "recipes_to_tools"):
                result["related"][table] = [
                    dict(r)
                    for r in connection.execute(
                        text("SELECT * FROM " + table + " WHERE recipe_id=:id"), values
                    ).mappings()
                ]
            return result

    def fixture(context: dict[str, Any], name: str) -> dict[str, Any]:
        slug = api("POST", "/api/recipes", context["token"], json={"name": name})
        return api("GET", "/api/recipes/" + slug, context["token"])

    def delivered(context: dict[str, Any], slug: str, start_index: int) -> list[dict[str, Any]]:
        events = []
        for item in report["notifications"][start_index:]:
            metadata = json.loads(unquote_plus(str(item["body"].get("document_data", "{}"))))
            if metadata.get("recipeSlug") == slug and item["path"].startswith("/" + context["label"] + "-"):
                events.append({"path": item["path"], "body": {**item["body"], "document_data": metadata}})
        return events

    def file_state(recipe):
        directory = data / "recipes" / recipe["id"]
        return {
            str(f.relative_to(directory)): {
                "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
                "size": f.stat().st_size,
                "mode": f.stat().st_mode & 0o777,
            }
            for f in directory.rglob("*")
            if f.is_file() and not f.is_symlink()
        }

    def rich_fixture(context):
        source = fixture(context, "UI Duplicate Source")
        food = api("POST", "/api/foods", context["token"], json={"name": "Broth"})
        substitute = api("POST", "/api/foods", context["token"], json={"name": "Stock"})
        unit = api("POST", "/api/units", context["token"], json={"name": "Cup", "abbreviation": "c", "fraction": True})
        tag = api("POST", "/api/organizers/tags", context["token"], json={"name": "Weeknight"})
        category = api("POST", "/api/organizers/categories", context["token"], json={"name": "Dinner"})
        tool = api("POST", "/api/organizers/tools", context["token"], json={"name": "Pot"})
        referenced = fixture(context, "Referenced Soup")
        ingredient_ref, note_ref = str(uuid4()), str(uuid4())
        source.update(
            description="Full copied data",
            image="src1",
            recipeServings=4,
            recipeYieldQuantity=2,
            recipeYield="bowls",
            totalTime="1 hour",
            prepTime="20 min",
            cookTime="40 min",
            performTime="30 min",
            totalTimeSeconds=3600,
            prepTimeSeconds=1200,
            performTimeSeconds=1800,
            rating=4.5,
            dateAdded="2020-01-02",
            createdAt="2020-01-02T03:04:05Z",
            lastMade="2026-10-09T00:00:00Z",
            orgURL="https://example.invalid/source",
            tags=[tag],
            recipeCategory=[category],
            tools=[tool],
            notes=[{"title": "Tip", "text": "Keep this note", "referenceId": note_ref}],
            extras={"source": "fixture", "other": "nested extra"},
            nutrition={"calories": "200", "proteinContent": "20g"},
            assets=[{"name": "Guide", "icon": "mdi-file", "fileName": "guide.pdf"}],
            recipeIngredient=[
                {
                    "quantity": 1.25,
                    "unit": unit,
                    "food": food,
                    "note": "Broth note",
                    "originalText": "1 1/4 cups broth",
                    "referenceId": ingredient_ref,
                    "substitutions": [
                        {"substituteFoodId": substitute["id"], "note": "interchangeable"},
                        {"note": "water and spice"},
                    ],
                },
                {"quantity": 2, "referencedRecipe": referenced, "note": "Sub recipe", "referenceId": str(uuid4())},
            ],
            recipeInstructions=[
                {
                    "title": "First",
                    "summary": "Heat",
                    "text": "See /api/media/recipes/" + source["id"] + "/assets/guide.pdf",
                    "ingredientReferences": [{"referenceId": ingredient_ref}],
                    "noteReferences": [{"referenceId": note_ref}],
                },
                {"title": "Serve", "summary": "", "text": "Serve hot"},
            ],
        )
        source = api("PUT", "/api/recipes/" + source["slug"], context["token"], json=source)
        # Stored rating, privacy/locking and foreign associations are seeded only in the new disposable database.
        execute("UPDATE recipes SET rating=:rating WHERE id=:id", {"rating": 4.5, "id": scalar(source["id"])})
        execute(
            "UPDATE recipe_settings SET public=:flag, locked=:locked WHERE recipe_id=:id",
            {
                "flag": False if args.engine == "postgres" else 0,
                "locked": True if args.engine == "postgres" else 1,
                "id": scalar(source["id"]),
            },
        )
        execute(
            "UPDATE household_preferences SET "
            "private_household=:flag,lock_recipe_edits_from_other_households=:flag WHERE "
            "household_id=:id",
            {"flag": True if args.engine == "postgres" else 1, "id": scalar(context["household_id"])},
        )
        api("POST", "/api/comments", context["token"], json={"recipeId": source["id"], "text": "Source-only comment"})
        api(
            "PATCH",
            "/api/recipes/" + source["slug"] + "/last-made",
            context["token"],
            json={"timestamp": "2026-10-09T00:00:00Z"},
        )
        execute(
            "INSERT INTO users_to_recipes "
            "(id,user_id,recipe_id,rating,is_favorite,created_at,update_at) VALUES "
            "(:id,:user,:recipe,5,:favorite,:at,:at)",
            {
                "id": scalar(str(uuid4())),
                "user": scalar(context["user_id"]),
                "recipe": scalar(source["id"]),
                "favorite": True if args.engine == "postgres" else 1,
                "at": datetime.now(UTC).replace(tzinfo=None),
            },
        )
        execute(
            "INSERT INTO recipe_share_tokens (id,group_id,recipe_id,expires_at,created_at,update_at) "
            "VALUES (:id,:group,:recipe,:expires,:at,:at)",
            {
                "id": scalar(str(uuid4())),
                "group": scalar(context["group_id"]),
                "recipe": scalar(source["id"]),
                "expires": datetime.now(UTC).replace(tzinfo=None) + timedelta(days=1),
                "at": datetime.now(UTC).replace(tzinfo=None),
            },
        )
        source = api("GET", "/api/recipes/" + source["slug"], context["token"])
        folder = data / "recipes" / source["id"]
        (folder / "images" / "timeline").mkdir(parents=True, exist_ok=True)
        (folder / "assets" / "nested").mkdir(parents=True, exist_ok=True)
        for relative, content in [
            ("images/original.webp", b"isolated image content"),
            ("images/small.webp", b"thumbnail"),
            ("images/timeline/history.webp", b"timeline image"),
            ("assets/guide.pdf", b"isolated asset"),
            ("assets/nested/unlisted.bin", b"all files including unlisted"),
        ]:
            (folder / relative).write_bytes(content)
        from PIL import Image

        for name in ("original.webp", "small.webp"):
            Image.new("RGB", (32, 32), "orange").save(folder / "images" / name, format="WEBP")
        (folder / "assets" / "good-link.pdf").symlink_to(folder / "assets" / "guide.pdf")
        return source

    def comparable(value, context):
        import re

        value = canonical(value, context)

        def visit(v, key=""):
            if isinstance(v, dict):
                return {k: visit(x, k) for k, x in v.items()}
            if isinstance(v, list):
                result = [visit(x, key) for x in v]
                return (
                    sorted(result, key=lambda x: json.dumps(x, sort_keys=True, default=str))
                    if key
                    in (
                        "recipeCategory",
                        "tags",
                        "tools",
                        "notes",
                        "assets",
                        "recipe_assets",
                        "api_extras",
                        "recipes_to_tags",
                        "recipes_to_categories",
                        "recipes_to_tools",
                        "recipe_ingredient_ref_link",
                        "recipe_note_ref_link",
                    )
                    else sorted(result, key=lambda x: x["position"])
                    if key == "recipe_instructions"
                    else result
                )
            if isinstance(v, str):
                if key == "image" and len(v) == 4:
                    return "<cache-key>"
                if key == "path":
                    return v.replace("/" + context["label"] + "-", "/<context>-")
                return re.sub(
                    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>", v
                )
            if key in ("ingredient_id", "id"):
                return "<generated-id>"
            return v

        return visit(value)

    def probe(  # noqa: C901 - compare each HTTP operation, its complete rows, files and events atomically.
        name,
        body=None,
        *,
        mode="owner",
        expected=None,
        selected=None,
        recipes=None,
        lookup="slug",
        notifications=True,
        **kwargs,
    ):
        chosen = selected or contexts
        recipes = recipes or [sources[contexts.index(c)] for c in chosen]
        case = {
            "name": name,
            "mode": mode,
            "request": kwargs or {"json": body},
            "observations": {},
            "result": "in_progress",
        }
        report["cases"].append(case)
        results = []
        try:
            for context, source in zip(chosen, recipes, strict=True):
                before = snapshot(source)
                sourcefiles = file_state(source)
                identifier = source["slug"]
                if lookup == "uuid":
                    identifier = source["id"]
                if lookup == "hex":
                    identifier = UUID(source["id"]).hex
                if lookup == "braces":
                    identifier = "{" + source["id"].upper() + "}"
                if lookup == "urn":
                    identifier = "urn:uuid:" + source["id"]
                if lookup == "missing":
                    identifier = "not-found-005-unique"
                token = context["actor_token"] if mode == "actor" else context["token"]
                request_args = dict(kwargs)
                if not any(key in request_args for key in ("json", "data")):
                    request_args["json"] = body
                headers = dict(request_args.pop("headers", {}))
                if mode == "none":
                    token = None
                if mode == "invalid":
                    token = "invalid"
                if mode == "foreign":
                    token = root_token
                if mode == "api":
                    token = context["api_token"]
                if mode == "cookie":
                    request_args["cookies"] = {"mealie.access_token": token}
                    token = None
                if mode in (
                    "expired",
                    "future-iat",
                    "future-nbf",
                    "wrong-signature",
                    "unregistered-api",
                    "null-integration",
                ):
                    payload = {"sub": context["user_id"], "exp": datetime.now(UTC) + timedelta(minutes=10)}
                    if mode == "expired":
                        payload["exp"] = datetime.now(UTC) - timedelta(seconds=5)
                    if mode == "future-iat":
                        payload["iat"] = datetime.now(UTC) + timedelta(minutes=5)
                    if mode == "future-nbf":
                        payload["nbf"] = datetime.now(UTC) + timedelta(minutes=5)
                    if mode == "unregistered-api":
                        payload = {"id": context["user_id"], "long_token": True}
                    if mode == "null-integration":
                        payload["integration_id"] = None
                    token = jwt.encode(
                        payload, "wrong-test-key" if mode == "wrong-signature" else SECRET, algorithm="HS256"
                    )
                if token:
                    headers["Authorization"] = "Bearer " + token
                event_start = len(report["notifications"])
                with db.connect() as connection:
                    count_before = connection.execute(
                        text("SELECT COUNT(*) FROM recipes WHERE group_id=:id"), {"id": scalar(context["group_id"])}
                    ).scalar()
                response = requests.post(
                    context["origin"] + "/api/recipes/" + quote(identifier, safe="") + "/duplicate",
                    headers=headers,
                    timeout=30,
                    **request_args,
                )
                observed = capture(response)
                case["observations"][context["label"]] = observed
                assert snapshot(source) == before, "Duplication changed source rows"
                assert file_state(source) == sourcefiles, "Duplication changed source file contents or permissions"
                if expected is not None:
                    assert response.status_code == expected, observed
                if context["label"] == "gateway":
                    assert observed["headers"].get("x-mealie-backend") == "java", observed
                if response.status_code == 201:
                    copied = response.json()
                    results.append(copied)
                    assert UUID(copied["id"]).version == 4 and copied["id"] != source["id"]
                    assert copied["userId"] == context["actor_id" if mode == "actor" else "user_id"]
                    assert copied["householdId"] == context["actor_household_id" if mode == "actor" else "household_id"]
                    assert copied["groupId"] == source["groupId"]
                    assert copied["lastMade"] is None and copied["comments"] == []
                    assert copied["createdAt"] == source["createdAt"] and copied["dateAdded"] == source["dateAdded"]
                    assert copied["rating"] == source["rating"]
                    assert copied["settings"] == (
                        source["settings"]
                        or {
                            "public": True,
                            "showNutrition": True,
                            "showAssets": True,
                            "landscapeView": True,
                            "disableComments": False,
                            "locked": False,
                        }
                    )
                    assert copied == api("GET", "/api/recipes/" + copied["slug"], context["token"]), (
                        "Response differs from original Python full detail projection"
                    )
                    stored = snapshot(copied)
                    observed["stored"] = stored
                    assert all(
                        stored["related"][table] == []
                        for table in (
                            "recipe_timeline_events",
                            "households_to_recipes",
                            "users_to_recipes",
                            "recipe_share_tokens",
                            "recipe_comments",
                        )
                    )
                    for table in (
                        "recipes_ingredients",
                        "recipe_instructions",
                        "recipe_settings",
                        "recipe_nutrition",
                        "notes",
                        "recipe_assets",
                        "api_extras",
                        "recipe_ingredient_ref_link",
                        "recipe_note_ref_link",
                        "recipes_ingredients_substitutions",
                    ):
                        assert not (
                            {str(r["id"]) for r in stored["related"][table]}
                            & {str(r["id"]) for r in before["related"][table]}
                        ), table
                    if not source["recipeIngredient"]:
                        assert len(copied["recipeIngredient"]) == 1 and copied["recipeIngredient"][0]["quantity"] == 0
                    if not source["recipeInstructions"]:
                        assert len(copied["recipeInstructions"]) == 1 and copied["recipeInstructions"][0]["text"]
                    for original, duplicate in zip(
                        source["recipeIngredient"], copied["recipeIngredient"], strict=bool(source["recipeIngredient"])
                    ):
                        assert original["referenceId"] != duplicate["referenceId"]
                        assert {k: v for k, v in original.items() if k != "referenceId"} == {
                            k: v for k, v in duplicate.items() if k != "referenceId"
                        }
                    for original, duplicate in zip(
                        source["recipeInstructions"],
                        copied["recipeInstructions"],
                        strict=bool(source["recipeInstructions"]),
                    ):
                        assert original["id"] != duplicate["id"]
                        assert duplicate["text"] == original["text"].replace(source["id"], copied["id"])
                        assert duplicate["ingredientReferences"] == original["ingredientReferences"]
                        assert duplicate["noteReferences"] == original["noteReferences"]
                    copiedfiles = file_state(copied)
                    observed["files"] = copiedfiles
                    expected_files = dict(sourcefiles)
                    directory = data / "recipes" / source["id"]
                    for f in directory.rglob("*"):
                        if f.is_symlink() and f.is_file():
                            expected_files[str(f.relative_to(directory))] = {
                                "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
                                "size": f.stat().st_size,
                                "mode": f.stat().st_mode & 0o777,
                            }
                    assert copiedfiles == expected_files, (copiedfiles, expected_files)
                    for relative in copiedfiles:
                        assert (data / "recipes" / copied["id"] / relative).stat().st_ino != (
                            directory / relative
                        ).stat().st_ino, "File not independent"
                    for _ in range(100):
                        events = delivered(context, copied["slug"], event_start)
                        if events or not notifications:
                            break
                        time.sleep(0.05)
                    observed["notifications"] = events
                    assert len(events) == (1 if notifications else 0), events
                    for event in events:
                        assert event["path"] == "/" + context["label"] + "-" + (
                            "actor" if mode == "actor" else "owner"
                        ), event
                        assert event["body"]["document_data"] == {
                            "documentType": "recipe",
                            "operation": "create",
                            "recipeSlug": copied["slug"],
                        }
                        assert event["body"]["event_type"] == "recipe_created"
                        assert event["body"]["integration_id"] == (
                            "duplicate-integration" if mode == "api" else "generic"
                        )
                        assert "http://" not in event["body"]["message"], event
                elif mode == "null-integration":
                    with db.connect() as connection:
                        assert (
                            count_before + 1
                            == connection.execute(
                                text("SELECT COUNT(*) FROM recipes WHERE group_id=:id"),
                                {"id": scalar(context["group_id"])},
                            ).scalar()
                        ), "Python notification failure must retain the committed duplication"
                    retained = api("GET", "/api/recipes/" + body["name"].lower().replace(" ", "-"), context["token"])
                    observed["stored"] = snapshot(retained)
                    observed["files"] = file_state(retained)
                    assert retained["lastMade"] is None and observed["files"]
                else:
                    with db.connect() as connection:
                        assert (
                            count_before
                            == connection.execute(
                                text("SELECT COUNT(*) FROM recipes WHERE group_id=:id"),
                                {"id": scalar(context["group_id"])},
                            ).scalar()
                        ), "Rejected duplication wrote a recipe"
            reference = chosen[0]
            oracle = case["observations"][reference["label"]]
            for context in chosen[1:]:
                observed = case["observations"][context["label"]]
                assert observed["status"] == oracle["status"]
                assert comparable({k: v for k, v in observed.items() if k != "headers"}, context) == comparable(
                    {k: v for k, v in oracle.items() if k != "headers"}, reference
                ), ("Body/state/event mismatch", comparable(observed, context), comparable(oracle, reference))
                for header in ("content-type", "www-authenticate"):
                    left_header = observed["headers"].get(header)
                    right_header = oracle["headers"].get(header)
                    if header == "content-type":
                        # Media types/charsets are case-insensitive; spaces around semicolons are insignificant.
                        left_header = str(left_header).lower().replace(" ", "")
                        right_header = str(right_header).lower().replace(" ", "")
                    assert left_header == right_header, header
            case["result"] = "passed"
        except Exception:
            case["result"] = "failed"
            case["failure"] = traceback.format_exc()
            report["failures"].append({"case": name, "failure": case["failure"]})
        print(name, case["result"], flush=True)  # noqa: T201 - CLI parity progress
        return results

    try:
        wrapper = data / "python_server.py"
        wrapper.write_text(
            "import os,sys,re\nfrom pathlib import Path\nsys.path.insert(0," + repr(str(REPO)) + ")\n"
            "from mealie.app import app\nfrom fastapi.responses import JSONResponse\n"
            "@app.middleware('http')\nasync def block_duplicate(request,call_next):\n"
            " if request.method=='POST' and re.fullmatch(r'/api/recipes/[^/]+/duplicate',request.url.path):\n"
            "  if Path(os.environ['DUPLICATE_PARITY_BLOCK_FILE']).exists():\n"
            "   return JSONResponse({'detail':'Python duplicate disabled for independence test'},status_code=503)\n"
            " return await call_next(request)\nimport uvicorn\n"
            "uvicorn.run(app,host='127.0.0.1',port=int(os.environ['API_PORT']),log_level='info')\n"
        )
        py = start("python", ["uv", "run", "--frozen", "python", str(wrapper)])
        wait(python, "/api/app/about", py)
        root_token = login("changeme@example.com", "MyPassword")
        contexts = []
        for label, origin in (("python", python), ("direct", java), ("gateway", gateway)):
            group = api("POST", "/api/admin/groups", root_token, json={"name": "Duplicate " + label})
            context = {"label": label, "origin": origin, "group_id": group["id"], "group_slug": group["slug"]}
            for who in ("owner", "actor"):
                home = api(
                    "POST",
                    "/api/admin/households",
                    root_token,
                    json={"name": label + " " + who, "groupId": group["id"]},
                )
                email = label + "-" + who + "@duplicate.example"
                api(
                    "POST",
                    "/api/admin/users",
                    root_token,
                    json={
                        "fullName": email,
                        "username": label + "-" + who,
                        "email": email,
                        "password": "Duplicate-test-password1",
                        "group": group["name"],
                        "household": home["name"],
                        "admin": False,
                        "canManage": False,
                        "canManageHousehold": False,
                        "canOrganize": True,
                        "tokens": [],
                    },
                )
                token = login(email, "Duplicate-test-password1")
                user = api("GET", "/api/users/self", token)
                notifier = api(
                    "POST",
                    "/api/households/events/notifications",
                    token,
                    json={
                        "name": label + " " + who,
                        "appriseUrl": f"json://localhost:{sink_port}/" + label + "-" + who,
                    },
                )
                notifier = api(
                    "PUT",
                    "/api/households/events/notifications/" + notifier["id"],
                    token,
                    json={
                        **notifier,
                        "options": {**notifier["options"], "recipeCreated": True, "recipeUpdated": True},
                    },
                )
                if who == "owner":
                    context.update(
                        token=token, user_id=user["id"], household_id=home["id"], email=email, notifier=notifier
                    )
                else:
                    context.update(actor_token=token, actor_id=user["id"], actor_household_id=home["id"])
            api_token = jwt.encode(
                {"id": context["user_id"], "long_token": True, "integration_id": "duplicate-integration"},
                SECRET,
                algorithm="HS256",
            )
            execute(
                "INSERT INTO long_live_tokens (name,token,user_id,created_at) VALUES (:name,:token,:user,:at)",
                {
                    "name": label,
                    "token": api_token,
                    "user": scalar(context["user_id"]),
                    "at": datetime.now(UTC).replace(tzinfo=None),
                },
            )
            context["api_token"] = api_token
            contexts.append(context)
        report["fixture_owners"] = [
            {k: v for k, v in c.items() if k not in ("token", "actor_token", "api_token")} for c in contexts
        ]
        jp = start("java", ["java", "-jar", str(args.jar)])
        report["java_health"] = wait(java, "/api/java/health", jp)
        config = data / "Caddyfile"
        config.write_text((REPO / "gateway/Caddyfile").read_text().replace(":8080 {", f":{gateway_port} {{"))
        gw = start(
            "gateway",
            [str(args.gateway), "run", "--config", str(config), "--adapter", "caddyfile"],
            {
                **env,
                "PYTHON_UPSTREAM": f"localhost:{py_port}",
                "JAVA_UPSTREAM": f"localhost:{java_port}",
                "AUTH_TOKEN_UPSTREAM": f"localhost:{java_port}",
            },
        )
        wait(gateway, "/api/app/about", gw)
        sources = [rich_fixture(c) for c in contexts]
        report["source_recipes"] = sources
        for c in contexts:
            execute(
                "UPDATE users SET can_organize=:flag WHERE group_id=:id",
                {"flag": False if args.engine == "postgres" else 0, "id": scalar(c["group_id"])},
            )
        for name, body, status in [
            ("custom name", {"name": "Named Copy"}, 201),
            ("omitted name", {}, 201),
            ("null name", {"name": None}, 201),
            ("empty name", {"name": ""}, 201),
            ("unicode", {"name": "Café 中文 & Dinner"}, 201),
            ("entity", {"name": "Coffee &amp; Cake"}, 201),
            ("special only", {"name": "!!!"}, 400),
            ("whitespace", {"name": "   "}, 400),
            ("numeric name", {"name": 123}, 422),
            ("bool name", {"name": True}, 422),
            ("array name", {"name": []}, 422),
            ("object name", {"name": {}}, 422),
            ("body array", [], 422),
            ("body string", "test", 422),
            ("body null", None, 422),
            (
                "extra ignored",
                {"name": "Ignore Extra", "id": str(uuid4()), "groupId": str(uuid4()), "recipeIngredient": []},
                201,
            ),
        ]:
            probe(name, body, expected=status)
        for lookup in ("uuid", "hex", "braces", "urn", "missing"):
            probe(
                "lookup " + lookup,
                {"name": "Lookup " + lookup},
                lookup=lookup,
                expected=404 if lookup == "missing" else 201,
            )
        for mode in ("actor", "cookie", "api"):
            probe("authentication " + mode, {"name": "Auth " + mode}, mode=mode, expected=201)
        for mode in (
            "foreign",
            "none",
            "invalid",
            "expired",
            "future-iat",
            "future-nbf",
            "wrong-signature",
            "unregistered-api",
        ):
            probe("authentication " + mode, {}, mode=mode, expected=404 if mode == "foreign" else 401)
        probe("invalid field before auth", {"name": 42}, mode="none", expected=401)
        probe("malformed JSON", data='{"name":', headers={"Content-Type": "application/json"}, expected=422)
        probe(
            "malformed JSON before auth",
            mode="none",
            data='{"name":',
            headers={"Content-Type": "application/json"},
            expected=422,
        )
        probe("empty body", data="", headers={"Content-Type": "application/json"}, expected=422)
        probe("plain content type", data='{"name":"Plain"}', headers={"Content-Type": "text/plain"}, expected=422)
        probe(
            "vendor JSON",
            data='{"name":"Vendor JSON"}',
            headers={"Content-Type": "application/recipe+json"},
            expected=201,
        )
        probe(
            "UTF-16 JSON",
            data=json.dumps({"name": "UTF16 Copy"}).encode("utf-16"),
            headers={"Content-Type": "application/json"},
            expected=201,
        )
        probe("invalid UTF-8", data=b"\xff", headers={"Content-Type": "application/json"}, expected=400)
        probe(
            "duplicate JSON keys",
            data='{"name":"First","name":"Last Key Copy"}',
            headers={"Content-Type": "application/json"},
            expected=201,
        )
        probe("null integration postcommit", {"name": "Null Integration Copy"}, mode="null-integration", expected=500)
        for i in range(10):
            probe("collision retry " + str(i), {"name": "Collision Name"}, expected=201)
        probe("collision limit", {"name": "Collision Name"}, expected=400)
        long_name = "a" * 260
        probe("long name truncation", {"name": long_name}, expected=201)
        probe("long truncated collision exhaustion", {"name": long_name}, expected=400)
        empty_sources = []
        for context in contexts:
            id = scalar(str(uuid4()))
            at = datetime.now(UTC).replace(tzinfo=None)
            execute(
                "INSERT INTO recipes "
                "(id,name,slug,group_id,user_id,name_normalized,description,description_normalized,recipe"
                "_servings,recipe_yield_quantity,created_at,update_at,date_updated,date_added,is_ocr_reci"
                "pe)"
                " VALUES (:id,:name,:slug,:group,:user,:normalized,'','',0,0,:at,:at,:at,:date,:flag)",
                {
                    "id": id,
                    "name": "Empty Source",
                    "slug": "empty-source",
                    "group": scalar(context["group_id"]),
                    "user": scalar(context["user_id"]),
                    "normalized": "empty source",
                    "at": at,
                    "date": at.date(),
                    "flag": False if args.engine == "postgres" else 0,
                },
            )
            empty_sources.append(api("GET", "/api/recipes/empty-source", context["token"]))
        probe("empty nested defaults", {"name": "Default Copy"}, recipes=empty_sources, expected=201)
        probe(
            "localized empty defaults",
            {"name": "French Defaults"},
            recipes=empty_sources,
            headers={"Accept-Language": "fr-FR"},
            expected=201,
        )
        # Broken links cause partial file copying and an error log, but the committed copy still returns 201 and
        # notifies.
        broken_sources = []
        for context in contexts:
            source = fixture(context, "Broken Files Source")
            directory = data / "recipes" / source["id"]
            (directory / "assets").mkdir(parents=True, exist_ok=True)
            (directory / "assets" / "first.bin").write_bytes(b"copy survives later file failure")
            (directory / "assets" / "broken.bin").symlink_to(directory / "missing-test-target")
            source["image"] = None
            broken_sources.append(source)
        probe("partial copy dangling symlink", {"name": "Partial Copy"}, recipes=broken_sources, expected=201)
        # Editing all copied private rows and file bytes must leave the source untouched.
        copies = probe("independent copied data", {"name": "Independent Copy"}, expected=201)
        for context, source, copied in zip(contexts, sources, copies, strict=True):
            before = snapshot(source)
            files_before = file_state(source)
            edited = {
                **copied,
                "notes": [{"title": "New", "text": "Independent edit"}],
                "extras": {"edited": "yes"},
                "nutrition": {"calories": "999"},
                "assets": [{"name": "Edited", "icon": "mdi-file", "fileName": "guide.pdf"}],
            }
            edited["recipeIngredient"][0]["note"] = "Changed ingredient"
            edited["recipeIngredient"][0]["substitutions"] = [{"note": "independent workaround"}]
            edited["recipeInstructions"][0]["text"] = "Changed instruction"
            api("PUT", "/api/recipes/" + copied["slug"], context["token"], json=edited)
            (data / "recipes" / copied["id"] / "images" / "original.webp").write_bytes(b"copy-only edited image")
            assert snapshot(source) == before and file_state(source) == files_before
        report["cases"].append(
            {"name": "edit copied nested data and files leaves originals unchanged", "result": "passed"}
        )
        # Regressions for every already migrated recipe endpoint, through Java directly and the gateway.
        for context in contexts[1:]:
            for method, path, kwargs in [
                ("GET", "/api/recipes/" + sources[contexts.index(context)]["id"], {}),
                ("GET", "/api/recipes", {"params": {"page": 1, "perPage": 2, "search": "Copy", "orderBy": "name"}}),
                ("POST", "/api/recipes", {"json": {"name": "Native Creation Regression"}}),
                (
                    "PATCH",
                    "/api/recipes/" + sources[contexts.index(context)]["slug"] + "/last-made",
                    {"json": {"timestamp": "2026-10-11T01:02:03.123456Z"}},
                ),
            ]:
                response = requests.request(
                    method,
                    context["origin"] + path,
                    headers={"Authorization": "Bearer " + context["token"]},
                    timeout=30,
                    **kwargs,
                )
                assert response.status_code in (200, 201), capture(response)
                if context["label"] == "gateway":
                    assert response.headers.get("X-Mealie-Backend") == "java", capture(response)
                report["cases"].append(
                    {
                        "name": "regression " + context["label"] + " " + method + " " + path,
                        "response": capture(response),
                        "result": "passed",
                    }
                )
        for method, path, payload in [
            ("GET", "/api/recipes/ui-duplicate-source/duplicate", None),
            ("PATCH", "/api/recipes/ui-duplicate-source/duplicate", {}),
            ("PUT", "/api/recipes/ui-duplicate-source", {}),
            ("PATCH", "/api/recipes/ui-duplicate-source", {}),
            ("GET", "/api/recipes/timeline/events", None),
            ("POST", "/api/recipes/create/url", {}),
            ("POST", "/api/recipes/ui-duplicate-source/duplicate/", {}),
        ]:
            response = requests.request(
                method,
                gateway + path,
                headers={"Authorization": "Bearer " + contexts[2]["token"]},
                json=payload,
                allow_redirects=False,
                timeout=30,
            )
            assert response.headers.get("X-Mealie-Backend") == "python", capture(response)
            report["cases"].append(
                {"name": "gateway boundary " + method + " " + path, "response": capture(response), "result": "passed"}
            )
        for path in ("/internal/recipe-created", "/internal/recipe-updated", "/api/internal/events"):
            response = requests.post(gateway + path, json={})
            assert response.status_code == 404
            report["cases"].append(
                {"name": "hidden adapter " + path, "response": capture(response), "result": "passed"}
            )
        # Signed messageKind accepts only the approved duplicate variant and cannot alter rows or ownership.
        context = contexts[1]
        copied = api("GET", "/api/recipes/named-copy", context["token"])
        before = snapshot(copied)
        metadata = {
            "eventId": str(uuid4()),
            "timestamp": datetime.now(UTC).isoformat(),
            "recipeId": copied["id"],
            "userId": context["user_id"],
            "groupId": context["group_id"],
            "householdId": context["household_id"],
            "locale": "en-US",
            "integrationId": "generic",
            "messageKind": "duplicate",
        }

        def signed(data):
            raw = json.dumps(data).encode()
            stamp = str(int(time.time()))
            signature = hmac.new(adapter_key.encode(), stamp.encode() + b"." + raw, hashlib.sha256).hexdigest()
            return requests.post(
                python + "/internal/recipe-created",
                data=raw,
                headers={
                    "Content-Type": "application/json",
                    "X-Mealie-Event-Time": stamp,
                    "X-Mealie-Event-Signature": signature,
                },
                timeout=30,
            )

        for name, event_payload, status in [
            ("invalid message variant", {**metadata, "messageKind": "arbitrary"}, 422),
            ("foreign ownership", {**metadata, "householdId": contexts[0]["household_id"]}, 404),
            ("valid duplicate event", metadata, 204),
            ("replayed duplicate event", metadata, 409),
        ]:
            response = signed(event_payload)
            assert response.status_code == status, capture(response)
            assert snapshot(copied) == before
            report["cases"].append({"name": "adapter " + name, "response": capture(response), "result": "passed"})
        # Block precisely the Python duplication route while keeping its notification adapter enabled.
        (data / "block-python-duplicate").write_text(
            "Only Python duplication POST disabled; event adapter remains available.\n"
        )
        reference = requests.post(
            python + "/api/recipes/" + sources[0]["slug"] + "/duplicate",
            headers={"Authorization": "Bearer " + contexts[0]["token"]},
            json={"name": "Blocked Python"},
            timeout=30,
        )
        assert reference.status_code == 503
        for context in contexts[1:]:
            probe(
                "native duplicate with Python POST disabled " + context["label"],
                {"name": "Native Proof " + context["label"]},
                selected=[context],
                expected=201,
            )
        report["independence_proof"] = {
            "python_duplicate_response": capture(reference),
            "java_direct_and_gateway_copied_stored_data_files_and_delivered_real_notifications": True,
            "event_adapter_enabled": True,
        }
        report["result"] = "passed" if not report["failures"] else "failed"
        report["passed_cases"] = sum(c["result"] == "passed" for c in report["cases"])
        report["case_count"] = len(report["cases"])
        report["ui_review"] = {
            "gateway": gateway,
            "email": contexts[2]["email"],
            "password": "Duplicate-test-password1",
            "recipe_slug": sources[2]["slug"],
            "fixture_database": database,
            "python_duplicate_guard_active": True,
        }
        if args.keep_serving:
            report["ended_utc"] = datetime.now(UTC).isoformat()
            args.report.write_text(json.dumps(report, indent=2, default=str, ensure_ascii=False) + "\n")
            print("UI_REVIEW_READY " + json.dumps(report["ui_review"]), flush=True)  # noqa: T201 - CLI handoff
            while True:
                time.sleep(1)
    except Exception:
        report["result"] = "failed"
        report["failures"].append({"case": "harness", "failure": traceback.format_exc()})
        raise
    finally:
        # Stop only this lifecycle's services; never remove directories, fixture rows or databases.
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
        sink.shutdown()
        sink.server_close()
        for log in logs:
            log.close()
        db.dispose()
        report["ended_utc"] = datetime.now(UTC).isoformat()
        args.report.write_text(json.dumps(report, indent=2, default=str, ensure_ascii=False) + "\n")
    assert report["result"] == "passed", report["failures"]


if __name__ == "__main__":
    main()
