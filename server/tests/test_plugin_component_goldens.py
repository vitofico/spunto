"""The Android renderer replays the same component output the host returns."""

import json
import uuid
from pathlib import Path

import pytest

from spunto.auth.tokens import Principal
from spunto.host import validate_output

pytestmark = pytest.mark.anyio
FORMS = {
    "context-packs": "edit",
    "thinking-desk": "edit",
    "decision-journal": "record-decision",
}
REQUEST_ID = "00000000-0000-4000-8000-000000000001"
FIXTURE = (
    Path(__file__).parents[2] / "android/app/src/androidTest/assets/plugin-workflow-forms.json"
)


async def workflow_forms(server):
    host = server.services.host
    owner = Principal("fixture-owner", "session", "Fixture owner", "write", private_ok=True)
    outputs = {}
    for plugin_id, command in FORMS.items():
        await host.enable(plugin_id, permissions=host.manifests[plugin_id]["permissions"])
        result = await host.invoke_command(plugin_id, command, {}, principal=owner)
        validate_output(result)
        items = result["items"] if "items" in result else [result]
        form = next(item for item in items if item["type"] == "form")
        request_id = form["schema"]["properties"]["request_id"]
        uuid.UUID(request_id["default"])
        request_id["default"] = REQUEST_ID
        outputs[plugin_id] = result
    return outputs


async def test_android_workflow_form_goldens_match_the_host(server):
    encoded = json.dumps(await workflow_forms(server), ensure_ascii=False, indent=2) + "\n"
    assert FIXTURE.read_text() == encoded
