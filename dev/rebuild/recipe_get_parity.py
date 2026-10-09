"""Exercise recipe reads on real Python/Java HTTP servers and optionally the actual Caddyfile.

Run from the repository with uv, after task java:package. Fixtures live in a disposable database.
SQLite uses a fresh temporary database initialized by Python/Alembic; Postgres requires a disposable database
named compx574_recipe_parity (never use the application's database). No credentials appear in reports.
"""

import argparse
import contextlib
import json
import os
import signal
import sqlite3
import subprocess
import tempfile
import time
from datetime import UTC, datetime, timedelta
from functools import partial
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import jwt
import requests
from sqlalchemy import create_engine, text

REPO = Path(__file__).resolve().parents[2]
SECRET = "shh-secret-test-key"


def wait(url: str, process: subprocess.Popen) -> dict[str, Any]:
    for _ in range(100):
        if process.poll() is not None:
            raise RuntimeError(f"Service exited with code {process.returncode}; inspect saved service logs")
        with contextlib.suppress(requests.RequestException):
            response = requests.get(url, timeout=1)
            if response.status_code == 200:
                return response.json()
        time.sleep(0.2)
    raise RuntimeError(f"Service did not become ready: {url}")


def auth(token: str) -> dict[str, str]:
    return {"Authorization": "Bearer " + token}


