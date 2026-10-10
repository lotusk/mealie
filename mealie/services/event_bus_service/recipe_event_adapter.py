"""Private delivery adapter for Java-created recipes; it performs no recipe or timeline writes."""

import hashlib
import hmac
import json
import os
import threading
import time
from datetime import datetime
from pathlib import Path
from uuid import UUID

from dotenv import dotenv_values
from fastapi import APIRouter, BackgroundTasks, HTTPException, Request, Response
from sqlalchemy import select

from mealie.core.config import get_app_settings
from mealie.core.root_logger import get_logger
from mealie.db.db_setup import session_context
from mealie.db.models.group import Group
from mealie.db.models.recipe import RecipeModel
from mealie.db.models.users.users import User
from mealie.lang.providers import get_locale_provider
from mealie.services import urls
from mealie.services.event_bus_service.event_bus_service import EventBusService
from mealie.services.event_bus_service.event_types import (
    Event,
    EventBusMessage,
    EventOperation,
    EventRecipeData,
    EventTypes,
)

router = APIRouter(include_in_schema=False)
logger = get_logger()
_seen: dict[UUID, float] = {}
_lock = threading.Lock()


def _key() -> str:
    configured = os.environ.get("RECIPE_EVENT_ADAPTER_KEY")
    if configured is not None:
        return configured
    return dotenv_values(Path(__file__).resolve().parents[3] / ".env").get("RECIPE_EVENT_ADAPTER_KEY") or ""


@router.post("/internal/recipe-created", status_code=204)
async def deliver_recipe_created(request: Request, background: BackgroundTasks) -> Response:
    key = _key()
    if len(key.encode()) < 32:
        raise HTTPException(404, "Not Found")
    body = await request.body()
    if len(body) > 8192:
        raise HTTPException(413, "Event metadata is too large")
    timestamp = request.headers.get("X-Mealie-Event-Time", "")
    signature = request.headers.get("X-Mealie-Event-Signature", "")
    try:
        if not timestamp.isascii():
            raise ValueError("non-ASCII signature timestamp")
        sent_at = int(timestamp)
        if abs(time.time() - sent_at) > 60:
            raise ValueError("expired signature")
    except ValueError as exc:
        raise HTTPException(401, "Invalid event signature") from exc
    expected = hmac.new(key.encode(), timestamp.encode("ascii") + b"." + body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise HTTPException(401, "Invalid event signature")
    try:
        data = json.loads(body)
        event_id, recipe_id, user_id, group_id, household_id = (
            UUID(data[field]) for field in ("eventId", "recipeId", "userId", "groupId", "householdId")
        )
        if any(value.version != 4 for value in (event_id, recipe_id, user_id, group_id, household_id)):
            raise ValueError("expected UUID4")
        event_timestamp = datetime.fromisoformat(data["timestamp"])
        if event_timestamp.tzinfo is None:
            raise ValueError("timestamp must have timezone")
        locale, integration_id = data["locale"], data["integrationId"]
        if not isinstance(locale, str) or not isinstance(integration_id, str):
            raise ValueError("expected strings")
    except (ValueError, KeyError, TypeError) as exc:
        raise HTTPException(422, "Invalid recipe event metadata") from exc

    # Resolve committed names and ownership rather than trusting a caller-supplied name, slug, URL or destination.
    with session_context() as session:
        row = session.execute(
            select(RecipeModel.name, RecipeModel.slug, Group.slug)
            .join(User, RecipeModel.user_id == User.id)
            .join(Group, RecipeModel.group_id == Group.id)
            .where(
                RecipeModel.id == recipe_id,
                RecipeModel.user_id == user_id,
                RecipeModel.group_id == group_id,
                User.household_id == household_id,
                User.group_id == group_id,
            )
        ).one_or_none()
    if row is None:
        raise HTTPException(404, "Recipe event ownership does not match")
    with _lock:
        now = time.time()
        for old_id in [item for item, recorded in _seen.items() if now - recorded > 120]:
            del _seen[old_id]
        if event_id in _seen:
            raise HTTPException(409, "Recipe event already accepted")
        _seen[event_id] = now
    translator = get_locale_provider(locale)
    message = translator.t(
        "notifications.generic-created-with-url",
        name=row[0],
        url=urls.recipe_url(row[2], row[1], get_app_settings().BASE_URL),
    )
    event = Event(
        message=EventBusMessage.from_type(EventTypes.recipe_created, body=message, translator=translator),
        event_type=EventTypes.recipe_created,
        integration_id=integration_id,
        document_data=EventRecipeData(operation=EventOperation.create, recipe_slug=row[1]),
    )
    event.event_id, event.timestamp = event_id, event_timestamp
    bus = EventBusService(translator=translator)
    background.add_task(bus._publish_event, event, group_id, household_id)
    logger.info("RECIPE_CREATED_EVENT_ADAPTER event=%s recipe=%s", event_id, recipe_id)
    return Response(status_code=204)
