"""Tag removal stays an explicit owner decision and uses Markdown label tokens."""

import pytest
from test_suggestions import OWNER, stored

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.suggestions import decide, suggest, validate_payload
from spunto.host import Ctx, ManifestError

pytestmark = pytest.mark.anyio
pytest_plugins = ("test_suggestions",)


async def test_removal_is_pending_until_owner_accepts_and_preserves_other_text(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    body = "Words #old/a #oldish `#old`\n```\n#old\n```"
    note = await domain.create(body, private=True)
    another = await domain.create("Before #old after")
    proposal = await suggest(
        Ctx(host, "proposals"), None, "label_change", {"old": "old", "new": None}
    )
    assert proposal["status"] == "pending"
    assert (await domain.get(note["id"]))["body"] == body
    assert (await domain.get(another["id"]))["body"] == another["body"]
    for kind in ("device", "pat"):
        credential = Principal("fixture-" + kind, kind, "Fixture", "full", private_ok=True)
        with pytest.raises(Problem) as failure:
            await decide(host.store, credential, proposal["id"], "accept")
        assert failure.value.code == ("session_required" if kind == "device" else "read_only_token")
    assert (await stored(host, proposal["id"]))["status"] == "pending"
    result = await decide(host.store, OWNER, proposal["id"], "accept")
    assert result["applied"] and result["result"] == {"changed": 2}
    saved = await domain.get(note["id"])
    assert saved["body"] == "Words  #oldish `#old`\n```\n#old\n```"
    assert saved["labels"] == ["oldish"]
    assert saved["version"] == note["version"] + 1
    assert (await domain.get(another["id"]))["body"] == "Before  after"
    assert not (await decide(host.store, OWNER, proposal["id"], "accept"))["applied"]
    assert (await domain.get(note["id"]))["version"] == saved["version"]


async def test_null_is_only_a_rename_removal_destination(proposal_host):
    host = proposal_host
    note = await Notes(host.store, OWNER).create("Unlabeled")
    assert validate_payload("label_change", {"old": "old", "new": None}) == {
        "old": "old",
        "new": None,
    }
    for payload in (
        {"new": None, "note_ids": [note["id"]]},
        {"old": None, "new": "new"},
        {"old": "old", "new": None, "note_ids": [note["id"]]},
        {"old": "old"},
    ):
        with pytest.raises(ManifestError):
            validate_payload("label_change", payload)
