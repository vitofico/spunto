"""One optional AI grant shared by Collections and Decisions."""

from spunto.host import Plugin

from .collections import collections
from .decisions import decisions

plugin = Plugin("note-assist")
plugin.route("collections", method="POST")(collections)
plugin.route("decisions", method="POST")(decisions)
