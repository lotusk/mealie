"""Compare tool listing on isolated Python/Java servers and optionally Caddy, using a temporary SQLite database.

Run after task java:package:
    uv run --frozen python dev/rebuild/tool_list_parity.py --report /tmp/tools-parity.json
"""

import argparse
import json
import os
import socket
import sqlite3
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlencode
from uuid import uuid4

import requests
from recipe_get_parity import REPO, compare_case, stop_processes, wait


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--gateway", type=Path)
    args = parser.parse_args()
    ports = (9320, 9330, 9400)
    for port in ports:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", port))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    python, java, gateway = (f"http://localhost:{port}" for port in ports)
    report = {"engine": "sqlite", "shared_development_database_used": False, "cases": [], "result": "in_progress"}
    processes, logs = [], []
    with tempfile.TemporaryDirectory(prefix="mealie-tools-parity-") as directory:
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

            group = api("GET", "/api/groups/self")
            household = api("GET", "/api/households/self")
            second = api("POST", "/api/admin/households", {"name": "Tools second", "groupId": group["id"]})
            pan = api(
                "POST",
                "/api/organizers/tools",
                {
                    "name": "Baking Pan",
                    "householdsWithTool": [household["slug"], second["slug"]],
                },
            )
            api("POST", "/api/organizers/tools", {"name": "Whisk"})
            slug = api("POST", "/api/recipes", {"name": "Tools parity cake"})
            recipe = api("GET", "/api/recipes/" + slug)
            recipe["tools"] = [pan]
            api("PUT", "/api/recipes/" + slug, recipe)
            foreign_group = api("POST", "/api/admin/groups", {"name": "Tools foreign"})
            with sqlite3.connect(data / "mealie.db") as connection:
                connection.execute(
                    "INSERT INTO tools (id, group_id, name, slug) VALUES (?, ?, ?, ?)",
                    (uuid4().hex, foreign_group["id"].replace("-", ""), "Foreign tool", "foreign-tool"),
                )

            jar = REPO / "backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar"
            jvm = start("java", ["java", "-jar", str(jar)])
            wait(java + "/api/java/health", jvm)
            if args.gateway:
                config = data / "Caddyfile"
                config.write_text((REPO / "gateway/Caddyfile").read_text().replace(":8080 {", ":9400 {"))
                gw_env = {**env, "PYTHON_UPSTREAM": "localhost:9320", "JAVA_UPSTREAM": "localhost:9330"}
                gw = start(
                    "gateway", [str(args.gateway), "run", "--config", str(config), "--adapter", "caddyfile"], gw_env
                )
                wait(gateway + "/api/app/about", gw)

            def probe(name: str, query: dict, expected: int = 200, authenticated: bool = True) -> None:
                compare_case(
                    python,
                    java,
                    gateway if args.gateway else None,
                    report,
                    name,
                    "/api/organizers/tools?" + urlencode(query),
                    token if authenticated else None,
                    expected=expected,
                )

            probe("all by name", {"page": 1, "perPage": -1, "orderBy": "name", "orderDirection": "asc"})
            probe("first page", {"perPage": 1, "orderBy": "name", "orderDirection": "asc"})
            probe("last page", {"page": -1, "perPage": 1, "orderBy": "name", "orderDirection": "asc"})
            probe("past end", {"page": 99, "perPage": 2})
            probe("empty page", {"perPage": 0})
            probe("search", {"search": "pan"})
            probe("household filter", {"queryFilter": "householdsWithTool.slug = " + second["slug"]})
            probe("recipe filter", {"queryFilter": "recipes.slug = " + slug})
            probe("group isolation", {"queryFilter": "slug = foreign-tool"})
            probe("seeded random", {"orderBy": "random", "paginationSeed": "tools"})
            probe("invalid query", {"page": "invalid", "orderDirection": "invalid"}, 422)
            probe("invalid sort", {"orderBy": "nonexistent"}, 400)
            probe("unauthenticated", {}, 401, False)
            if args.gateway:
                compare_case(
                    python,
                    java,
                    gateway,
                    report,
                    "empty route stays on Python",
                    "/api/organizers/tools/empty",
                    token,
                    expected=200,
                    backend="python",
                )
                compare_case(
                    python,
                    java,
                    gateway,
                    report,
                    "detail route stays on Python",
                    "/api/organizers/tools/" + pan["id"],
                    token,
                    expected=200,
                    backend="python",
                )
                compare_case(
                    python,
                    java,
                    gateway,
                    report,
                    "POST stays on Python",
                    "/api/organizers/tools",
                    method="POST",
                    expected=401,
                    backend="python",
                )
            report["result"] = "passed" if all(case["passed"] for case in report["cases"]) else "failed"
        finally:
            stop_processes(processes)
            for log in logs:
                log.close()
            args.report.write_text(json.dumps(report, indent=2))
    assert report["result"] == "passed", f"See {args.report} for differences"


if __name__ == "__main__":
    main()
