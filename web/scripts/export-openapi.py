"""Export the app contract without startup, network calls or opening a user database."""

import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server" / "src"))
from spunto.api.deps import Services  # noqa: E402 - load the workspace server after its path
from spunto.config import Settings  # noqa: E402
from spunto.main import create_app  # noqa: E402

with tempfile.TemporaryDirectory(prefix="spunto-openapi-") as directory:
    settings = Settings(
        public_url="http://localhost:8080", data_dir=Path(directory), _env_file=None
    )
    # OpenAPI inspects routes only: no request or lifespan executes.
    app = create_app(Services(settings, None))
    target = ROOT / "web" / "src" / "lib" / "openapi.json"
    target.write_text(json.dumps(app.openapi(), indent=2, sort_keys=True) + "\n")
