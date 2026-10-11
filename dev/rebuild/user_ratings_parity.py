"""Compare self ratings on isolated Python/Java servers and optionally the actual Caddyfile.

Run after task java:package:
    uv run --frozen python dev/rebuild/user_ratings_parity.py --report /tmp/ratings-parity.json
Fixtures use a disposable SQLite database, never the shared development database.
"""

import argparse
import json
import os
import socket
import sqlite3
import subprocess
import tempfile
from pathlib import Path
from uuid import uuid4

import requests
from recipe_get_parity import REPO, compare_case, stop_processes, wait


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--gateway", type=Path)
    args = parser.parse_args()
    ports = (9340, 9350, 9420)
    for port in ports:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", port))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    python, java, gateway = (f"http://localhost:{port}" for port in ports)
    report = {"engine": "sqlite", "shared_development_database_used": False, "cases": [], "result": "in_progress"}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix="mealie-ratings-parity-") as directory:
        data = Path(directory)
        with sqlite3.connect(data / "mealie.db"):
            pass
        env = {
            **os.environ,
            "PRODUCTION": "false",
            "TESTING": "true",
            "DATA_DIR": str(data),
            "DB_ENGINE": "sqlite",
            "API_PORT": str(ports[0]),
            "JAVA_API_PORT": str(ports[1]),
            "TOKEN_TIME": "48",
        }
        env.pop("POSTGRES_URL_OVERRIDE", None)

        def start(name: str, command: list[str], service_env: dict[str, str] = env) -> subprocess.Popen:
            log = args.report.with_suffix("." + name + ".log").open("w")
            logs.append(log)
            process = subprocess.Popen(
                command, cwd=REPO, env=service_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
            )
            processes.append(process)
            return process

        try:
            py = start("python", ["uv", "run", "--frozen", "python", "mealie/app.py"])
            wait(python + "/api/app/about", py)
            response = requests.post(
                python + "/api/auth/token",
                data={"username": "changeme@example.com", "password": "MyPassword"},
                timeout=20,
            )
            response.raise_for_status()
            token = response.json()["access_token"]

            def api(method: str, path: str, body: dict | None = None):
                response = requests.request(
                    method, python + path, headers={"Authorization": "Bearer " + token}, json=body, timeout=20
                )
                response.raise_for_status()
                return response.json()

            user = api("GET", "/api/users/self")
            recipes = []
            for name in ("Rated recipe", "Favorite only", "Zero rating"):
                slug = api("POST", "/api/recipes", {"name": name})
                recipes.append(api("GET", "/api/recipes/" + slug)["id"])

            jar = REPO / "backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar"
            jvm = start("java", ["java", "-jar", str(jar)])
            wait(java + "/api/java/health", jvm)
            if args.gateway:
                config = data / "Caddyfile"
                config.write_text((REPO / "gateway/Caddyfile").read_text().replace(":8080 {", ":9420 {"))
                gw_env = {**env, "PYTHON_UPSTREAM": "localhost:9340", "JAVA_UPSTREAM": "localhost:9350"}
                gw = start(
                    "gateway", [str(args.gateway), "run", "--config", str(config), "--adapter", "caddyfile"], gw_env
                )
                wait(gateway + "/api/app/about", gw)

            def probe(name: str, **kwargs) -> None:
                compare_case(python, java, gateway if args.gateway else None, report, name, **kwargs)

            path = "/api/users/self/ratings"
            probe("empty ratings", path=path, token=token, expected=200)
            with sqlite3.connect(data / "mealie.db") as connection:
                for recipe, rating, favorite in zip(recipes, (3.5, None, 0), (False, True, False), strict=True):
                    connection.execute(
                        "INSERT INTO users_to_recipes (id, user_id, recipe_id, rating, is_favorite) "
                        "VALUES (?, ?, ?, ?, ?)",
                        (uuid4().hex, user["id"].replace("-", ""), recipe.replace("-", ""), rating, favorite),
                    )
            probe("rated, favorite-only and zero", path=path, token=token, expected=200)
            probe("session cookie", path=path, cookies={"mealie.access_token": token}, expected=200)
            probe("unauthenticated", path=path, expected=401)
            probe("invalid bearer", path=path, token="invalid", expected=401)
            if args.gateway:
                probe(
                    "individual rating stays on Python",
                    path=path + "/" + recipes[0],
                    token=token,
                    expected=200,
                    backend="python",
                )
                probe(
                    "favorites stay on Python",
                    path="/api/users/self/favorites",
                    token=token,
                    expected=200,
                    backend="python",
                )
                probe("POST stays on Python", path=path, method="POST", expected=405, backend="python")
            report["result"] = "passed" if all(case["passed"] for case in report["cases"]) else "failed"
        finally:
            stop_processes(processes)
            for log in logs:
                log.close()
            args.report.write_text(json.dumps(report, indent=2))
    assert report["result"] == "passed", f"See {args.report} for differences"


if __name__ == "__main__":
    main()
