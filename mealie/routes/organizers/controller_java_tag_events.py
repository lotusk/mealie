"""Signed event bridge for Java-owned tag mutations during the backend migration."""

import jwt
from fastapi import APIRouter, Header, HTTPException, status
from pydantic import UUID4

from mealie.routes._base import BaseCrudController, controller
from mealie.services import urls
from mealie.services.event_bus_service.event_types import EventOperation, EventTagData, EventTypes

router = APIRouter(prefix="/internal/java", include_in_schema=False)


@controller(router)
class JavaTagEventController(BaseCrudController):
    @router.post("/tag-events", status_code=status.HTTP_204_NO_CONTENT)
    def publish_tag_event(self, signature: str = Header(alias="X-Mealie-Tag-Event")) -> None:
        self.checks.can_organize()
        try:
            event = jwt.decode(
                signature,
                self.settings.SECRET,
                algorithms=["HS256"],
                audience="mealie-java-tag-event",
                options={"require": ["exp", "aud", "operation", "id", "groupId", "name", "slug"]},
            )
            if event["groupId"] != str(self.group_id):
                raise ValueError("group mismatch")
            operation = EventOperation(event["operation"])
            tag_id = UUID4(event["id"])
            event_type = {
                EventOperation.create: EventTypes.tag_created,
                EventOperation.update: EventTypes.tag_updated,
                EventOperation.delete: EventTypes.tag_deleted,
            }[operation]
        except (jwt.PyJWTError, ValueError, KeyError) as error:
            raise HTTPException(status.HTTP_403_FORBIDDEN) from error

        message = (
            self.t("notifications.generic-deleted", name=event["name"])
            if operation is EventOperation.delete
            else self.t(
                "notifications.generic-created-with-url"
                if operation is EventOperation.create
                else "notifications.generic-updated-with-url",
                name=event["name"],
                url=urls.tag_url(event["slug"], self.settings.BASE_URL),
            )
        )
        self.publish_event(
            event_type=event_type,
            document_data=EventTagData(operation=operation, tag_id=tag_id),
            group_id=self.group_id,
            household_id=None,
            message=message,
        )
