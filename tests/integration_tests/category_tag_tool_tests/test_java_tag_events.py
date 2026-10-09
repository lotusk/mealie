"""The Java tag bridge preserves notifications and accepts only signed, scoped events."""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import jwt
import pytest
from fastapi.testclient import TestClient
from pytest import MonkeyPatch

from mealie.core.config import get_app_settings
from mealie.services.event_bus_service.event_bus_service import EventBusService
from mealie.services.event_bus_service.event_types import EventTypes
from tests.utils.fixture_schemas import TestUser

ROUTE = "/api/internal/java/tag-events"


@pytest.mark.parametrize("operation", ["create", "update", "delete"])
def test_java_tag_event_dispatch(
    api_client: TestClient, unique_user: TestUser, monkeypatch: MonkeyPatch, operation: str
) -> None:
    events: list[dict] = []
    monkeypatch.setattr(EventBusService, "dispatch", lambda self, **kwargs: events.append(kwargs))
    tag_id = uuid4()
    signature = jwt.encode(
        {
            "aud": "mealie-java-tag-event",
            "exp": datetime.now(UTC) + timedelta(seconds=30),
            "operation": operation,
            "id": str(tag_id),
            "groupId": str(unique_user.group_id),
            "name": "Dinner",
            "slug": "dinner",
        },
        get_app_settings().SECRET,
        algorithm="HS256",
    )
    response = api_client.post(ROUTE, headers={**unique_user.token, "X-Mealie-Tag-Event": signature})
    assert response.status_code == 204
    assert len(events) == 1
    event = events[0]
    assert event["event_type"] is getattr(EventTypes, f"tag_{operation}d" if operation != "delete" else "tag_deleted")
    assert event["document_data"].tag_id == tag_id
    assert str(event["group_id"]) == unique_user.group_id
    assert event["household_id"] is None
    assert "Dinner" in event["message"]


@pytest.mark.parametrize("invalid", ["signature", "group", "expired", "audience"])
def test_java_tag_event_rejects_untrusted_events(
    api_client: TestClient, unique_user: TestUser, monkeypatch: MonkeyPatch, invalid: str
) -> None:
    events: list[dict] = []
    monkeypatch.setattr(EventBusService, "dispatch", lambda self, **kwargs: events.append(kwargs))
    signature = jwt.encode(
        {
            "aud": "wrong" if invalid == "audience" else "mealie-java-tag-event",
            "exp": datetime.now(UTC) + timedelta(seconds=-30 if invalid == "expired" else 30),
            "operation": "create",
            "id": str(uuid4()),
            "groupId": str(uuid4() if invalid == "group" else unique_user.group_id),
            "name": "Dinner",
            "slug": "dinner",
        },
        "invalid-secret-which-is-at-least-32-bytes" if invalid == "signature" else get_app_settings().SECRET,
        algorithm="HS256",
    )
    response = api_client.post(ROUTE, headers={**unique_user.token, "X-Mealie-Tag-Event": signature})
    assert response.status_code == 403
    assert events == []
