"""Manual recipe creation parity: native Java, gateway and original Python on retained isolated databases."""

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
from concurrent.futures import ThreadPoolExecutor
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
    py_port, java_port, gateway_port, sink_port = (p + args.port_offset for p in (9400, 9410, 9480, 9490))
    for port in (py_port, java_port, gateway_port, sink_port):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", port))
    assert args.jar.is_file() and args.gateway.is_file()
    data = Path(tempfile.mkdtemp(prefix="compx574-recipe-create-003-"))
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
        "BASE_URL": "http://isolated-create-review.invalid",
        "RECIPE_EVENT_ADAPTER_KEY": adapter_key,
        "RECIPE_EVENT_ADAPTER_URL": f"http://localhost:{py_port}/internal/recipe-created",
        "CREATE_PARITY_BLOCK_FILE": str(data / "block-python-create"),
    }
    if args.production_validation:
        env.update(PRODUCTION="true", TESTING="false")
        (data / ".secret").write_text(SECRET)
    env.pop("POSTGRES_URL_OVERRIDE", None)
    database = str(data / "mealie.db")
    if args.engine == "postgres":
        database = "compx574_recipe_create_003_" + uuid4().hex[:12]
        assert database.startswith("compx574_recipe_create_003_")
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
            return {k: canonical(v, context, k) for k, v in value.items()}
        if isinstance(value, list):
            return [canonical(v, context, key) for v in value]
        if isinstance(value, (datetime, UUID)):
            value = str(value)
        if key == "id" and isinstance(value, int):
            assert value > 0
            return "<generated-integer-id>"
        if not isinstance(value, str):
            return value
        for field in ("user_id", "group_id", "household_id"):
            uid = context[field]
            value = value.replace(uid, "<" + field + ">").replace(UUID(uid).hex, "<" + field + ">")
        value = value.replace("/g/" + context["group_slug"] + "/", "/g/<group_slug>/")
        if key in ("dateUpdated", "createdAt", "updatedAt", "created_at", "update_at", "date_updated", "timestamp"):
            return "<generated-timestamp>"
        try:
            uid = UUID(value)
            assert uid.version == 4
            return "<generated-uuid>"
        except ValueError:
            return value

    def notifications_for(context: dict[str, Any], slug: str) -> list[dict[str, Any]]:
        return [
            item
            for item in report["notifications"]
            if item["path"] == "/" + context["label"]
            and json.loads(unquote_plus(str(item["body"].get("document_data", "{}")))).get("recipeSlug") == slug
        ]

    def describe(context: dict[str, Any], slug: str, before: datetime, after: datetime) -> dict[str, Any]:
        recipe = api("GET", "/api/recipes/" + quote(slug, safe=""), context["token"])
        assert recipe["userId"] == context["user_id"]
        assert recipe["groupId"] == context["group_id"]
        assert recipe["householdId"] == context["household_id"]
        assert UUID(recipe["id"]).version == 4
        created = datetime.fromisoformat(recipe["createdAt"].replace("Z", "+00:00"))
        assert before <= created <= after + timedelta(seconds=1)
        assert recipe["dateAdded"] == created.date().isoformat()
        timeline = api(
            "GET", "/api/recipes/timeline/events", context["token"], params={"queryFilter": "recipe_id=" + recipe["id"]}
        )
        assert len(timeline["items"]) == 1
        assert timeline["items"][0]["timestamp"] == recipe["createdAt"]
        assert timeline["items"][0]["userId"] == context["user_id"]
        with db.connect() as connection:
            rows = {
                table: [
                    dict(row)
                    for row in connection.execute(
                        text(f"SELECT * FROM {table} WHERE recipe_id=:id"), {"id": scalar(recipe["id"])}
                    ).mappings()
                ]
                for table in (
                    "recipes_ingredients",
                    "recipe_instructions",
                    "recipe_nutrition",
                    "recipe_settings",
                    "recipe_timeline_events",
                )
            }
            assert all(len(values) == 1 for values in rows.values())
            stored = dict(
                connection.execute(text("SELECT * FROM recipes WHERE id=:id"), {"id": scalar(recipe["id"])})
                .mappings()
                .one()
            )
        for _ in range(100 if context.get("expects_notification", True) else 1):
            notifications = notifications_for(context, slug)
            if notifications:
                break
            time.sleep(0.05)
        assert len(notifications) == int(context.get("expects_notification", True)), (
            "notification count",
            context["label"],
            slug,
            notifications,
        )
        if notifications:
            assert notifications[0]["body"]["event_type"] == "recipe_created"
        return {
            "recipe": recipe,
            "timeline_items": timeline["items"],
            "stored_recipe": stored,
            "related_rows": rows,
            "notifications": notifications,
        }

    def probe(
        name: str,
        contexts: list[dict[str, Any]],
        *,
        mode: str = "bearer",
        path: str = "/api/recipes",
        expected: int | None = None,
        **kwargs: Any,
    ) -> None:
        case: dict[str, Any] = {"name": name, "request": dict(kwargs), "mode": mode, "path": path}
        report["cases"].append(case)
        try:
            for context in contexts:
                headers = dict(kwargs.get("headers", {}))
                request_args = {k: v for k, v in kwargs.items() if k != "headers"}
                token = context["token"]
                if mode == "cookie":
                    request_args["cookies"] = {"mealie.access_token": token}
                elif mode == "api":
                    token = context["api_token"]
                elif mode == "no-auth":
                    token = ""
                elif mode == "invalid":
                    token = "invalid"
                elif mode == "expired":
                    token = jwt.encode(
                        {"sub": context["user_id"], "exp": datetime.now(UTC) - timedelta(seconds=10)},
                        SECRET,
                        algorithm="HS256",
                    )
                elif mode in ("future-iat", "future-nbf", "wrong-signature"):
                    payload = {"sub": context["user_id"], "exp": datetime.now(UTC) + timedelta(minutes=10)}
                    if mode == "future-iat":
                        payload["iat"] = datetime.now(UTC) + timedelta(minutes=5)
                    if mode == "future-nbf":
                        payload["nbf"] = datetime.now(UTC) + timedelta(minutes=5)
                    token = jwt.encode(
                        payload, "wrong-test-key" if mode == "wrong-signature" else SECRET, algorithm="HS256"
                    )
                elif mode == "unregistered-api":
                    token = jwt.encode(
                        {"id": context["user_id"], "long_token": True, "integration_id": "unregistered"},
                        SECRET,
                        algorithm="HS256",
                    )
                if mode != "cookie" and token:
                    headers["Authorization"] = "Bearer " + token
                before = datetime.now(UTC)
                response = requests.post(context["origin"] + path, headers=headers, timeout=30, **request_args)
                after = datetime.now(UTC)
                captured = capture(response)
                case[context["label"]] = captured
                if expected is not None:
                    assert response.status_code == expected, (context["label"], captured)
                if context["label"] == "gateway":
                    assert captured["headers"].get("x-mealie-backend") == "java"
                if response.status_code == 201:
                    captured["created"] = describe(context, response.json(), before, after)
            reference = contexts[0]
            oracle = case[reference["label"]]
            for context in contexts[1:]:
                observed = case[context["label"]]
                assert observed["status"] == oracle["status"]
                assert observed["body"] == oracle["body"], ("body", observed["body"], oracle["body"])
                for header in ("content-type", "www-authenticate"):
                    assert observed["headers"].get(header) == oracle["headers"].get(header), header
                if oracle.get("created"):
                    left = canonical(oracle["created"], reference)
                    right = canonical(observed["created"], context)
                    # Sink routing distinguishes isolated test groups; its path is not part of the event payload.
                    for value in (left, right):
                        for notification in value["notifications"]:
                            notification["path"] = "/<context>"
                    assert left == right, ("created state", left, right)
            case["result"] = "passed"
        except Exception:
            case["result"] = "failed"
            case["failure"] = traceback.format_exc()
            report["failures"].append({"case": name, "failure": case["failure"]})
        print(name, case["result"], flush=True)  # noqa: T201 - CLI test progress

    try:
        # The local wrapper blocks only Python creation on demand, while keeping its event adapter available.
        wrapper = data / "python_server.py"
        wrapper.write_text(
            "import os,sys\nfrom pathlib import Path\nsys.path.insert(0," + repr(str(REPO)) + ")\n"
            "from mealie.app import app\nfrom fastapi.responses import JSONResponse\n"
            "@app.middleware('http')\nasync def block_creation(request,call_next):\n"
            " if request.method=='POST' and request.url.path=='/api/recipes':\n"
            "  if Path(os.environ['CREATE_PARITY_BLOCK_FILE']).exists():\n"
            "   return JSONResponse({'detail':'Python creation disabled for independence test'},status_code=503)\n"
            " return await call_next(request)\nimport uvicorn\n"
            "uvicorn.run(app,host='127.0.0.1',port=int(os.environ['API_PORT']),log_level='info')\n"
        )
        py = start("python", ["uv", "run", "--frozen", "python", str(wrapper)])
        wait(python, "/api/app/about", py)
        root_token = login("changeme@example.com", "MyPassword")
        contexts = []
        for label, origin in (("python", python), ("direct", java), ("gateway", gateway)):
            group = api("POST", "/api/admin/groups", root_token, json={"name": "Create " + label})
            home = api(
                "POST", "/api/admin/households", root_token, json={"name": "Create " + label, "groupId": group["id"]}
            )
            email = "create-" + label + "@example.com"
            api(
                "POST",
                "/api/admin/users",
                root_token,
                json={
                    "fullName": email,
                    "username": "create-" + label,
                    "email": email,
                    "password": "Create-test-password1",
                    "group": group["name"],
                    "household": home["name"],
                    "admin": False,
                    "canManage": False,
                    "canManageHousehold": False,
                    "canOrganize": False,
                    "tokens": [],
                },
            )
            token = login(email, "Create-test-password1")
            owner = api("GET", "/api/users/self", token)
            notifier = api(
                "POST",
                "/api/households/events/notifications",
                token,
                json={"name": label, "appriseUrl": f"json://localhost:{sink_port}/" + label},
            )
            api(
                "PUT",
                "/api/households/events/notifications/" + notifier["id"],
                token,
                json={**notifier, "options": {**notifier["options"], "recipeCreated": True}},
            )
            api_token = jwt.encode(
                {"id": owner["id"], "long_token": True, "integration_id": "create-integration"},
                SECRET,
                algorithm="HS256",
            )
            execute(
                "INSERT INTO long_live_tokens (name, token, user_id, created_at) VALUES (:name,:token,:user,:at)",
                {
                    "name": label,
                    "token": api_token,
                    "user": scalar(owner["id"]),
                    "at": datetime.now(UTC).replace(tzinfo=None),
                },
            )
            contexts.append(
                {
                    "label": label,
                    "origin": origin,
                    "token": token,
                    "api_token": api_token,
                    "email": email,
                    "user_id": owner["id"],
                    "group_id": group["id"],
                    "household_id": home["id"],
                    "group_slug": group["slug"],
                }
            )
        report["fixture_owners"] = [{k: v for k, v in c.items() if k not in ("token", "api_token")} for c in contexts]
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
        ordinary = [
            ("ordinary", {"name": "Manual Recipe"}, 201),
            ("duplicate", {"name": "Manual Recipe"}, 201),
            (
                "ignored extra fields",
                {
                    "name": "Extra Fields",
                    "slug": "forged",
                    "userId": str(uuid4()),
                    "groupId": str(uuid4()),
                    "householdId": str(uuid4()),
                    "settings": {"public": True},
                    "recipeIngredient": ["forged"],
                    "rating": 5,
                },
                201,
            ),
            ("unicode", {"name": "Café 重庆 & Soup"}, 201),
            ("HTML entities", {"name": "Cr&egrave;me &#38; &#x41; &apos; Pie"}, 201),
            ("quotes and numeric commas", {"name": 'Chef\'s "Soup" 1,000'}, 201),
            ("long slug", {"name": "A" * 500}, 201),
            ("long slug collision", {"name": "A" * 500}, 400),
            ("empty", {"name": ""}, 500),
            ("whitespace", {"name": "   "}, 400),
            ("punctuation", {"name": "---"}, 400),
            ("missing name", {}, 422),
            ("null name", {"name": None}, 422),
            ("integer name", {"name": 42}, 422),
            ("boolean name", {"name": True}, 422),
            ("decimal name", {"name": 1.0}, 422),
            ("scientific large name", {"name": 1e20}, 422),
            ("scientific small name", {"name": 1e-5}, 422),
            ("fixed large name", {"name": 1e7}, 422),
            ("fixed small name", {"name": 1e-4}, 422),
            ("negative zero name", {"name": -0.0}, 422),
            ("array name", {"name": []}, 422),
            ("object name", {"name": {}}, 422),
            ("array body", [], 422),
            ("string body", "Manual", 422),
            ("null body", None, 422),
        ]
        for name, body, expected in ordinary:
            probe(name, contexts, json=body, expected=expected)
        for i in range(11):
            probe(
                "duplicate retry " + str(i),
                contexts,
                json={"name": "Retry Exhaustion"},
                expected=201 if i < 10 else 400,
            )
        for mode in (
            "no-auth",
            "invalid",
            "expired",
            "unregistered-api",
            "future-iat",
            "future-nbf",
            "wrong-signature",
        ):
            probe(mode + " authentication", contexts, mode=mode, json={"name": "Bad Auth"}, expected=401)
        probe("cookie authentication", contexts, mode="cookie", json={"name": "Cookie Recipe"}, expected=201)
        probe("registered API token integration", contexts, mode="api", json={"name": "API Recipe"}, expected=201)
        for raw in (
            '{"name":',
            '{"name":"x",}',
            '{"name" "x"}',
            '{"name":"x}',
            '{"name":"\\q"}',
            '{"name":"\\uQQQQ"}',
            "[]x",
            " ",
            "[1,]",
            "{",
            "null",
        ):
            probe(
                "JSON syntax " + repr(raw),
                contexts,
                data=raw,
                headers={"Content-Type": "application/json"},
                expected=422,
            )
        probe(
            "syntax failure before invalid auth",
            contexts,
            mode="invalid",
            data='{"name":',
            headers={"Content-Type": "application/json"},
            expected=422,
        )
        probe("field validation after invalid auth", contexts, mode="invalid", json={}, expected=401)
        probe("empty body", contexts, data="", headers={"Content-Type": "application/json"}, expected=422)
        probe(
            "non-JSON content type",
            contexts,
            data='{"name":"Bytes"}',
            headers={"Content-Type": "text/plain"},
            expected=422,
        )
        probe(
            "JSON vendor type",
            contexts,
            data='{"name":"Vendor JSON"}',
            headers={"Content-Type": "application/vnd.api+json"},
            expected=201,
        )
        probe(
            "UTF16 JSON",
            contexts,
            data='{"name":"UTF16 Recipe"}'.encode("utf-16"),
            headers={"Content-Type": "application/json"},
            expected=201,
        )
        for locale in ("fr-FR", "zh-CN", "fr-FR,en-US;q=0.5", "unknown-locale"):
            probe(
                "locale " + locale,
                contexts,
                json={"name": "Locale " + locale},
                headers={"Accept-Language": locale},
                expected=201,
            )
        for context in contexts:
            execute(
                "UPDATE household_preferences SET recipe_public=:yes, recipe_show_nutrition=:yes, "
                "recipe_show_assets=:yes, "
                "recipe_landscape_view=:yes, recipe_disable_comments=:yes WHERE household_id=:home",
                {"yes": True, "home": scalar(context["household_id"])},
            )
        probe("household default settings", contexts, json={"name": "Household Preferences"}, expected=201)
        # Simultaneous duplicate requests must create the same ten-retry-compatible slug set and complete side effects.
        concurrent_states = []
        for context in contexts:
            before = datetime.now(UTC)

            def simultaneous(_: int, owner: dict[str, Any] = context) -> requests.Response:
                return requests.post(
                    owner["origin"] + "/api/recipes",
                    json={"name": "Concurrent Manual"},
                    headers={"Authorization": "Bearer " + owner["token"]},
                    timeout=30,
                )

            with ThreadPoolExecutor(max_workers=4) as executor:
                responses = list(executor.map(simultaneous, range(4)))
            assert all(response.status_code == 201 for response in responses), [capture(r) for r in responses]
            slugs = sorted(response.json() for response in responses)
            assert slugs == ["concurrent-manual", "concurrent-manual-1", "concurrent-manual-2", "concurrent-manual-3"]
            states = [describe(context, slug, before, datetime.now(UTC)) for slug in slugs]
            concurrent_states.append(
                {"context": context["label"], "responses": [capture(r) for r in responses], "states": states}
            )
        report["cases"].append(
            {
                "name": "concurrent duplicate names with complete side effects",
                "contexts": concurrent_states,
                "result": "passed",
            }
        )

        # Create a new household that never had a preferences row; do not delete an existing row to test fallback.
        fallback_contexts = []
        for context in contexts:
            home_id = str(uuid4())
            home_name = "No Preferences " + context["label"]
            execute(
                "INSERT INTO households (id,name,slug,group_id,created_at,update_at) "
                "VALUES (:id,:name,:slug,:group,:at,:at)",
                {
                    "id": scalar(home_id),
                    "name": home_name,
                    "slug": "no-preferences-" + context["label"],
                    "group": scalar(context["group_id"]),
                    "at": datetime.now(UTC).replace(tzinfo=None),
                },
            )
            email = "fallback-" + context["label"] + "@example.com"
            api(
                "POST",
                "/api/admin/users",
                root_token,
                json={
                    "fullName": email,
                    "username": "fallback-" + context["label"],
                    "email": email,
                    "password": "Create-test-password1",
                    "group": "Create " + context["label"],
                    "household": home_name,
                    "admin": False,
                    "tokens": [],
                },
            )
            token = login(email, "Create-test-password1")
            owner = api("GET", "/api/users/self", token)
            notifier = api(
                "POST",
                "/api/households/events/notifications",
                token,
                json={"name": "fallback", "appriseUrl": f"json://localhost:{sink_port}/" + context["label"]},
            )
            api(
                "PUT",
                "/api/households/events/notifications/" + notifier["id"],
                token,
                json={**notifier, "options": {**notifier["options"], "recipeCreated": True}},
            )
            fallback_contexts.append({**context, "token": token, "user_id": owner["id"], "household_id": home_id})
        probe(
            "missing household preferences fallback",
            fallback_contexts,
            json={"name": "No Preferences Recipe"},
            expected=201,
        )
        for context in contexts[1:]:
            response = requests.get(
                context["origin"] + "/api/recipes/no-preferences-recipe",
                headers={"Authorization": "Bearer " + context["token"]},
                timeout=30,
            )
            assert response.status_code == 200
            assert response.json()["settings"]["disableComments"] is True
            report["cases"].append(
                {
                    "name": context["label"] + " private same-group household remains visible",
                    "actual": capture(response),
                    "result": "passed",
                }
            )

        # Disabled and opted-out destinations must not receive creation events.
        for context in contexts:
            for kind in ("disabled", "opted-out"):
                notifier = api(
                    "POST",
                    "/api/households/events/notifications",
                    context["token"],
                    json={"name": kind, "appriseUrl": f"json://localhost:{sink_port}/" + kind + "-" + context["label"]},
                )
                api(
                    "PUT",
                    "/api/households/events/notifications/" + notifier["id"],
                    context["token"],
                    json={
                        **notifier,
                        "enabled": kind != "disabled",
                        "options": {**notifier["options"], "recipeCreated": kind != "opted-out"},
                    },
                )
        probe(
            "notification enabled and subscribed household selection",
            contexts,
            json={"name": "Notifier Selection"},
            expected=201,
        )
        assert not any(n["path"].startswith(("/disabled-", "/opted-out-")) for n in report["notifications"])

        # Verify existing Java endpoints and gateway boundaries against the current Python implementation.
        for context in contexts[1:]:
            for path in (
                "/api/recipes?perPage=2&orderBy=name:asc",
                "/api/recipes/manual-recipe",
                "/api/users/self",
                "/api/groups/self",
            ):
                py_value = api("GET", path, context["token"])
                actual_response = requests.get(
                    context["origin"] + path, headers={"Authorization": "Bearer " + context["token"]}, timeout=30
                )
                entry = {
                    "name": context["label"] + " regression " + path,
                    "python": py_value,
                    "actual": capture(actual_response),
                }
                report["cases"].append(entry)
                assert actual_response.status_code == 200
                comparison = actual_response.json()
                if path == "/api/users/self":
                    # Existing baseline Java omits insignificant trailing fractional zeros. Preserve raw evidence.
                    py_value = json.loads(json.dumps(py_value))
                    comparison = json.loads(json.dumps(comparison))
                    for profile in (py_value, comparison):
                        for token_entry in profile["tokens"]:
                            token_entry["createdAt"] = datetime.fromisoformat(token_entry["createdAt"]).isoformat(
                                timespec="microseconds"
                            )
                    entry["comparison_note"] = (
                        "Existing baseline user-profile token timestamps compared as exact instants."
                    )
                assert comparison == py_value
                if context["label"] == "gateway":
                    assert actual_response.headers.get("X-Mealie-Backend") == "java"
                entry["result"] = "passed"
            token = login(context["email"], "Create-test-password1", context["origin"])
            assert token
        foreign = contexts[0]
        for context in contexts[1:]:
            response = requests.get(
                context["origin"] + "/api/recipes/" + api("GET", "/api/recipes/manual-recipe", foreign["token"])["id"],
                headers={"Authorization": "Bearer " + context["token"]},
                timeout=30,
            )
            assert response.status_code == 404
            report["cases"].append(
                {
                    "name": context["label"] + " cross-group detail restricted",
                    "actual": capture(response),
                    "result": "passed",
                }
            )
        for method, path, kwargs in (
            ("POST", "/api/recipes/create/url", {"json": {}}),
            ("PUT", "/api/recipes/does-not-exist", {"json": {}}),
            ("PATCH", "/api/recipes/does-not-exist", {"json": {}}),
            ("DELETE", "/api/recipes/does-not-exist", {}),
            ("POST", "/api/recipes/manual-recipe/duplicate", {"json": {}}),
        ):
            response = requests.request(method, gateway + path, timeout=30, **kwargs)
            assert response.headers.get("X-Mealie-Backend") == "python", (method, path, response.headers)
            report["cases"].append(
                {"name": "unmigrated " + method + " " + path, "actual": capture(response), "result": "passed"}
            )
        blocked = requests.post(gateway + "/internal/recipe-created", json={}, timeout=30)
        assert blocked.status_code == 404
        unsigned = requests.post(python + "/internal/recipe-created", json={}, timeout=30)
        assert unsigned.status_code == 401
        report["cases"].append(
            {
                "name": "adapter public gateway blocked and unsigned direct call rejected",
                "gateway": capture(blocked),
                "unsigned": capture(unsigned),
                "result": "passed",
            }
        )
        # Verify signed adapter auth, ownership, replay protection and absence of database writes.
        quiet_slug = api("POST", "/api/recipes", root_token, json={"name": "Quiet Adapter Fixture"})
        quiet_recipe = api("GET", "/api/recipes/" + quiet_slug, root_token)
        payload = {
            "eventId": str(uuid4()),
            "timestamp": datetime.now(UTC).isoformat(),
            "recipeId": quiet_recipe["id"],
            "userId": quiet_recipe["userId"],
            "groupId": quiet_recipe["groupId"],
            "householdId": quiet_recipe["householdId"],
            "locale": "en-US",
            "integrationId": "adapter-parity",
        }

        def adapter_call(
            values: dict[str, Any], signed_at: str | None = None, bad_signature: bool = False
        ) -> requests.Response:
            body = json.dumps(values).encode()
            stamp = signed_at or str(int(time.time()))
            signature = hmac.new(adapter_key.encode(), stamp.encode() + b"." + body, hashlib.sha256).hexdigest()
            return requests.post(
                python + "/internal/recipe-created",
                data=body,
                timeout=30,
                headers={
                    "Content-Type": "application/json",
                    "X-Mealie-Event-Time": stamp,
                    "X-Mealie-Event-Signature": "invalid" if bad_signature else signature,
                },
            )

        with db.connect() as connection:
            before_counts = [
                connection.execute(text("SELECT count(*) FROM " + table)).scalar()
                for table in ("recipes", "recipe_timeline_events")
            ]
        checks = [
            adapter_call(payload, bad_signature=True),
            adapter_call(payload, str(int(time.time()) - 120)),
            adapter_call({**payload, "userId": contexts[0]["user_id"]}),
            adapter_call(payload),
            adapter_call(payload),
        ]
        assert [r.status_code for r in checks] == [401, 401, 404, 204, 409]
        with db.connect() as connection:
            after_counts = [
                connection.execute(text("SELECT count(*) FROM " + table)).scalar()
                for table in ("recipes", "recipe_timeline_events")
            ]
        assert after_counts == before_counts
        report["cases"].append(
            {
                "name": "signed adapter authentication ownership replay and read-only behavior",
                "responses": [capture(r) for r in checks],
                "database_counts_unchanged": True,
                "result": "passed",
            }
        )
        (data / "block-python-create").write_text("Creation disabled; event adapter remains enabled.\n")
        reference = requests.post(
            python + "/api/recipes",
            headers={"Authorization": "Bearer " + contexts[0]["token"]},
            json={"name": "Java Independence"},
            timeout=30,
        )
        assert reference.status_code == 503
        for context in contexts[1:]:
            probe(
                "native creation with Python creation disabled " + context["label"],
                [context],
                json={"name": "Java Independence"},
                expected=201,
            )
        report["independence_proof"] = {
            "python_create_response": capture(reference),
            "java_creation_succeeded_directly_and_through_gateway": True,
            "timeline_and_real_notifications_verified": True,
            "event_adapter_was_enabled": True,
        }
        report["result"] = "passed" if not report["failures"] else "failed"
        report["passed_cases"] = sum(c.get("result") == "passed" for c in report["cases"])
        report["case_count"] = len(report["cases"])
        report["ui_review"] = {
            "gateway": gateway,
            "email": contexts[2]["email"],
            "password": "Create-test-password1",
            "recipe_slug": "manual-recipe",
            "fixture_database": database,
        }
        if args.keep_serving:
            # Keep the independence guard active; gateway creation already uses Java.
            report["ui_review"]["python_create_guard_active"] = True
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