def stop_processes(processes: list[subprocess.Popen]) -> None:
    for process in reversed(processes):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
    for process in reversed(processes):
        with contextlib.suppress(subprocess.TimeoutExpired):
            process.wait(timeout=10)
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def compare_case(
    python: str,
    java: str,
    gateway: str | None,
    report: dict[str, Any],
    name: str,
    path: str,
    token: str | None = None,
    headers: dict[str, str] | None = None,
    cookies: dict[str, str] | None = None,
    method: str = "GET",
    normalize: Any = None,
    expected: int | None = None,
    backend: str = "java",
) -> None:
    request_headers = {**(auth(token) if token else {}), **(headers or {})}
    oracle = requests.request(
        method, python + path, headers=request_headers, cookies=cookies, timeout=20, allow_redirects=False
    )
    targets = {"java": java}
    if gateway:
        targets["gateway"] = gateway
    if backend == "python":
        targets = {"gateway": gateway} if gateway else {}

    def captured(response: requests.Response) -> dict[str, Any]:
        return {
            "status": response.status_code,
            "headers": {
                k.lower(): v
                for k, v in response.headers.items()
                if k.lower() in ("last-modified", "cache-control", "www-authenticate", "x-mealie-backend")
            },
            "body": response.json() if response.content else None,
        }

    entry = {"name": name, "method": method, "path": path, "python": captured(oracle), "targets": {}}
    report["cases"].append(entry)
    if expected is not None:
        assert oracle.status_code == expected, (name, oracle.status_code, oracle.text)
    failures = []
    for target, base in targets.items():
        response = requests.request(
            method, base + path, headers=request_headers, cookies=cookies, timeout=20, allow_redirects=False
        )
        actual = captured(response)
        entry["targets"][target] = actual
        expected_body = oracle.json() if oracle.content else None
        actual_body = actual["body"]
        if normalize:
            expected_body, actual_body = normalize(expected_body), normalize(json.loads(json.dumps(actual_body)))
        if response.status_code != oracle.status_code or actual_body != expected_body:
            failures.append(f"{target} status/body differs")
        for key in ("last-modified", "cache-control", "www-authenticate"):
            if actual["headers"].get(key) != entry["python"]["headers"].get(key):
                failures.append(f"{target} {key} differs")
        if target == "gateway" and actual["headers"].get("x-mealie-backend") != backend:
            failures.append(f"gateway did not use {backend}")
    entry["failures"] = failures
    entry["passed"] = not failures
    print(  # noqa: T201 - CLI output records actual HTTP case results.
        f"{'PASS' if entry['passed'] else 'FAIL'} {name}: Python={oracle.status_code}, targets={list(targets)}",
        flush=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["sqlite", "postgres"], default="sqlite")
    parser.add_argument("--gateway", type=Path, help="Path to a Caddy binary; also test gateway routing")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "started_at_utc": datetime.now(UTC).isoformat(),
        "engine": args.engine,
        "cases": [],
        "result": "in_progress",
    }
    processes = []
    logs = []
    temporary = tempfile.TemporaryDirectory(prefix="compx574-recipe-get-")
    data = Path(temporary.name)
    env = {
        **os.environ,
        "UV_FROZEN": "1",
        "PRODUCTION": "false",
        "TESTING": "true",
        "DATA_DIR": str(data),
        "API_PORT": "9200",
        "JAVA_API_PORT": "9210",
        "DB_ENGINE": args.engine,
        "BASE_URL": "http://localhost:3000",
        "ALLOW_SIGNUP": "false",
    }
    env.pop("POSTGRES_URL_OVERRIDE", None)
    if args.engine == "sqlite":
        # Python/Alembic initializes the existing schema in this fresh disposable database.
        sqlite3.connect(data / "mealie.db").close()
        db = create_engine("sqlite:///" + str(data / "mealie.db"))
    else:
        # The caller creates this isolated database; reject the normal application DB name.
        env.update(
            POSTGRES_SERVER="localhost",
            POSTGRES_PORT="55432",
            POSTGRES_DB="compx574_recipe_parity",
            POSTGRES_USER="mealie",
            POSTGRES_PASSWORD="mealie",
        )
        db = create_engine("postgresql+psycopg2://mealie:mealie@localhost:55432/compx574_recipe_parity")

    def start(name: str, command: list[str], service_env: dict[str, str] = env) -> subprocess.Popen:
        log = args.report.with_suffix("." + name + ".log").open("w")
        logs.append(log)
        process = subprocess.Popen(
            command, cwd=REPO, env=service_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
        )
        processes.append(process)
        return process

    python = "http://localhost:9200"
    java = "http://localhost:9210"
    gateway = "http://localhost:9280"

    def api(method: str, path: str, token: str | None = None, body: Any = None) -> Any:
        response = requests.request(
            method, python + path, headers=auth(token) if token else None, json=body, timeout=20
        )
        assert response.status_code < 400, (method, path, response.status_code, response.text)
        return response.json()

    def login(email: str, password: str) -> str:
        response = requests.post(python + "/api/auth/token", data={"username": email, "password": password}, timeout=20)
        assert response.status_code == 200, response.text
        return response.json()["access_token"]

    def scalar(value: str) -> str | UUID:
        return UUID(value).hex if args.engine == "sqlite" else UUID(value)

    def execute(statement: str, values: dict[str, Any]) -> None:
        with db.begin() as connection:
            connection.execute(text(statement), values)

    def recipe(name: str, token: str) -> dict[str, Any]:
        slug = api("POST", "/api/recipes", token, {"name": name})
        return api("GET", "/api/recipes/" + slug, token)

    probe = partial(compare_case, python, java, gateway if args.gateway else None, report)

    try:
        py = start("python", ["uv", "run", "--frozen", "python", "mealie/app.py"])
        wait(python + "/api/app/about", py)
        root_token = login("changeme@example.com", "MyPassword")
        group = api("GET", "/api/groups/self", root_token)
        household = api(
            "POST", "/api/admin/households", root_token, {"name": "Parity Private Household", "groupId": group["id"]}
        )

        def user(email: str, group_name: str, household_name: str, admin: bool = False) -> tuple[str, dict[str, Any]]:
            email = email.replace("@", "-" + data.name[-8:] + "@")
            api(
                "POST",
                "/api/admin/users",
                root_token,
                {
                    "fullName": email,
                    "username": email.split("@")[0],
                    "email": email,
                    "password": "Parity-test-password1",
                    "group": group_name,
                    "household": household_name,
                    "admin": admin,
                    "tokens": [],
                },
            )
            token = login(email, "Parity-test-password1")
            return token, api("GET", "/api/users/self", token)

        member_token, member = user("parity-member@example.com", group["name"], household["name"])
        foreign_group = api("POST", "/api/admin/groups", root_token, {"name": "Parity Other Group"})
        foreign_household = api(
            "POST",
            "/api/admin/households",
            root_token,
            {"name": "Parity Foreign Household", "groupId": foreign_group["id"]},
        )
        foreign_token, _ = user("parity-foreign@example.com", foreign_group["name"], foreign_household["name"], True)
        other_token, _ = user("parity-other@example.com", foreign_group["name"], foreign_household["name"])
        reader_token, _ = user(
            "parity-reader@example.com", group["name"], api("GET", "/api/households/self", root_token)["name"]
        )
        execute(
            "UPDATE household_preferences SET private_household = :private WHERE household_id = :id",
            {"private": True, "id": scalar(household["id"])},
        )
        child = recipe("Parity Referenced Recipe", member_token)
        rich = recipe("Parity Complete Recipe", member_token)
        label = api("POST", "/api/groups/labels", member_token, {"name": "Parity Label", "color": "#112233"})
        alternative = api("POST", "/api/foods", root_token, {"name": "tofu", "pluralName": "tofu pieces"})
        food = api(
            "POST",
            "/api/foods",
            root_token,
            {
                "name": "egg",
                "pluralName": "eggs",
                "description": "A fixture food",
                "labelId": label["id"],
                "extras": {"source": "fixture"},
                "aliases": [{"name": "egg alias"}],
                "householdsWithIngredientFood": [household["slug"]],
                "substitutions": [{"substituteFoodId": alternative["id"], "note": " substitute "}],
            },
        )
        unit = api(
            "POST",
            "/api/units",
            root_token,
            {
                "name": "cup",
                "pluralName": "cups",
                "description": "Fixture unit",
                "fraction": True,
                "abbreviation": "c",
                "pluralAbbreviation": "cs",
                "useAbbreviation": False,
                "standardUnit": "milliliter",
                "standardQuantity": 250,
                "aliases": [{"name": "cup alias"}],
            },
        )
        ref, note_ref = str(uuid4()), str(uuid4())
        rich.update(
            description="Full nested recipe response",
            recipeServings=8,
            recipeYieldQuantity=6,
            recipeYield="bowls",
            totalTimeSeconds=3600,
            prepTimeSeconds=900,
            performTimeSeconds=2700,
            recipeCategory=[{"name": "Parity Category"}],
            tags=[{"name": "Parity Tag"}],
            tools=[{"name": "Parity Tool", "householdsWithTool": [household["slug"]]}],
            recipeIngredient=[
                {
                    "quantity": 2.75,
                    "unit": unit,
                    "food": food,
                    "note": "whisked",
                    "title": "Ingredients",
                    "originalText": "2 3/4 cups egg",
                    "referenceId": ref,
                    "substitutions": [{"substituteFoodId": alternative["id"], "note": " alternative "}],
                },
                {"quantity": 0, "food": alternative, "note": "optional"},
                {"quantity": None, "note": "to taste"},
                {"quantity": 1, "referencedRecipe": child, "note": "prepared"},
            ],
            recipeInstructions=[
                {
                    "text": "Mix ingredients",
                    "title": "Mix",
                    "summary": "First",
                    "ingredientReferences": [{"referenceId": ref}],
                    "noteReferences": [{"referenceId": note_ref}],
                },
                {"text": "Serve", "summary": "", "title": ""},
            ],
            nutrition={"calories": "120", "proteinContent": "9.5"},
            settings={
                "public": False,
                "showNutrition": True,
                "showAssets": True,
                "landscapeView": False,
                "disableComments": False,
                "locked": True,
            },
            assets=[{"name": "Fixture attachment", "icon": "mdi-file", "fileName": "fixture.txt"}],
            notes=[{"title": "Storage", "text": "Keep chilled", "referenceId": note_ref}],
            extras={"source": "parity-fixture"},
            orgURL="https://example.com/fixture",
        )
        api("PUT", "/api/recipes/" + rich["slug"], member_token, rich)
        # The comment API is outside this migration; insert test data only into the disposable DB.
        execute(
            "INSERT INTO recipe_comments (id, recipe_id, user_id, text, created_at, update_at) "
            "VALUES (:id, :recipe, :user, :text, :at, :at)",
            {
                "id": scalar(str(uuid4())),
                "recipe": scalar(rich["id"]),
                "user": scalar(member["id"]),
                "text": "Fixture comment",
                "at": datetime(2026, 1, 2, 3, 4, 5, 123456, tzinfo=UTC).replace(tzinfo=None),
            },
        )
        java_process = start(
            "java", ["java", "-jar", str(REPO / "backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar")]
        )
        health = wait(java + "/api/java/health", java_process)
        assert health["dbEngine"] == args.engine and health["database"]["connected"], health
        report["java_health"] = health
        report["java_command"] = ["java", "-jar", "backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar"]
        if args.gateway:
            gateway_config = data / "Caddyfile"
            gateway_config.write_text((REPO / "gateway/Caddyfile").read_text().replace(":8080 {", ":9280 {"))
            caddy_env = {**env, "PYTHON_UPSTREAM": "localhost:9200", "JAVA_UPSTREAM": "localhost:9210"}
            caddy = start(
                "gateway",
                [str(args.gateway), "run", "--config", str(gateway_config), "--adapter", "caddyfile"],
                caddy_env,
            )
            wait(gateway + "/api/app/about", caddy)
        path = "/api/recipes/" + rich["slug"]
        probe("complete recipe by slug", path, member_token, expected=200)
        probe("complete recipe by ID", "/api/recipes/" + rich["id"], member_token, expected=200)
        probe("compact ID", "/api/recipes/" + rich["id"].replace("-", ""), member_token, expected=200)
        probe("uppercase ID", "/api/recipes/" + rich["id"].upper(), member_token, expected=200)
        probe("URN ID", "/api/recipes/urn:uuid:" + rich["id"], member_token, expected=200)
        probe("same group private household admin", path, root_token, expected=200)
        probe("same group private household regular user", path, reader_token, expected=200)
        probe("other group regular user denied", path, other_token, expected=404)
        probe("other group admin denied by slug", path, foreign_token, expected=404)
        probe("other group admin denied by ID", "/api/recipes/" + rich["id"], foreign_token, expected=404)
        probe("missing slug", "/api/recipes/not-a-parity-recipe", member_token, expected=404)
        probe("missing ID", "/api/recipes/" + str(uuid4()), member_token, expected=404)
        probe("slug is case sensitive", "/api/recipes/" + rich["slug"].upper(), member_token, expected=404)
        probe("UUID-like slug remains a slug", "/api/recipes/1-1-1-1-1", member_token, expected=404)
        probe("missing authentication", path, expected=401)
        probe("malformed token", path, headers={"Authorization": "Bearer invalid-token"}, expected=401)
        probe("cookie authentication", path, cookies={"mealie.access_token": member_token}, expected=200)
        probe(
            "invalid bearer overrides valid cookie",
            path,
            headers={"Authorization": "Bearer invalid-token"},
            cookies={"mealie.access_token": member_token},
            expected=401,
        )
        now = datetime.now(UTC)
        expired = jwt.encode(
            {"sub": member["id"], "iat": now - timedelta(hours=2), "exp": now - timedelta(seconds=1)},
            SECRET,
            algorithm="HS256",
        )
        probe("expired token", path, expired, expected=401)
        forged = jwt.encode(
            {"sub": member["id"], "iat": now, "exp": now + timedelta(hours=1)}, "wrong-secret", algorithm="HS256"
        )
        probe("wrong signature", path, forged, expected=401)
        api_token = jwt.encode(
            {"id": member["id"], "long_token": "fixture", "iat": now, "exp": now + timedelta(hours=1)},
            SECRET,
            algorithm="HS256",
        )
        execute(
            "INSERT INTO long_live_tokens (name, token, user_id) VALUES (:name, :token, :user)",
            {"name": "fixture API token", "token": api_token, "user": scalar(member["id"])},
        )
        probe("registered API token", path, api_token, expected=200)
        execute("DELETE FROM long_live_tokens WHERE token = :token", {"token": api_token})
        probe("revoked API token", path, api_token, expected=401)
        execute(
            "UPDATE recipe_settings SET public = :public WHERE recipe_id = :id",
            {"public": True, "id": scalar(rich["id"])},
        )
        probe("public recipe still requires authentication on this route", path, expected=401)
        execute(
            "UPDATE recipe_settings SET public = :public WHERE recipe_id = :id",
            {"public": False, "id": scalar(rich["id"])},
        )
        execute(
            "UPDATE users SET tokens_valid_after = :at WHERE id = :id",
            {"at": datetime.now(UTC).replace(tzinfo=None) + timedelta(seconds=1), "id": scalar(member["id"])},
        )
        probe("password change revokes session token", path, member_token, expected=401)
        execute("UPDATE users SET tokens_valid_after = NULL WHERE id = :id", {"id": scalar(member["id"])})
        for locale in ("en-US", "fr-FR", "zh-CN", "unknown"):
            probe(
                "ingredient display locale " + locale,
                path,
                member_token,
                headers={"Accept-Language": locale},
                expected=200,
            )
        # Python generates UUIDs in each response for legacy missing references. Verify and normalize only these UUIDs.
        legacy = recipe("Parity Missing References", member_token)
        execute(
            "UPDATE recipes_ingredients SET reference_id = NULL WHERE recipe_id = :id", {"id": scalar(legacy["id"])}
        )

        def legacy_body(body: dict[str, Any]) -> dict[str, Any]:
            for ingredient in body["recipeIngredient"]:
                UUID(ingredient["referenceId"])
                ingredient["referenceId"] = "<generated UUID>"
            return body

        probe(
            "legacy missing ingredient reference",
            "/api/recipes/" + legacy["slug"],
            member_token,
            normalize=legacy_body,
            expected=200,
        )
        if args.gateway:
            for method, path in [
                ("GET", "/api/recipes"),
                ("GET", "/api/recipes/suggestions"),
                ("GET", "/api/recipes/exports"),
                ("GET", "/api/recipes/" + rich["slug"] + "/comments"),
                ("POST", "/api/recipes"),
                ("PUT", "/api/recipes/" + rich["slug"]),
                ("PATCH", "/api/recipes/" + rich["slug"]),
                ("DELETE", "/api/recipes/" + rich["slug"]),
                ("POST", "/api/auth/token"),
                ("POST", "/api/recipes/create/url"),
                ("HEAD", "/api/recipes/" + rich["slug"]),
                ("OPTIONS", "/api/recipes/" + rich["slug"]),
            ]:
                probe("unmigrated " + method + " " + path, path, method=method, backend="python")
        # Stop Python completely and prove that successful reads depend on Java and the DB alone.
        os.killpg(py.pid, signal.SIGTERM)
        py.wait(timeout=10)
        for _ in range(30):
            try:
                requests.get(python + "/api/app/about", timeout=0.2)
            except requests.ConnectionError:
                break
            time.sleep(0.1)
        else:
            raise AssertionError("Python test server is still reachable")
        independence = {"python_unreachable": True, "targets": {}}
        for target, base in ({"java": java, "gateway": gateway} if args.gateway else {"java": java}).items():
            response = requests.get(base + "/api/recipes/" + rich["slug"], headers=auth(member_token), timeout=10)
            independence["targets"][target] = {
                "status": response.status_code,
                "backend": response.headers.get("X-Mealie-Backend"),
                "body": response.json(),
            }
            assert response.status_code == 200 and response.json() == report["cases"][0]["python"]["body"], target
            if target == "gateway":
                assert response.headers.get("X-Mealie-Backend") == "java"
        report["python_stopped_independence_check"] = independence
        failures = [case["name"] for case in report["cases"] if not case["passed"]]
        report["failures"] = failures
        report["result"] = "passed" if not failures else "failed"
        report["cases_count"] = len(report["cases"])
        assert not failures, failures
    except Exception as error:
        report["result"] = "failed"
        report["error"] = str(error)
        raise
    finally:
        report["ended_at_utc"] = datetime.now(UTC).isoformat()
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        stop_processes(processes)
        for log in logs:
            log.close()
        db.dispose()
        temporary.cleanup()


if __name__ == "__main__":
    main()
