"""Exercise Java tag mutations and compare reads with Python on a disposable, Alembic-created DB.

Run `task java:package`, then `uv run python dev/rebuild/test_tags_migration.py --engine sqlite`.
For Postgres, set POSTGRES_SERVER/PORT/USER/PASSWORD and use --engine postgres. A temporary database
is created and dropped; the configured development database is never modified.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["sqlite", "postgres"], default="sqlite")
    args = parser.parse_args()
    processes: list[subprocess.Popen] = []
    admin_engine = None
    database_name = "tags_migration_" + uuid4().hex
    with tempfile.TemporaryDirectory(prefix="mealie-tags-") as data_dir:
        os.environ.update(TESTING="true", PRODUCTION="false", DATA_DIR=data_dir, DB_ENGINE=args.engine)
        if args.engine == "postgres":
            from sqlalchemy import create_engine
            from sqlalchemy.engine import URL

            url = URL.create(
                "postgresql+psycopg2",
                username=os.environ.get("POSTGRES_USER", "mealie"),
                password=os.environ.get("POSTGRES_PASSWORD", "mealie"),
                host=os.environ.get("POSTGRES_SERVER", "localhost"),
                port=int(os.environ.get("POSTGRES_PORT", "5432")),
                database="postgres",
            )
            admin_engine = create_engine(url, isolation_level="AUTOCOMMIT")
            with admin_engine.connect() as connection:
                connection.exec_driver_sql(f'CREATE DATABASE "{database_name}"')
            os.environ["POSTGRES_DB"] = database_name
            os.environ.pop("POSTGRES_URL_OVERRIDE", None)

        try:
            from fastapi.testclient import TestClient

            from mealie.app import app
            from mealie.core.config import get_app_settings
            from mealie.db.init_db import main as init_db

            init_db()
            python = TestClient(app)
            settings = get_app_settings()
            login = python.post(
                "/api/auth/token",
                data={"username": settings._DEFAULT_EMAIL, "password": settings._DEFAULT_PASSWORD},
            )
            assert login.status_code == 200, login.text
            headers = {"Authorization": "Bearer " + login.json()["access_token"]}
            log = open(Path(data_dir) / "servers.log", "w")
            env = {**os.environ, "JAVA_API_PORT": "19100", "PYTHON_API_URL": "http://localhost:19000"}
            processes.append(
                subprocess.Popen(
                    [sys.executable, "-m", "uvicorn", "mealie.app:app", "--host", "127.0.0.1", "--port", "19000"],
                    cwd=ROOT,
                    env=env,
                    stdout=log,
                    stderr=log,
                )
            )
            processes.append(
                subprocess.Popen(
                    ["java", "-jar", str(ROOT / "backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar")],
                    cwd=ROOT,
                    env=env,
                    stdout=log,
                    stderr=log,
                )
            )

            def java(method: str, path: str, body: dict | None = None) -> tuple[int, object]:
                request = urllib.request.Request(
                    "http://localhost:19100" + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={**headers, "Content-Type": "application/json"},
                    method=method,
                )
                try:
                    response = urllib.request.urlopen(request, timeout=20)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    raw = response.read()
                    return response.status, json.loads(raw) if raw else None

            for port in [19000, 19100]:
                for _ in range(100):
                    try:
                        urllib.request.urlopen(f"http://localhost:{port}/api/app/about", timeout=1).close()
                        break
                    except OSError, urllib.error.URLError:
                        time.sleep(0.2)
                else:
                    raise AssertionError(Path(data_dir, "servers.log").read_text())

            base = "/api/organizers/tags"
            tags = []
            for name in ["Alpha", "Beta", "Crème brûlée & tea", "Dinner's", "中文", "1,000 &amp; tea"]:
                status, tag = java("POST", base, {"name": name})
                assert status == 201, tag
                tags.append(tag)
                py = python.get(base + "/" + tag["id"], headers=headers)
                assert py.status_code == 200 and py.json()["name"] == name.strip(), py.text

            recipe_response = python.post("/api/recipes", json={"name": "Soup"}, headers=headers)
            assert recipe_response.status_code == 201, recipe_response.text
            recipe_path = "/api/recipes/" + recipe_response.json()
            recipe = python.get(recipe_path, headers=headers).json()
            recipe["tags"] = tags[:2]
            assert python.put(recipe_path, json=recipe, headers=headers).status_code == 200

            checks = 0
            failures = []

            def compare(path: str) -> None:
                nonlocal checks
                py = python.get(path, headers=headers)
                status, actual = java("GET", path)
                assert status == py.status_code, (path, status, py.status_code)
                expected = py.json()
                # ORM relationships have no order_by: compare their membership, not planner-dependent row order.
                for payload in [actual, expected]:
                    if isinstance(payload, dict) and "recipes" in payload:
                        payload["recipes"].sort(key=lambda recipe: recipe["id"])
                        for recipe in payload["recipes"]:
                            for collection in ["tags", "recipeCategory", "tools"]:
                                recipe[collection].sort(key=lambda organizer: organizer["id"])
                            for tool in recipe["tools"]:
                                tool["householdsWithTool"].sort()
                if actual != expected:
                    failures.append({"path": path, "java": actual, "python": expected})
                checks += 1

            for query in [
                "",
                "?orderBy=name&orderDirection=asc",
                "?orderBy=name:desc,slug:asc&perPage=2",
                "?orderBy=name&perPage=-1",
                "?orderBy=name&page=-1&perPage=2",
                "?perPage=0",
                "?search=Alph",
                "?search=%22Alph%22",
                "?search=Alpha%20Beta",
                "?search=absent",
                "?search=%21%21%21",
                "?queryFilter=name%20%3D%20%22alpha%22",
                "?orderBy=random&paginationSeed=stable&perPage=2",
                "?queryFilter=recipes.id%20IS%20NOT%20NULL",
                "?queryFilter=recipes.name%20%3D%20%22soup%22",
            ]:
                compare(base + query)
            compare(base + "/empty")
            compare(base + "/" + tags[0]["id"])
            compare(base + "/slug/alpha")
            compare(base + "/" + str(uuid4()))

            status, result = java("PUT", base + "/" + tags[0]["id"], {"name": "Updated"})
            assert status == 200 and result["slug"] == "updated", result
            compare(base + "/slug/updated")
            status, result = java("POST", base + "/merge", {"fromId": tags[0]["id"], "toId": tags[1]["id"]})
            assert status == 200 and result["recipeCount"] == 1, result
            compare(base + "/slug/beta")
            assert java("DELETE", base + "/" + tags[1]["id"]) == (200, None)
            assert python.get(recipe_path, headers=headers).json()["tags"] == []
            compare(base + "?orderBy=name")
            assert not failures, json.dumps(failures, indent=2)
            print(  # noqa: T201
                f"PASS: {args.engine}: {checks} matching Python/Java reads; "
                "Java CRUD, merge and Python recipe interoperability"
            )
            log.flush()
            server_log = Path(data_dir, "servers.log").read_text()
            assert (
                "Tag event dispatch failed" not in server_log and "tag event dispatch returned HTTP" not in server_log
            ), server_log
        finally:
            for process in processes:
                process.terminate()
            for process in processes:
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            if admin_engine:
                from mealie.db.db_setup import engine

                engine.dispose()
                with admin_engine.connect() as connection:
                    connection.exec_driver_sql(f'DROP DATABASE "{database_name}" WITH (FORCE)')
                admin_engine.dispose()


if __name__ == "__main__":
    main()
