"""Recipe last-made parity: native Java, gateway and original Python on retained isolated databases."""

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

    py_port, java_port, gateway_port, sink_port = (p + args.port_offset for p in (10400, 10410, 10480, 10490))
    for port in (py_port, java_port, gateway_port, sink_port):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", port))
    assert args.jar.is_file() and args.gateway.is_file()
    data = Path(tempfile.mkdtemp(prefix="compx574-recipe-last-made-004-"))
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
        "BASE_URL": "http://isolated-last-made-review.invalid",
        "RECIPE_EVENT_ADAPTER_KEY": adapter_key,
        "RECIPE_EVENT_ADAPTER_URL": f"http://localhost:{py_port}/internal/recipe-created",
        "LAST_MADE_PARITY_BLOCK_FILE": str(data / "block-python-last-made"),
    }
    if args.production_validation:
        env.update(PRODUCTION="true", TESTING="false")
        (data / ".secret").write_text(SECRET)
    env["POSTGRES_URL_OVERRIDE"] = ""
    database = str(data / "mealie.db")
    if args.engine == "postgres":
        database = "compx574_recipe_last_made_004_" + uuid4().hex[:12]
        assert database.startswith("compx574_recipe_last_made_004_")
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
            "Additional Spring Security no-cache protection is recorded. No feature is omitted."
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
            uid = context[field]
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
            return {
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
                    )
                },
            }

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

    def probe(  # noqa: C901 - one probe compares authentication, response, state and events atomically.
        name: str,
        body: Any = None,
        *,
        mode: str = "owner",
        expected: int | None = None,
        recipes: list[dict[str, Any]] | None = None,
        selected: list[dict[str, Any]] | None = None,
        lookup: str = "slug",
        notifications: bool = True,
        **kwargs: Any,
    ) -> list[dict[str, Any]]:
        chosen = selected or contexts
        recipes = recipes or [fixture(c, name) for c in chosen]
        case = {
            "name": name,
            "mode": mode,
            "request": kwargs or {"json": body},
            "observations": {},
            "result": "in_progress",
        }
        report["cases"].append(case)
        try:
            for context, recipe in zip(chosen, recipes, strict=True):
                before = snapshot(recipe)
                pathvalue = recipe["slug"]
                if lookup == "uuid":
                    pathvalue = recipe["id"]
                if lookup == "hex":
                    pathvalue = UUID(recipe["id"]).hex
                if lookup == "braces":
                    pathvalue = "{" + recipe["id"].upper() + "}"
                if lookup == "urn":
                    pathvalue = "urn:uuid:" + recipe["id"]
                if lookup == "missing":
                    pathvalue = "nonexistent-004-unique"
                token = context["actor_token"] if mode == "actor" else context["token"]
                request_args = dict(kwargs or {"json": body})
                headers = dict(request_args.pop("headers", {}))
                if mode == "none":
                    token = None
                if mode == "invalid":
                    token = "invalid"
                if mode == "foreign":
                    token = root_token
                if mode == "foreign-user":
                    token = next(c["token"] for c in contexts if c["group_id"] != context["group_id"])
                if mode == "cookie":
                    request_args["cookies"] = {"mealie.access_token": token}
                    token = None
                if mode == "api":
                    token = context["api_token"]
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
                response = requests.patch(
                    context["origin"] + "/api/recipes/" + quote(pathvalue, safe="") + "/last-made",
                    headers=headers,
                    timeout=30,
                    **request_args,
                )
                observed = capture(response)
                after = snapshot(recipe)
                observed.update(before=before, after=after)
                case["observations"][context["label"]] = observed
                if expected is not None:
                    assert response.status_code == expected, (context["label"], observed)
                if context["label"] == "gateway":
                    assert observed["headers"].get("x-mealie-backend") == "java", observed
                if response.status_code == 200:
                    assert response.json() == api("GET", "/api/recipes/" + recipe["slug"], context["token"])
                    assert after["related"]["recipe_timeline_events"] == before["related"]["recipe_timeline_events"]
                    for table in ("recipes_ingredients", "recipe_instructions", "recipe_settings", "recipe_nutrition"):
                        assert after["related"][table] == before["related"][table], table
                    assert before["recipe"]["date_updated"] == after["recipe"]["date_updated"]
                    for _ in range(100):
                        events = delivered(context, recipe["slug"], event_start)
                        if events or not notifications:
                            break
                        time.sleep(0.05)
                    if not notifications:
                        time.sleep(0.15)
                        events = delivered(context, recipe["slug"], event_start)
                    observed["notifications"] = events
                    assert len(events) == (1 if notifications else 0), events
                    for event in events:
                        assert event["path"] == "/" + context["label"] + "-owner", event
                        assert event["body"]["document_data"] == {
                            "documentType": "recipe",
                            "operation": "update",
                            "recipeSlug": recipe["slug"],
                        }
                        assert event["body"]["type"] == "info"
                    observed["recipe_updated_at_changed"] = (
                        before["recipe"]["update_at"] != after["recipe"]["update_at"]
                    )
                    links_before = {str(r["household_id"]): r for r in before["related"]["households_to_recipes"]}
                    observed["association_changes"] = {
                        str(r["household_id"]): {
                            "inserted": str(r["household_id"]) not in links_before,
                            "id_preserved": str(r["household_id"]) not in links_before
                            or r["id"] == links_before[str(r["household_id"])]["id"],
                            "created_preserved": str(r["household_id"]) not in links_before
                            or r["created_at"] == links_before[str(r["household_id"])]["created_at"],
                            "updated_changed": str(r["household_id"]) not in links_before
                            or r["update_at"] != links_before[str(r["household_id"])]["update_at"],
                        }
                        for r in after["related"]["households_to_recipes"]
                    }
                elif mode != "null-integration":
                    assert before == after, "Rejected request changed database"
            reference = chosen[0]
            oracle = case["observations"][reference["label"]]
            for context in chosen[1:]:
                observed = case["observations"][context["label"]]
                assert observed["status"] == oracle["status"], (observed, oracle)
                left = canonical({k: v for k, v in oracle.items() if k != "headers"}, reference)
                right = canonical({k: v for k, v in observed.items() if k != "headers"}, context)
                for value in (left, right):
                    for event in value.get("notifications", []):
                        event["path"] = "/<context>-owner"
                    for state in ("before", "after"):
                        value[state]["recipe"]["slug"] = recipe["slug"]
                        for rows in value[state]["related"].values():
                            for row in rows:
                                for field in ("recipe_id", "reference_id", "instruction_id"):
                                    if field in row:
                                        row[field] = "<generated-id>"
                assert left == right, ("state or body mismatch", left, right)
                for header in ("content-type", "www-authenticate"):
                    assert observed["headers"].get(header) == oracle["headers"].get(header), header
            case["result"] = "passed"
        except Exception:
            case["result"] = "failed"
            case["failure"] = traceback.format_exc()
            report["failures"].append({"case": name, "failure": case["failure"]})
        print(name, case["result"], flush=True)  # noqa: T201 - CLI test progress
        return recipes

    try:
        wrapper = data / "python_server.py"
        wrapper.write_text(
            "import os,sys,re\nfrom pathlib import Path\nsys.path.insert(0," + repr(str(REPO)) + ")\n"
            "from mealie.app import app\nfrom fastapi.responses import JSONResponse\n"
            "@app.middleware('http')\nasync def block_last_made(request,call_next):\n"
            " if request.method=='PATCH' and re.fullmatch(r'/api/recipes/[^/]+/last-made',request.url.path):\n"
            "  if Path(os.environ['LAST_MADE_PARITY_BLOCK_FILE']).exists():\n"
            "   return JSONResponse({'detail':'Python last-made disabled for independence test'},status_code=503)\n"
            " return await call_next(request)\nimport uvicorn\n"
            "uvicorn.run(app,host='127.0.0.1',port=int(os.environ['API_PORT']),log_level='info')\n"
        )
        py = start("python", ["uv", "run", "--frozen", "python", str(wrapper)])
        wait(python, "/api/app/about", py)
        root_token = login("changeme@example.com", "MyPassword")
        contexts = []
        for label, origin in (("python", python), ("direct", java), ("gateway", gateway)):
            group = api("POST", "/api/admin/groups", root_token, json={"name": "Last Made " + label})
            context = {"label": label, "origin": origin, "group_id": group["id"], "group_slug": group["slug"]}
            for who in ("owner", "actor"):
                home = api(
                    "POST",
                    "/api/admin/households",
                    root_token,
                    json={"name": label + " " + who, "groupId": group["id"]},
                )
                email = label + "-" + who + "@last-made.example"
                api(
                    "POST",
                    "/api/admin/users",
                    root_token,
                    json={
                        "fullName": email,
                        "username": label + "-" + who,
                        "email": email,
                        "password": "Last-made-test-password1",
                        "group": group["name"],
                        "household": home["name"],
                        "admin": False,
                        "canManage": False,
                        "canManageHousehold": False,
                        "canOrganize": False,
                        "tokens": [],
                    },
                )
                token = login(email, "Last-made-test-password1")
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
                        "options": {**notifier["options"], "recipeCreated": False, "recipeUpdated": True},
                    },
                )
                if who == "owner":
                    context.update(
                        token=token, user_id=user["id"], household_id=home["id"], email=email, notifier=notifier
                    )
                else:
                    context.update(actor_token=token, actor_id=user["id"], actor_household_id=home["id"])
            api_token = jwt.encode(
                {"id": context["user_id"], "long_token": True, "integration_id": "last-made-integration"},
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
        corpus = [
            ("UTC", "2026-10-09T12:34:56Z"),
            ("microseconds", "2026-10-09T12:34:56.123400Z"),
            ("long fraction", "2026-10-09T12:34:56.123456789Z"),
            ("positive offset", "2026-10-09T12:34:56+13:00"),
            ("negative offset", "2026-10-09T12:34:56-05:30"),
            ("large offset", "2026-10-09T12:34:56+23:59"),
            ("naive", "2026-10-09T12:34:56"),
            ("date only", "2026-10-09"),
            ("space delimiter", "2026-10-09 12:34:56Z"),
            ("underscore delimiter", "2026-10-09_12:34:56Z"),
            ("lowercase", "2026-10-09t12:34:56z"),
            ("minutes only", "2026-10-09T12:34Z"),
            ("seconds epoch", 1791549296),
            ("millis epoch", 1791549296123),
            ("fraction epoch", 1791549296.123456),
            ("numeric string", "1791549296.123456"),
            ("negative epoch", -1.25),
            ("negative string", "-1.25"),
            ("leading decimal numeric string", ".5"),
            ("trailing decimal numeric string", "1."),
            ("oversized numeric string", "123456789012345678901234567890"),
            ("invalid date", "2026-02-30T12:34:56Z"),
            ("invalid timezone", "2026-10-09T12:34:56+24:00"),
            ("invalid text", "wrong"),
            ("empty timestamp", ""),
            ("null timestamp", None),
            ("bool timestamp", True),
            ("array timestamp", []),
            ("object timestamp", {}),
            ("range epoch", 10**18),
            ("minimum year", "0001-01-01T00:00:00Z"),
            ("maximum year", "9999-12-31T23:59:59.999999Z"),
        ]
        for name, value in corpus:
            probe(name, {"timestamp": value})
        for name, body in (
            ("missing", {}),
            ("body array", []),
            ("body string", "text"),
            ("body null", None),
            ("extra fields", {"timestamp": "2026-10-09T00:00:00Z", "groupId": "forged", "lastMade": "forged"}),
        ):
            probe(name, body)
        recipes = probe("cross household later", {"timestamp": "2026-10-10T00:00:00Z"}, mode="actor", expected=200)
        probe("owner earlier", {"timestamp": "2026-10-09T00:00:00Z"}, recipes=recipes, expected=200)
        probe("actor lowers", {"timestamp": "2026-10-08T00:00:00Z"}, mode="actor", recipes=recipes, expected=200)
        probe("actor repeat", {"timestamp": "2026-10-08T00:00:00Z"}, mode="actor", recipes=recipes, expected=200)
        probe("naive after global", {"timestamp": "2026-10-11T00:00:00"}, recipes=recipes, expected=200)
        for lookup in ("uuid", "hex", "braces", "urn"):
            probe(
                lookup + " lookup",
                {"timestamp": "2026-10-12T00:00:00Z"},
                recipes=recipes,
                lookup=lookup,
                expected=500 if lookup == "urn" and args.engine == "postgres" else 200,
            )
        probe(
            "foreign group admin", {"timestamp": "2026-10-13T00:00:00Z"}, mode="foreign", recipes=recipes, expected=404
        )
        probe(
            "foreign group ordinary user",
            {"timestamp": "2026-10-13T00:00:00Z"},
            mode="foreign-user",
            recipes=recipes,
            lookup="uuid",
            expected=404,
        )
        probe("missing recipe", {"timestamp": "2026-10-13T00:00:00Z"}, recipes=recipes, lookup="missing", expected=404)
        for private in (False, True):
            for locked in (False, True):
                fixtures = [fixture(c, f"Locked {private} {locked}") for c in contexts]
                for context, recipe in zip(contexts, fixtures, strict=True):
                    execute(
                        "UPDATE recipe_settings SET locked=:locked WHERE recipe_id=:id",
                        {"locked": locked, "id": scalar(recipe["id"])},
                    )
                    execute(
                        "UPDATE household_preferences SET private_household=:private, "
                        "lock_recipe_edits_from_other_households=:locked WHERE household_id=:id",
                        {"private": private, "locked": locked, "id": scalar(context["household_id"])},
                    )
                probe(
                    f"household boundaries {private} {locked}",
                    {"timestamp": "2026-10-10T00:00:00Z"},
                    mode="actor",
                    recipes=fixtures,
                    expected=200,
                )
        for mode in ("none", "invalid", "expired", "future-iat", "future-nbf", "wrong-signature", "unregistered-api"):
            probe(mode + " authentication", {"timestamp": "2026-10-13T00:00:00Z"}, mode=mode, expected=401)
        for mode in ("cookie", "api"):
            probe(mode + " authentication", {"timestamp": "2026-10-13T00:00:00Z"}, mode=mode, expected=200)
        probe("malformed JSON", data='{"timestamp":', headers={"Content-Type": "application/json"}, expected=422)
        probe("empty body", data="", headers={"Content-Type": "application/json"}, expected=422)
        probe("invalid field no auth", {}, mode="none", expected=401)
        probe(
            "malformed no auth",
            mode="none",
            data='{"timestamp":',
            headers={"Content-Type": "application/json"},
            expected=422,
        )
        probe(
            "text content type",
            data='{"timestamp":"2026-10-10T00:00:00Z"}',
            headers={"Content-Type": "text/plain"},
            expected=422,
        )
        for context in contexts:
            notifier = context["notifier"]
            api(
                "PUT",
                "/api/households/events/notifications/" + notifier["id"],
                context["token"],
                json={**notifier, "enabled": False},
            )
        probe(
            "disabled owner destination does not notify actor",
            {"timestamp": "2026-10-13T00:00:00Z"},
            mode="actor",
            expected=200,
            notifications=False,
        )
        for context in contexts:
            notifier = context["notifier"]
            api(
                "PUT",
                "/api/households/events/notifications/" + notifier["id"],
                context["token"],
                json={**notifier, "enabled": True},
            )
        probe(
            "translated update event",
            {"timestamp": "2026-10-13T00:00:00Z"},
            headers={"Accept-Language": "de-DE"},
            json={"timestamp": "2026-10-13T00:00:00Z"},
            expected=200,
        )
        # Creation's original event delivery remains intact after extending the adapter.
        for context in contexts[1:]:
            notifier = context["notifier"]
            api(
                "PUT",
                "/api/households/events/notifications/" + notifier["id"],
                context["token"],
                json={**notifier, "options": {**notifier["options"], "recipeCreated": True}},
            )
            before_event = len(report["notifications"])
            response = requests.post(
                context["origin"] + "/api/recipes",
                headers={"Authorization": "Bearer " + context["token"]},
                json={"name": "Creation Notification Regression"},
                timeout=30,
            )
            assert response.status_code == 201
            created_recipe = api("GET", "/api/recipes/" + response.json(), context["token"])
            for _ in range(100):
                events = delivered(context, response.json(), before_event)
                if events:
                    break
                time.sleep(0.05)
            assert (
                len(events) == 1
                and events[0]["body"]["event_type"] == "recipe_created"
                and events[0]["body"]["document_data"]["operation"] == "create"
            )
            state = snapshot(created_recipe)
            assert (
                len(state["related"]["recipe_timeline_events"]) == 1 and not state["related"]["households_to_recipes"]
            )
            report["cases"].append(
                {
                    "name": "creation notification and timeline regression " + context["label"],
                    "response": capture(response),
                    "state": state,
                    "notifications": events,
                    "result": "passed",
                }
            )
            api("PUT", "/api/households/events/notifications/" + notifier["id"], context["token"], json=notifier)
        # Signed adapter calls can only deliver events, with same-group actors and owner destinations.
        context = contexts[2]
        recipe = recipes[2]
        unchanged = snapshot(recipe)

        def metadata(**overrides: Any) -> dict[str, Any]:
            return {
                "eventId": str(uuid4()),
                "timestamp": datetime.now(UTC).isoformat(),
                "recipeId": recipe["id"],
                "userId": context["actor_id"],
                "groupId": context["group_id"],
                "householdId": context["household_id"],
                "locale": "en-US",
                "integrationId": "adapter-security-test",
                **overrides,
            }

        def adapter(payload: dict[str, Any], *, stale: bool = False, bad: bool = False) -> requests.Response:
            raw = json.dumps(payload).encode()
            timestamp = str(int(time.time()) - (120 if stale else 0))
            signed = hmac.new(adapter_key.encode(), timestamp.encode() + b"." + raw, hashlib.sha256).hexdigest()
            return requests.post(
                python + "/internal/recipe-updated",
                headers={
                    "Content-Type": "application/json",
                    "X-Mealie-Event-Time": timestamp,
                    "X-Mealie-Event-Signature": "invalid" if bad else signed,
                },
                data=raw,
                timeout=30,
            )

        payload = metadata()
        accepted = adapter(payload)
        checks = [
            accepted,
            adapter(payload),
            adapter(metadata(), bad=True),
            adapter(metadata(), stale=True),
            adapter(metadata(householdId=context["actor_household_id"])),
            adapter(metadata(userId=contexts[0]["user_id"])),
            adapter(metadata(recipeId=str(uuid4()))),
            adapter({}),
        ]
        assert [r.status_code for r in checks] == [204, 409, 401, 401, 404, 404, 404, 422]
        assert unchanged == snapshot(recipe)
        report["cases"].append(
            {
                "name": "update event adapter signature replay ownership and read-only checks",
                "responses": [capture(r) for r in checks],
                "stored_recipe_and_associations_unchanged": True,
                "result": "passed",
            }
        )
        # Regression checks on the three migrated endpoints, using each native stack's group.
        for context in contexts[1:]:
            headers = {"Authorization": "Bearer " + context["token"]}
            created = requests.post(
                context["origin"] + "/api/recipes", headers=headers, json={"name": "UI Last Made Review"}, timeout=30
            )
            assert created.status_code == 201, capture(created)
            slug = created.json()
            detail = requests.get(context["origin"] + "/api/recipes/" + slug, headers=headers, timeout=30)
            assert detail.status_code == 200 and detail.json() == api("GET", "/api/recipes/" + slug, context["token"])
            for params in (
                {"page": 1, "perPage": 2, "orderBy": "name", "orderDirection": "asc"},
                {"page": 2, "perPage": 2},
                {"queryFilter": "last_made>=2026-10-09", "perPage": 3},
                {"search": "UI Last Made Review"},
            ):
                listed = requests.get(context["origin"] + "/api/recipes", headers=headers, params=params, timeout=30)
                oracle = requests.get(python + "/api/recipes", headers=headers, params=params, timeout=30)
                assert listed.status_code == oracle.status_code == 200
                # Pagination links carry the origin requested by the browser.
                assert json.loads(json.dumps(listed.json()).replace(context["origin"], "<origin>")) == json.loads(
                    json.dumps(oracle.json()).replace(python, "<origin>")
                ), (listed.json(), oracle.json())
            report["cases"].append(
                {
                    "name": "create detail listing regression " + context["label"],
                    "create": capture(created),
                    "detail": capture(detail),
                    "pagination_search_last_made_filter_verified": True,
                    "result": "passed",
                }
            )
        # Only unmigrated routes must continue to use Python; probes are reads or deliberately invalid writes.
        for method, path, payload in (
            ("GET", "/api/recipes/timeline/events", None),
            ("PATCH", "/api/recipes/ui-last-made-review", {}),
            ("PUT", "/api/recipes/ui-last-made-review", {}),
            ("POST", "/api/recipes/create/url", {}),
            ("PATCH", "/api/recipes/ui-last-made-review/last-made/", {"timestamp": "bad"}),
        ):
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
        assert requests.post(gateway + "/internal/recipe-updated", json={}).status_code == 404
        # Java continues to update and notify after Python's matching PATCH is deliberately disabled.
        (data / "block-python-last-made").write_text("Only Python last-made PATCH disabled; adapter stays enabled.\n")
        reference = requests.patch(
            python + "/api/recipes/" + recipes[0]["slug"] + "/last-made",
            headers={"Authorization": "Bearer " + contexts[0]["token"]},
            json={"timestamp": "2026-10-15T00:00:00Z"},
            timeout=30,
        )
        assert reference.status_code == 503
        for context in contexts[1:]:
            probe(
                "native update with Python PATCH disabled " + context["label"],
                {"timestamp": "2026-10-15T00:00:00Z"},
                selected=[context],
                expected=200,
            )
        report["independence_proof"] = {
            "python_patch_response": capture(reference),
            "java_direct_and_gateway_updated_stored_data_and_delivered_real_notifications": True,
            "event_adapter_enabled": True,
        }
        report["result"] = "passed" if not report["failures"] else "failed"
        report["passed_cases"] = sum(c["result"] == "passed" for c in report["cases"])
        report["case_count"] = len(report["cases"])
        report["ui_review"] = {
            "gateway": gateway,
            "email": contexts[2]["email"],
            "password": "Last-made-test-password1",
            "recipe_slug": "ui-last-made-review",
            "fixture_database": database,
            "python_last_made_guard_active": True,
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
