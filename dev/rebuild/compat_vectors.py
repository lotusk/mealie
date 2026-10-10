"""Write the Python reference outputs that backend-java's compat tests check against.

The Java backend reimplements a few Python behaviours that must match byte for byte (python-slugify, random.shuffle
with a str seed, json.loads error messages, pydantic's UUID4 errors). This script records what Python does for a set
of inputs; PythonCompatibilityTest asserts Java does the same.

    uv run python dev/rebuild/compat_vectors.py > backend-java/src/test/resources/compat/python-vectors.json
"""

import json
import random
import sys

from pydantic import UUID4, TypeAdapter, ValidationError
from slugify import slugify

SLUG_INPUTS = [
    "Spicy Food!",
    "Ümlaut Café & Bar",
    "Ελληνικά 日本 Straße",
    'It\'s 1,000 "quoted"',
    "&amp; &eacute;t&#233; &#x41;",
    "Привет мир",
    "   ",
    "a--b__c",
    "ﬁ ligature ½",
    "emoji 🍰 cake",
    "&#99999999; &#65;",
]
SHUFFLE_SEEDS = ["abc", "seed 2", "", "ünï 🍰"]
SHUFFLE_SIZES = [1, 2, 5, 17, 100]
JSON_INPUTS = [
    "{bad",
    '{"a":1,}',
    "[1,]",
    '{"a" 1}',
    '{"a":1 "b":2}',
    "[1 2]",
    '"abc',
    '"a\\x"',
    '"\\u12"',
    '"\\u12zz"',
    "1 2",
    "",
    " ",
    "-",
    "01",
    '{"a":"\u0001"}',
    "[",
    "{",
    '{"a":',
    "nul",
    '"\\ud83c\\udf70" x',
    '{"a": [1, 2.5, -0, 1e3]}',
]
UUID_INPUTS = [
    "bad",
    "notauuid",
    "29bf7010-a6ba-4881-9607-9c57b83d41e",
    "29bf7010-a6ba4881-9607-9c57b83d41ea",
    "29bf7010a6ba-4881-9607-9c57b83d41ea-",
    "29bf7010-a6ba-1881-9607-9c57b83d41ea",
    "{29bf7010-a6ba-4881-9607-9c57b83d41eg}",
    "29bf7010-a6ba-4881-9607-9c57b83d41éa",
    "é9bf7010-a6ba-4881-9607-9c57b83d41ea",
    "{29bf7010-a6ba-4881-9607-9c57b83d41ea}",
    "urn:uuid:29bf7010-a6ba-4881-9607-9c57b83d41ea",
    "29BF7010A6BA488196079C57B83D41EA",
    "urn:uuid:29bf7010-a6ba-4881-9607-9c57b83d41eg",
    "{29bf7010-a6ba-4881-9607-9c57b83d41e}",
    "{29bf7010a6ba}",
]


def shuffled(seed: str, n: int) -> list[int]:
    order = list(range(n))
    random.seed(seed)
    random.shuffle(order)
    return order


def json_error(text: str) -> list | None:
    try:
        json.loads(text)
        return None
    except json.JSONDecodeError as e:
        return [e.msg, e.pos]


def uuid_result(text: str) -> str:
    try:
        return str(TypeAdapter(UUID4).validate_python(text))
    except ValidationError as e:
        return e.errors()[0]["msg"]


sys.stdout.write(
    json.dumps(
        {
            "slugify": {name: slugify(name) for name in SLUG_INPUTS},
            "shuffle": {f"{seed}|{n}": shuffled(seed, n) for seed in SHUFFLE_SEEDS for n in SHUFFLE_SIZES},
            "json": {text: json_error(text) for text in JSON_INPUTS},
            "uuid": {text: uuid_result(text) for text in UUID_INPUTS},
        },
        ensure_ascii=False,
        indent=1,
    )
    + "\n"
)
