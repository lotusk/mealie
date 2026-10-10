import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from mealie.core.config import get_app_settings
from mealie.routes.internal.controller_events import SIGNATURE_HEADER, signature_for
from mealie.services.event_bus_service.event_bus_service import EventBusService
from mealie.services.event_bus_service.event_types import EventTagData, EventTypes
from tests.utils.fixture_schemas import TestUser

ROUTE = "/api/internal/events"


def tag_event(group_id: str, issued_at: datetime | None = None, **overrides) -> dict:
    event = {
        "issuedAt": (issued_at or datetime.now(UTC)).isoformat(),
        "integrationId": "generic",
        "groupId": group_id,
        "householdId": None,
        "eventType": "tag_created",
        "documentData": {"documentType": "tag", "operation": "create", "tagId": str(uuid4())},
        "message": {
            "key": "notifications.generic-created-with-url",
            "params": {"name": "Dessert"},
            "url": {"type": "tag", "slug": "dessert"},
        },
    }
    event.update(overrides)
    return event


def post(api_client: TestClient, event: dict, signature: str | None = None):
    body = json.dumps(event).encode()
    signature = signature if signature is not None else signature_for(body, get_app_settings().SECRET)
    return api_client.post(
        ROUTE, content=body, headers={"Content-Type": "application/json", SIGNATURE_HEADER: signature}
    )


@pytest.fixture
def dispatched(monkeypatch: pytest.MonkeyPatch) -> list[dict]:
    calls: list[dict] = []
    monkeypatch.setattr(EventBusService, "dispatch", lambda self, **kwargs: calls.append(kwargs))
    return calls


def test_signed_event_is_dispatched_like_a_python_event(
    api_client: TestClient, unique_user: TestUser, dispatched: list[dict]
):
    event = tag_event(str(unique_user.group_id))
    response = post(api_client, event)

    assert response.status_code == 202
    assert len(dispatched) == 1
    call = dispatched[0]
    assert call["event_type"] is EventTypes.tag_created
    assert str(call["group_id"]) == str(unique_user.group_id)
    assert call["household_id"] is None
    assert isinstance(call["document_data"], EventTagData)
    assert str(call["document_data"].tag_id) == event["documentData"]["tagId"]
    assert call["message"].startswith("Dessert has been created, ")
    assert call["message"].endswith("/recipes/tags/dessert")


@pytest.mark.parametrize("signature", ["", "0" * 64])
def test_unsigned_or_forged_events_are_rejected(
    api_client: TestClient, unique_user: TestUser, dispatched: list[dict], signature: str
):
    assert post(api_client, tag_event(str(unique_user.group_id)), signature).status_code == 401
    assert dispatched == []


@pytest.mark.parametrize(
    "overrides",
    [
        {"issuedAt": (datetime.now(UTC) - timedelta(minutes=10)).isoformat()},
        {"eventType": "not_an_event"},
        {"documentData": {"documentType": "shopping_list", "shoppingListId": str(uuid4())}},
        {"documentData": {"documentType": "tag", "operation": "create"}},
    ],
)
def test_invalid_events_are_rejected(
    api_client: TestClient, unique_user: TestUser, dispatched: list[dict], overrides: dict
):
    assert post(api_client, tag_event(str(unique_user.group_id), **overrides)).status_code == 400
    assert dispatched == []
