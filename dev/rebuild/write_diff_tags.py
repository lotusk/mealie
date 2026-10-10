"""Compare the tag write endpoints (POST/PUT/DELETE, merge) of the Python and Java backends.

diff_test.py only sends read-only requests, because both backends share one database. This script instead runs the
same scenario against each backend on its own fresh data (names carry a per-backend suffix), then compares every
step's status code and JSON body after replacing generated ids, the suffix and timestamps with placeholders.

    uv run python dev/rebuild/write_diff_tags.py

Needs both backends running on the same database, the seeded admin (changeme@example.com / MyPassword), and creates
a second, non-organizer user "tags-noorg" if it doesn't exist. Leaves its tags and recipes behind (names start with
"Parity").
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import uuid
from typing import Any

UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}")
TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?|datetime\.datetime\([0-9, ]+\)")


def call(
    base: str,
    method: str,
    path: str,
    token: str | None,
    body: Any = None,
    raw: bytes | None = None,
    content_type: str | None = "application/json",
) -> tuple[int, Any, dict]:
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if data is not None and content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req, timeout=30) as resp:
            status, payload, resp_headers = resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        status, payload, resp_headers = e.code, e.read(), dict(e.headers)
    try:
        parsed = json.loads(payload) if payload else None
    except ValueError:
        parsed = payload.decode(errors="replace")
    return status, parsed, resp_headers


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def login(base: str, username: str, password: str) -> str:
    form = f"username={username}&password={password}".encode()
    with urllib.request.urlopen(urllib.request.Request(base + "/api/auth/token", data=form), timeout=30) as r:
        return json.load(r)["access_token"]


def scenario(base: str, python: str, admin: str, noorg: str, sfx: str) -> list[tuple[str, int, Any]]:
    """Runs every step against `base`; recipes are set up through Python, which owns the recipe routes."""
    T = "/api/organizers/tags"
    steps: list[tuple[str, int, Any]] = []

    def step(label: str, method: str, path: str, token: str | None = admin, **kw: Any) -> Any:
        status, body, headers = call(base, method, path, token, **kw)
        if status == 307:
            body = {
                "location": re.sub(
                    r"^https?://[^/]+", "", {k.lower(): v for k, v in headers.items()}.get("location", "")
                )
            }
        steps.append((label, status, body))
        return body

    a = step("create", "POST", T, body={"name": f"  Parity {sfx} Café &amp; Co!  "})
    step("create duplicate", "POST", T, body={"name": f"Parity {sfx} Café &amp; Co!"})
    step("create blank", "POST", T, body={"name": "   "})
    step("create missing name", "POST", T, body={"nme": 1})
    step("create null name", "POST", T, body={"name": None})
    step("create list body", "POST", T, body=[1])
    step("create bad json", "POST", T, raw=b'{"name": "x",}')
    step("create bad json 2", "POST", T, raw=b'{"name": "x" "y"}')
    step("create no content type", "POST", T, raw=b'{"name": "x"}', content_type=None)
    step("create bad utf8", "POST", T, raw=b"\xff\xfe{")
    step("create empty body", "POST", T, raw=b"")
    step("create unauthenticated", "POST", T, token=None, body={"name": "x"})
    step("create bad json unauthenticated", "POST", T, token=None, raw=b"{bad")
    step("create forbidden", "POST", T, token=noorg, body={"name": f"Parity {sfx} forbidden"})
    b = step("create second", "POST", T, body={"name": f"Parity {sfx} Ελληνικά 日本 Straße"})
    c = step("create third", "POST", T, body={"name": f"Parity {sfx} third"})

    a_id, b_id, c_id = a["id"], b["id"], c["id"]
    step("get", "GET", f"{T}/{a_id}")
    step("update", "PUT", f"{T}/{a_id}", body={"name": f" Parity {sfx} renamed "})
    step("update unchanged", "PUT", f"{T}/{a_id}", body={"name": f"Parity {sfx} renamed"})
    step("update slug clash", "PUT", f"{T}/{a_id}", body={"name": f"Parity {sfx} third"})
    step("update blank", "PUT", f"{T}/{a_id}", body={"name": ""})
    step("update unknown", "PUT", f"{T}/{uuid.uuid4()}", body={"name": "x"})
    step("update bad id and body", "PUT", f"{T}/bad", body={"x": 1})
    step("update forbidden", "PUT", f"{T}/{a_id}", token=noorg, body={"name": "x"})

    # A recipe tagged with a and b, another with b and c, set up through Python.
    for i, tags in enumerate([[a_id, b_id], [b_id, c_id]]):
        _, slug, _ = call(python, "POST", "/api/recipes", admin, body={"name": f"Parity {sfx} recipe {i}"})
        _, recipe, _ = call(python, "GET", f"/api/recipes/{slug}", admin)
        _, all_tags, _ = call(python, "GET", f"{T}?perPage=-1", admin)
        recipe["tags"] = [t for t in all_tags["items"] if t["id"] in tags]
        call(python, "PUT", f"/api/recipes/{slug}", admin, body=recipe)

    step("slug with recipes", "GET", f"{T}/slug/parity-{sfx.lower()}-third")
    step("list filtered", "GET", f"{T}?queryFilter=name%20LIKE%20%22parity%20{sfx}%25%22&orderBy=name")
    step("merge same", "POST", f"{T}/merge", body={"fromId": a_id, "toId": a_id})
    step("merge unknown from", "POST", f"{T}/merge", body={"fromId": str(uuid.uuid4()), "toId": a_id})
    step("merge unknown to", "POST", f"{T}/merge", body={"fromId": a_id, "toId": str(uuid.uuid4())})
    step("merge bad body", "POST", f"{T}/merge", body={"from_id": "bad"})
    step("merge forbidden", "POST", f"{T}/merge", token=noorg, body={"fromId": a_id, "toId": b_id})
    step("merge", "POST", f"{T}/merge", body={"fromId": a_id, "toId": b_id})
    step("merged tag", "GET", f"{T}/slug/{b['slug']}")
    step("from is gone", "GET", f"{T}/{a_id}")
    step("delete unknown", "DELETE", f"{T}/{uuid.uuid4()}")
    step("delete forbidden", "DELETE", f"{T}/{c_id}", token=noorg)
    step("delete", "DELETE", f"{T}/{c_id}")
    step("deleted", "GET", f"{T}/{c_id}")
    step("delete again", "DELETE", f"{T}/{c_id}")
    step("trailing slash", "GET", f"{T}/?perPage=1")
    step("trailing slash on item", "POST", f"{T}/{b_id}/", body={})
    step("method not allowed", "PATCH", f"{T}/{b_id}", body={})
    return steps


def normalize(value: Any, sfx: str, ids: dict[str, str]) -> Any:
    if isinstance(value, dict):
        # sorted, so placeholder numbers don't depend on key order
        return {k: normalize(value[k], sfx, ids) for k in sorted(value)}
    if isinstance(value, list):
        return [normalize(v, sfx, ids) for v in value]
    if isinstance(value, str):
        value = value.replace(sfx, "<SFX>").replace(sfx.lower(), "<sfx>")
        value = TIMESTAMP_RE.sub("<TIME>", value)

        def token(m: re.Match) -> str:
            key = m.group().replace("-", "")
            return ids.setdefault(key, f"<ID{len(ids)}>")

        return UUID_RE.sub(token, value)
    return value


def say(message: str = "") -> None:
    sys.stdout.write(message + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--python", default="http://localhost:9000")
    parser.add_argument("--java", default="http://localhost:9100")
    args = parser.parse_args()

    admin = login(args.python, "changeme@example.com", "MyPassword")
    call(
        args.python,
        "POST",
        "/api/admin/users",
        admin,
        body={
            "username": "tags-noorg",
            "fullName": "Tags NoOrg",
            "email": "tags-noorg@example.com",
            "password": "Password123!",
            "group": "Home",
            "household": "Family",
            "admin": False,
            "canOrganize": False,
        },
    )
    noorg = login(args.python, "tags-noorg@example.com", "Password123!")

    run_id = uuid.uuid4().hex[:6].upper()
    results = {}
    for name, base in [("python", args.python), ("java", args.java)]:
        sfx = f"P{run_id}" if name == "python" else f"J{run_id}"
        ids: dict[str, str] = {}
        results[name] = [
            (label, status, normalize(body, sfx, ids))
            for label, status, body in scenario(base, args.python, admin, noorg, sfx)
        ]

    failures = 0
    for (label, py_status, py_body), (_, java_status, java_body) in zip(
        results["python"], results["java"], strict=True
    ):
        if py_status == java_status and py_body == java_body:
            say(f"PASS  {label}")
            continue
        failures += 1
        say(
            f"FAIL  {label}\n        python {py_status}: {json.dumps(py_body)[:4000]}\n"
            f"        java   {java_status}: {json.dumps(java_body)[:4000]}"
        )
    say(f"\n{len(results['python']) - failures}/{len(results['python'])} steps matched")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
