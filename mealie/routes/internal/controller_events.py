"""
Event dispatch for the Java backend (backend-java/), which takes over API routes during the migration but can't
run the event bus itself: Apprise only exists in Python. After a write, Java posts the event here and it goes through
the same EventBusService as an event published by a Python controller.

Requests are signed with a key derived from the secret both backends share (see EventBridge.java). The gateway
refuses /api/internal/* from outside, so only the Java backend reaches this route.
"""

import hashlib
import hmac
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import UUID4, ValidationError

from mealie.core.config import get_app_settings
from mealie.lang.providers import Translator, get_locale_provider
from mealie.schema._mealie import MealieModel
from mealie.services import urls
from mealie.services.event_bus_service.event_bus_service import EventBusService
from mealie.services.event_bus_service.event_types import EventDocumentDataBase, EventTagData, EventTypes

router = APIRouter(prefix="/events")

INTERNAL_EVENTS_KEY_CONTEXT = b"mealie-internal-events-v1"
SIGNATURE_HEADER = "X-Mealie-Signature"
MAX_CLOCK_SKEW = timedelta(minutes=5)

# The events Java may publish, keyed by document type. Extend as routes migrate.
DOCUMENT_TYPES: dict[str, type[EventDocumentDataBase]] = {
    "tag": EventTagData,
}
URL_BUILDERS: dict[str, Callable[[str, str | None], str]] = {
    "tag": urls.tag_url,
}


class InternalEventUrl(MealieModel):
    type: str
    slug: str


class InternalEventMessage(MealieModel):
    key: str
    params: dict[str, str] = {}
    url: InternalEventUrl | None = None


class InternalEvent(MealieModel):
    issued_at: datetime
    integration_id: str
    group_id: UUID4
    household_id: UUID4 | None = None
    event_type: str
    document_data: dict
    message: InternalEventMessage


def signature_for(body: bytes, secret: str) -> str:
    key = hmac.new(secret.encode(), INTERNAL_EVENTS_KEY_CONTEXT, hashlib.sha256).digest()
    return hmac.new(key, body, hashlib.sha256).hexdigest()


def _bad_request(detail: str) -> HTTPException:
    return HTTPException(status.HTTP_400_BAD_REQUEST, detail)


@router.post("", status_code=status.HTTP_202_ACCEPTED)
async def dispatch_internal_event(
    request: Request,
    event_bus: EventBusService = Depends(EventBusService.as_dependency),
    translator: Translator = Depends(get_locale_provider),
) -> None:
    body = await request.body()
    expected = signature_for(body, get_app_settings().SECRET)
    if not hmac.compare_digest(expected, request.headers.get(SIGNATURE_HEADER, "")):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED)

    try:
        event = InternalEvent.model_validate_json(body)
    except ValidationError as e:
        raise _bad_request("invalid event") from e

    issued_at = event.issued_at if event.issued_at.tzinfo else event.issued_at.replace(tzinfo=UTC)
    if abs(datetime.now(UTC) - issued_at) > MAX_CLOCK_SKEW:
        raise _bad_request("stale event")

    try:
        event_type = EventTypes[event.event_type]
    except KeyError as e:
        raise _bad_request(f"unknown event type {event.event_type}") from e

    document_type = DOCUMENT_TYPES.get(str(event.document_data.get("documentType")))
    if document_type is None:
        raise _bad_request("unsupported document type")
    try:
        document_data = document_type.model_validate(event.document_data)
    except ValidationError as e:
        raise _bad_request("invalid document data") from e

    params = dict(event.message.params)
    if event.message.url:
        url_builder = URL_BUILDERS.get(event.message.url.type)
        if url_builder is None:
            raise _bad_request("unsupported url type")
        params["url"] = url_builder(event.message.url.slug, get_app_settings().BASE_URL)

    event_bus.dispatch(
        integration_id=event.integration_id,
        group_id=event.group_id,
        household_id=event.household_id,
        event_type=event_type,
        document_data=document_data,
        message=translator.t(event.message.key, **params),
    )
