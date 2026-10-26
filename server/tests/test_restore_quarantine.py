import pytest

from spunto.config import OperatorError
from spunto.main import startup


@pytest.mark.anyio
async def test_restore_quarantine_prevents_startup_before_database_access(settings):
    settings.data_dir.mkdir(parents=True)
    (settings.data_dir / "RESTORE-QUARANTINE.json").write_text('{"state":"quarantined"}')
    with pytest.raises(OperatorError, match="quarantined"):
        async with startup(settings):
            pytest.fail("Restored data must not serve before current deletion reconciliation")
