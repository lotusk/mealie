"""HTTP parity for recipe listing, using only new disposable SQLite/Postgres databases."""

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
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlsplit
from uuid import UUID, uuid4

import jwt
import requests
from sqlalchemy import create_engine, text

REPO = Path(os.environ.get("MEALIE_PARITY_REPO", str(Path(__file__).resolve().parents[2])))
SECRET = "shh-secret-test-key"


def main() -> None:  # noqa: C901 - one resource lifecycle guarantees cleanup of every disposable service.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["sqlite", "postgres"], default="sqlite")
    parser.add_argument("--baseline-only", action="store_true")
    parser.add_argument("--postgres-database", default="compx574_recipe_list_002")
    parser.add_argument("--gateway", type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    assert args.postgres_database == "compx574_recipe_list_002" or args.postgres_database.startswith(
        "compx574_recipe_list_002_"
    ), "Refusing non-experiment database name"
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {
        "started_at_utc": datetime.now(UTC).isoformat(),
        "engine": args.engine,
        "baseline_only": args.baseline_only,
        "cases": [],
        "result": "in_progress",
        "comparison_policy": (
            "Compare all fields and recipe item order. Organizer arrays (recipeCategory/tags/tools/householdsWithTool) "
            "are unordered associations: Python declares no ordering for these relationships. Compare their complete "
            "values as sets only; retain raw responses. Compare redirect paths after removing each server origin. "
            "Sort/search cases request name:asc as a secondary sort to make tied pages deterministic."
        ),
    }
    processes: list[subprocess.Popen] = []
    logs = []
    temporary = tempfile.TemporaryDirectory(prefix="compx574-recipe-list-")
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
        "ALLOW_SIGNUP": "false",
    }
    env.pop("POSTGRES_URL_OVERRIDE", None)
    if args.engine == "sqlite":
        sqlite3.connect(data / "mealie.db").close()
        db = create_engine("sqlite:///" + str(data / "mealie.db"))
    else:
        env.update(
            POSTGRES_SERVER="localhost",
            POSTGRES_PORT="55432",
            POSTGRES_DB=args.postgres_database,
            POSTGRES_USER="mealie",
            POSTGRES_PASSWORD="mealie",
        )
        db = create_engine("postgresql+psycopg2://mealie:mealie@localhost:55432/" + args.postgres_database)
    with db.connect() as connection:
        tables = connection.execute(
            text(
                "SELECT name FROM sqlite_master WHERE type='table'"
                if args.engine == "sqlite"
                else "SELECT tablename FROM pg_tables WHERE schemaname='public'"
            )
        ).all()
        assert not tables, "Refusing fixtures: disposable database is not empty"
    report["fixture_isolation"] = {
        "fresh_database_verified": True,
        "shared_development_database_used": False,
        "database": str(data / "mealie.db") if args.engine == "sqlite" else args.postgres_database,
    }
    python, java, gateway = "http://localhost:9200", "http://localhost:9210", "http://localhost:9280"

    def start(name: str, command: list[str], service_env: dict[str, str] = env) -> subprocess.Popen:
        log = args.report.with_suffix("." + name + ".log").open("w")
        logs.append(log)
        process = subprocess.Popen(
            command, cwd=REPO, env=service_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
        )
        processes.append(process)
        return process

    def wait(url: str, process: subprocess.Popen) -> dict[str, Any]:
        for _ in range(150):
            assert process.poll() is None, f"Service exited: {url}"
            with contextlib.suppress(requests.RequestException):
                response = requests.get(url, timeout=1)
                if response.status_code == 200:
                    return response.json()
            time.sleep(0.2)
        raise RuntimeError("Service did not become ready: " + url)

    def auth(token: str | None) -> dict[str, str]:
        return {"Authorization": "Bearer " + token} if token else {}

    def api(method: str, path: str, token: str | None = None, body: Any = None) -> Any:
        response = requests.request(method, python + path, headers=auth(token), json=body, timeout=20)
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

    def capture(response: requests.Response) -> dict[str, Any]:
        try:
            body = response.json() if response.content else None
        except ValueError:
            body = response.text
        return {
            "status": response.status_code,
            "body": body,
            "headers": {
                key.lower(): value
                for key, value in response.headers.items()
                if key.lower() in ("x-mealie-backend", "www-authenticate", "last-modified", "cache-control", "location")
            },
        }

    def comparable(value: Any) -> Any:
        if isinstance(value, list):
            return [comparable(item) for item in value]
        if not isinstance(value, dict):
            return value
        result = {key: comparable(item) for key, item in value.items()}
        for key in ("recipeCategory", "tags", "tools", "householdsWithTool"):
            if isinstance(result.get(key), list):
                result[key] = sorted(result[key], key=lambda item: json.dumps(item, sort_keys=True))
        return result

    def probe(
        name: str,
        params: Any = None,
        token: str | None = None,
        path: str = "/api/recipes",
        expected: int | None = None,
        method: str = "GET",
        backend: str = "java",
        cookies: Any = None,
    ) -> Any:
        path += ("?" + urlencode(params, doseq=True)) if params else ""
        oracle = requests.request(
            method, python + path, headers=auth(token), cookies=cookies, timeout=20, allow_redirects=False
        )
        entry = {"name": name, "method": method, "path": path, "python": capture(oracle), "targets": {}, "failures": []}
        report["cases"].append(entry)
        if expected is not None and oracle.status_code != expected:
            entry["failures"].append(f"Python baseline status {oracle.status_code}, expected {expected}")
        targets = {} if args.baseline_only else {"java": java, **({"gateway": gateway} if args.gateway else {})}
        if backend == "python":
            targets = {} if args.baseline_only or not args.gateway else {"gateway": gateway}
        for target, base in targets.items():
            response = requests.request(
                method, base + path, headers=auth(token), cookies=cookies, timeout=20, allow_redirects=False
            )
            actual = capture(response)
            entry["targets"][target] = actual
            if (actual["status"], comparable(actual["body"])) != (
                entry["python"]["status"],
                comparable(entry["python"]["body"]),
            ):
                entry["failures"].append(target + " status/body differs")
            for key in ("www-authenticate", "last-modified", "cache-control", "location"):
                if (
                    urlsplit(actual["headers"].get(key, "")).path if key == "location" else actual["headers"].get(key)
                ) != (
                    urlsplit(entry["python"]["headers"].get(key, "")).path
                    if key == "location"
                    else entry["python"]["headers"].get(key)
                ):
                    entry["failures"].append(target + " " + key + " differs")
            if target == "gateway" and actual["headers"].get("x-mealie-backend") != backend:
                entry["failures"].append("gateway backend differs")
        entry["passed"] = not entry["failures"]
        print(  # noqa: T201 - save actual CLI case outcomes.
            f"{'PASS' if entry['passed'] else 'FAIL'} {name}: Python={oracle.status_code}, targets={list(targets)}",
            flush=True,
        )
        return entry["python"]["body"]

    try:
        py = start("python", ["uv", "run", "--frozen", "python", "mealie/app.py"])
        wait(python + "/api/app/about", py)
        root_token = login("changeme@example.com", "MyPassword")
        root = api("GET", "/api/users/self", root_token)
        group = api("GET", "/api/groups/self", root_token)
        home = api("GET", "/api/households/self", root_token)
        private = api(
            "POST", "/api/admin/households", root_token, {"name": "Private List Household", "groupId": group["id"]}
        )

        def user(email: str, group_name: str, household_name: str, admin: bool = False) -> tuple[str, dict[str, Any]]:
            api(
                "POST",
                "/api/admin/users",
                root_token,
                {
                    "fullName": email,
                    "username": email.split("@")[0],
                    "email": email,
                    "password": "List-test-password1",
                    "group": group_name,
                    "household": household_name,
                    "admin": admin,
                    "tokens": [],
                },
            )
            token = login(email, "List-test-password1")
            return token, api("GET", "/api/users/self", token)

        member_token, member = user("list-member@example.com", group["name"], private["name"])
        reader_token, reader = user("list-reader@example.com", group["name"], home["name"])
        foreign_group = api("POST", "/api/admin/groups", root_token, {"name": "Other List Group"})
        foreign_home = api(
            "POST",
            "/api/admin/households",
            root_token,
            {"name": "Other List Household", "groupId": foreign_group["id"]},
        )
        foreign_token, foreign_user = user(
            "list-foreign@example.com", foreign_group["name"], foreign_home["name"], True
        )
        label = api("POST", "/api/groups/labels", root_token, {"name": "List Label"})
        food = api("POST", "/api/foods", root_token, {"name": "List Tomato", "labelId": label["id"]})
        substitute = api("POST", "/api/foods", root_token, {"name": "List Substitute"})
        recipes = []
        names = [
            "Alpha Tomato Soup",
            "Beta Pasta",
            "Café Crème",
            "重庆 Noodles",
            "Delta Salad",
            "Echo Cake",
            "Foxtrot Bread",
            "Gamma Stew",
            "Hotel Rice",
            "India Curry",
            "Juliet Apple",
            "Kilo Dessert",
        ]
        for i, name in enumerate(names):
            owner_token = member_token if i % 3 == 0 else root_token
            slug = api("POST", "/api/recipes", owner_token, {"name": name})
            recipe = api("GET", "/api/recipes/" + slug, owner_token)
            recipe.update(
                description="Vegetarian fresh tomatoes" if i % 2 == 0 else "Bake and serve",
                recipeServings=i + 1,
                recipeYieldQuantity=i,
                totalTimeSeconds=i * 60,
                recipeCategory=[{"name": "List Dinner"}] + ([{"name": "List Quick"}] if i % 2 == 0 else []),
                tags=(
                    [{"name": "List Vegan"}, {"name": "List Fresh"}]
                    if i % 3 == 0
                    else [{"name": "List Fresh"}]
                    if i % 3 == 1
                    else []
                ),
                tools=[{"name": "List Pot"}] + ([{"name": "List Spoon"}] if i % 2 == 0 else []),
                recipeIngredient=[
                    {
                        "quantity": 1,
                        "food": food if i % 2 == 0 else substitute,
                        "note": "Basil parsley" if i % 2 == 0 else "Cinnamon",
                        "originalText": "2 chopped tomatoes",
                        "substitutions": [{"substituteFoodId": substitute["id"]}],
                    }
                ],
                settings={**recipe["settings"], "public": False},
            )
            recipe = api("PUT", "/api/recipes/" + slug, owner_token, recipe)
            execute(
                "UPDATE recipes SET rating=:rating, last_made=:made, created_at=:at WHERE id=:id",
                {
                    "id": scalar(recipe["id"]),
                    "rating": None if i % 4 == 0 else i % 5,
                    "made": datetime(2025, 1, i + 1, tzinfo=UTC).replace(tzinfo=None) if i % 2 else None,
                    "at": datetime(2026, 1, i + 1, tzinfo=UTC).replace(tzinfo=None),
                },
            )
            recipes.append(recipe)
        foreign_slug = api("POST", "/api/recipes", foreign_token, {"name": "Foreign Hidden Recipe"})
        foreign_recipe = api("GET", "/api/recipes/" + foreign_slug, foreign_token)
        execute(
            "INSERT INTO users_to_recipes (id,user_id,recipe_id,rating,is_favorite) "
            "VALUES (:id,:user,:recipe,4,:favorite)",
            {
                "id": scalar(str(uuid4())),
                "user": scalar(reader["id"]),
                "recipe": scalar(recipes[0]["id"]),
                "favorite": True,
            },
        )
        execute(
            "INSERT INTO households_to_recipes (id,household_id,recipe_id,last_made) VALUES (:id,:home,:recipe,:made)",
            {
                "id": scalar(str(uuid4())),
                "home": scalar(home["id"]),
                "recipe": scalar(recipes[0]["id"]),
                "made": datetime(2026, 2, 1, tzinfo=UTC).replace(tzinfo=None),
            },
        )
        first = api("GET", "/api/recipes/" + recipes[0]["slug"], root_token)
        category, category2 = first["recipeCategory"]
        tag, tag2 = first["tags"]
        tool, tool2 = first["tools"]
        cookbook = api(
            "POST",
            "/api/households/cookbooks",
            member_token,
            {"name": "List Cookbook", "queryFilterString": f'tags.id IN ["{tag["id"]}"]'},
        )
        label_book = api(
            "POST",
            "/api/households/cookbooks",
            root_token,
            {"name": "Food Label Cookbook", "queryFilterString": f'recipeIngredient.food.labelId IN ["{label["id"]}"]'},
        )
        foreign_book = api("POST", "/api/households/cookbooks", foreign_token, {"name": "Foreign Cookbook"})
        if not args.baseline_only:
            jp = start("java", ["java", "-jar", str(REPO / "backend-java/target/mealie-backend-0.0.1-SNAPSHOT.jar")])
            report["java_health"] = wait(java + "/api/java/health", jp)
            if args.gateway:
                config = data / "Caddyfile"
                config.write_text((REPO / "gateway/Caddyfile").read_text().replace(":8080 {", ":9280 {"))
                gw = start(
                    "gateway",
                    [str(args.gateway), "run", "--config", str(config), "--adapter", "caddyfile"],
                    {**env, "PYTHON_UPSTREAM": "localhost:9200", "JAVA_UPSTREAM": "localhost:9210"},
                )
                wait(gateway + "/api/app/about", gw)
        listing = probe("default listing", token=root_token, expected=200)
        assert listing["total"] == 12, "Group-scoped listing baseline changed"
        assert foreign_recipe["id"] not in [item["id"] for item in listing["items"]]
        probe("private same-group household visible", {"households": private["slug"]}, reader_token, expected=200)
        probe("foreign admin still group-scoped", token=foreign_token, expected=200)
        for params in (
            {"perPage": 3},
            {"perPage": 3, "page": 2},
            {"perPage": 3, "page": -1},
            {"page": 99},
            {"page": 0},
            {"page": -2},
            {"perPage": -1},
            {"perPage": -1, "page": 2},
            {"perPage": 0},
            {"perPage": -2},
            {"page": "bad"},
            {"perPage": "bad"},
            {"page": "1.0"},
            {"page": "10000000000000000000000000000000000000"},
            {"perPage": "10000000000000000000000000000000000000"},
            {"page": "10000000000000000000000000000000000000", "perPage": 0},
            {"page": "9223372036854775808", "perPage": 0},
            {"page": "18446744073709551615", "perPage": 0},
            {"page": "18446744073709551616", "perPage": 0},
            {"page": "-10000000000000000000000000000000000000"},
            {"perPage": "-10000000000000000000000000000000000000"},
            {"orderDirection": "up"},
            {"orderByNullPosition": "middle"},
            {"requireAllTags": "bad"},
            {"orderBy": "random"},
            {"orderBy": "random", "paginationSeed": ""},
            {"unknown": "a b&?", "perPage": 3},
            {
                "unknown-name": "*~",
                "_searchSeed": "frontend",
                "__search_seed": "double",
                "OrderBy": "name",
                "perPage": 3,
            },
            [("tags", tag["id"]), ("tags", tag2["id"]), ("perPage", 2)],
        ):
            probe("pagination/validation " + str(params), params, root_token)
        for order in (
            "name",
            "name:asc,createdAt:desc",
            "rating",
            "lastMade",
            "recipeServings",
            "recipeCategory.name",
            "tags.name",
            "recipeIngredient.food.name",
            "user.household.name",
            "settings.public",
            "random",
            "doesNotExist",
            "user.email",
            "cookTime",
            "name:up",
            "name:asc:desc",
        ):
            for direction in ("asc", "desc"):
                probe(
                    "sort " + order + " " + direction,
                    {
                        "orderBy": order if order == "random" else order + ",name:asc",
                        "orderDirection": direction,
                        "orderByNullPosition": "last",
                        "paginationSeed": "seed-重庆",
                        "perPage": 3,
                    },
                    reader_token,
                )
        for search in (
            "tomato",
            "tomato pasta",
            '"tomato soup"',
            "'fresh tomatoes'",
            "basil",
            "café",
            "重庆",
            "...",
            "tomto",
            "tomato%",
            '"basil parsley" cake',
            "",
        ):
            probe("search " + search, {"search": search, "orderBy": "name:asc", "perPage": 3}, root_token)
        filters = [
            'name = "Alpha Tomato Soup"',
            'name LIKE "%a%"',
            'name NOT LIKE "%a%"',
            "rating >= 3",
            "rating IS NULL",
            'lastMade > "2025-12-31"',
            "recipeServings >= 5",
            'dateAdded >= "2020-01-01"',
            "createdAt < $NOW+1d",
            "settings.public = false",
            'householdId = "' + private["id"] + '"',
            'user.household.name = "Private List Household"',
            'recipeIngredient.food.labelId = "' + label["id"] + '"',
            'favoritedBy.id = "' + reader["id"] + '"',
            'tags.id CONTAINS ALL ["' + tag["id"] + '","' + tag2["id"] + '"]',
            'tags.id NOT IN ["' + tag["id"] + '"]',
            'tags.name = "List Vegan" AND tags.name = "List Fresh"',
            'name LIKE "%soup%" AND rating > 1 OR recipeServings > 10',
            '(name LIKE "%soup%" AND rating > 1) OR recipeServings > 10',
            'name LIKE "%x%" OR (recipeServings > 8 AND recipeServings < 11)',
            'id = "invalid"',
            'dateAdded > "invalid"',
            'rating LIKE "3"',
            'name IS "hello"',
            'tags.id IN "x"',
            'user.email = "x"',
            'user.password = "x"',
            "unknown = 1",
            '(name = "x"',
            "nothing",
            'name = "x" OR name = "y"',
            'name = "List AND OR (Dinner)"',
            "recipeServings <> 5",
            'createdAt > "January 2, 2026"',
            'tags.name CONTAINS ALL ["List Vegan","List Fresh"]',
        ]
        filters.extend(
            [
                'dateAdded > "01/02/2026"',
                'createdAt > "20260102"',
                'createdAt > "2 january 2026 1:30 PM"',
                'createdAt > "Fri, 2 Jan 2026 13:00:00 GMT"',
                'createdAt > "2026-01"',
                'createdAt > "01/02/26"',
                'dateAdded > "2026-02-30"',
                'createdAt > "Jan 2nd 2026"',
                'createdAt > "2026 Jan 2"',
                'createdAt > "Jan 2, 26"',
                'createdAt > "2-Jan-26"',
                'createdAt > "31/01/2026"',
                'createdAt > "31/01/26"',
                'createdAt > "2026.01.02"',
                'createdAt > "2026-01-02 at 5pm"',
                'createdAt > "5pm"',
                'createdAt > "Jan 2"',
                'createdAt > "01/02"',
                'createdAt > "2"',
                'createdAt > "January"',
                'createdAt > "Friday"',
                'createdAt > "Friday 13:00"',
                'createdAt > "2026-01-02 13:00 foo"',
                'createdAt > "Jan 2nd of 2026"',
                'createdAt > "2026-01-02 5h30m"',
                'createdAt > "20260102T130000"',
                'createdAt > "2026-01-02 13:00 PST"',
                'createdAt > "2026-01-02 13:00 GMT+3"',
                'createdAt > "2026-01-02 5.5h"',
                'recipeServings > "invalid"',
                'rating = "invalid"',
                'household.name = "Home"',
            ]
        )
        for query in filters:
            probe("query filter " + query, {"queryFilter": query, "perPage": 3}, reader_token)
        for key, first_org, second_org in (
            ("categories", category, category2),
            ("tags", tag, tag2),
            ("tools", tool, tool2),
        ):
            for value in (first_org["id"], first_org["slug"], "missing-slug", str(uuid4())):
                probe(key + " " + value, {key: value, "perPage": 3}, root_token)
            for require in (False, True):
                probe(
                    key + " multiple " + str(require),
                    {
                        key: [first_org["id"], second_org["slug"]],
                        "requireAll" + key.title(): str(require).lower(),
                        "perPage": 3,
                    },
                    root_token,
                )
        for params in (
            {"foods": food["id"]},
            {"foods": substitute["id"]},
            {"foods": [food["id"], substitute["id"]], "requireAllFoods": "true"},
            {"foods": "missing-slug"},
            {"households": home["id"]},
            {"households": foreign_home["id"]},
            {"households": "missing-slug"},
            {"cookbook": cookbook["id"]},
            {"cookbook": cookbook["slug"]},
            {"cookbook": label_book["id"]},
            {"cookbook": cookbook["id"], "tags": "missing-slug", "households": foreign_home["id"]},
            {"cookbook": cookbook["id"], "queryFilter": "recipeServings > 5"},
            {"cookbook": "missing-slug"},
            {"cookbook": foreign_book["id"]},
        ):
            probe("filter " + str(params), params, root_token)
        probe("missing authentication", expected=401)
        probe("invalid bearer", token="invalid", expected=401)
        expired = jwt.encode(
            {"sub": root["id"], "exp": datetime.now(UTC) - timedelta(days=1)}, SECRET, algorithm="HS256"
        )
        probe("expired bearer", token=expired, expected=401)
        unknown = jwt.encode(
            {"sub": str(uuid4()), "exp": datetime.now(UTC) + timedelta(days=1)}, SECRET, algorithm="HS256"
        )
        probe("unknown user", token=unknown, expected=401)
        probe("cookie authentication", cookies={"mealie.access_token": root_token}, expected=200)
        probe(
            "invalid bearer overrides cookie",
            token="invalid",
            cookies={"mealie.access_token": root_token},
            expected=401,
        )
        now = datetime.now(UTC)
        forged = jwt.encode(
            {"sub": member["id"], "iat": now, "exp": now + timedelta(hours=1)}, "wrong-secret", algorithm="HS256"
        )
        probe("wrong signature", token=forged, expected=401)
        api_token = jwt.encode(
            {"id": member["id"], "long_token": "list-fixture", "iat": now, "exp": now + timedelta(hours=1)},
            SECRET,
            algorithm="HS256",
        )
        execute(
            "INSERT INTO long_live_tokens (name,token,user_id) VALUES (:name,:token,:user)",
            {"name": "list API fixture", "token": api_token, "user": scalar(member["id"])},
        )
        probe("registered API token", token=api_token, expected=200)
        execute("DELETE FROM long_live_tokens WHERE token=:token", {"token": api_token})
        probe("revoked API token", token=api_token, expected=401)
        execute(
            "UPDATE users SET tokens_valid_after=:at WHERE id=:id",
            {"at": now.replace(tzinfo=None) + timedelta(seconds=1), "id": scalar(member["id"])},
        )
        probe("session revoked after password change", token=member_token, expected=401)
        execute("UPDATE users SET tokens_valid_after=NULL WHERE id=:id", {"id": scalar(member["id"])})
        page_body = probe("follow pagination first page", {"perPage": 3, "orderBy": "name:asc"}, root_token)
        next_link = page_body["next"]
        assert next_link.startswith("/recipes?")
        page_body = probe("follow Python next link", token=root_token, path="/api" + next_link)
        assert page_body["page"] == 2
        probe("follow Python previous link", token=root_token, path="/api" + page_body["previous"])
        probe("detail slug regression", token=reader_token, path="/api/recipes/" + recipes[0]["slug"], expected=200)
        probe("detail ID regression", token=root_token, path="/api/recipes/" + recipes[0]["id"], expected=200)
        probe("detail foreign-group restriction", token=root_token, path="/api/recipes/" + foreign_slug, expected=404)
        for method, path in (
            ("HEAD", "/api/recipes"),
            ("OPTIONS", "/api/recipes"),
            ("GET", "/api/recipes/"),
            ("GET", "/api/recipes/suggestions"),
            ("GET", "/api/recipes/exports"),
            ("GET", "/api/foods"),
            ("GET", "/api/users/self"),
            ("POST", "/api/recipes"),
            ("PUT", "/api/recipes/00000000-0000-4000-8000-000000000000"),
            ("DELETE", "/api/recipes/00000000-0000-4000-8000-000000000000"),
            ("POST", "/api/recipes/create/url"),
            ("POST", "/api/auth/token"),
        ):
            probe(
                "unmigrated boundary " + method + " " + path,
                token=root_token,
                path=path,
                method=method,
                backend="python",
            )
        if not args.baseline_only:
            os.killpg(py.pid, signal.SIGTERM)
            try:
                py.wait(timeout=10)
                report["python_shutdown"] = "graceful"
            except subprocess.TimeoutExpired:
                os.killpg(py.pid, signal.SIGKILL)
                py.wait(timeout=10)
                report["python_shutdown"] = "forced after graceful shutdown timeout"
            try:
                requests.get(python + "/api/app/about", timeout=1)
            except requests.ConnectionError:
                report["python_unreachable_verified"] = True
            else:
                raise AssertionError("Python is still reachable during Java independence check")
            for target, base in {"java": java, **({"gateway": gateway} if args.gateway else {})}.items():
                for path in ("/api/recipes", "/api/recipes/" + recipes[0]["slug"]):
                    response = requests.get(base + path, headers=auth(root_token), timeout=20)
                    passed = response.status_code == 200 and (
                        target != "gateway" or response.headers.get("X-Mealie-Backend") == "java"
                    )
                    report["cases"].append(
                        {
                            "name": "Python stopped " + target + " " + path,
                            "passed": passed,
                            "targets": {target: capture(response)},
                            "failures": [] if passed else ["Python-stop independence failed"],
                        }
                    )
        report["result"] = "passed" if all(case["passed"] for case in report["cases"]) else "failed"
    except Exception as error:
        report["result"] = "error"
        report["error"] = str(error)
        raise
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
        for process in reversed(processes):
            with contextlib.suppress(subprocess.TimeoutExpired):
                process.wait(timeout=10)
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        for log in logs:
            log.close()
        db.dispose()
        temporary.cleanup()
        report["completed_at_utc"] = datetime.now(UTC).isoformat()
        report["total_cases"] = len(report["cases"])
        report["passed_cases"] = sum(case["passed"] for case in report["cases"])
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False, default=str) + "\n")
    if report["result"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
