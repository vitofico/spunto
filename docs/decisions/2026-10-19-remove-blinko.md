# Remove Import from Blinko

The owner requested removal of the Import from Blinko plugin. Its bundled module,
plugin-manager import form, reports and CLI options are removed. Import Markdown
remains available through the plugin manager and the stopped-server operator CLI.

Existing imported notes and attachments remain ordinary library content. Removal
does not alter their bodies, labels, timestamps, privacy, history or source/import
identifiers. No migration deletes library content or plugin records.

The existing missing-plugin lifecycle retires saved importer state and invalidates
queued or running work. An absent importer with a known bundled origin is omitted
from the plugin catalog, so its old card does not remain in Settings. Historical
metadata and private records remain stored. Unknown and operator-installed removed
plugins retain the existing removed-card and cleanup behavior.

Historical design documents and import acceptance records remain as evidence;
they no longer describe an available importer. The current operations guide and
image smoke test use the remaining bundled catalog.
