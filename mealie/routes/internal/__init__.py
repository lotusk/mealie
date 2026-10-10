from fastapi import APIRouter

from . import controller_events

router = APIRouter(prefix="/internal", include_in_schema=False)
router.include_router(controller_events.router)
