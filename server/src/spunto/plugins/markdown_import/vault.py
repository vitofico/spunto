"""Translate simple unambiguous Obsidian wiki links into portable Markdown links."""

import posixpath
import re
from collections import defaultdict
from pathlib import PurePosixPath
from urllib.parse import quote

from markdown_it import MarkdownIt

from spunto.core.text import _code_mask, _escaped

from .evernote import markdown_literal

WIKI_LINK = re.compile(r"!?\[\[([^\[\]\n|#]+)(#[^\[\]\n|]+)?(?:\|([^\[\]\n]+))?\]\]")


class VaultLinks:
    def __init__(self, files):
        self.files = files
        wrapper, _, _ = next(iter(files)).partition("/")
        self.root = wrapper if all(path.startswith(wrapper + "/") for path in files) else ""
        self.names = defaultdict(list)
        for path in files:
            filename = PurePosixPath(path).name
            self.names[filename].append(path)
            if filename.lower().endswith(".md"):
                self.names[filename[:-3]].append(path)

    def resolve(self, source, name):
        folder = posixpath.dirname(source)
        relative = posixpath.normpath(posixpath.join(folder, name))
        rooted = posixpath.normpath(posixpath.join(self.root, name))
        for candidate in (relative, relative + ".md", rooted, rooted + ".md"):
            if candidate in self.files:
                return candidate
        matches = self.names.get(name, [])
        return matches[0] if len(matches) == 1 else None

    def rewrite(self, source, body):
        missing = 0
        protected = _code_mask(body)
        offsets = [0]
        for line in body.splitlines(keepends=True):
            offsets.append(offsets[-1] + len(line))
        for token in MarkdownIt().parse(body):
            if token.type in {"code_block", "fence"} and token.map:
                start, end = (offsets[line] for line in token.map)
                protected[start:end] = b"\1" * (end - start)

        def replace(match):
            nonlocal missing
            if any(protected[match.start() : match.end()]) or _escaped(body, match.start()):
                return match[0]
            name, fragment, alias = match.groups()
            target = self.resolve(source, name.strip())
            embed = match[0].startswith("!")
            if target is None or fragment or (embed and target.lower().endswith(".md")):
                missing += 1
                return match[0]
            relative = posixpath.relpath(target, posixpath.dirname(source) or ".")
            label = markdown_literal((alias or name).strip())
            is_image = PurePosixPath(target).suffix.lower() in {
                ".png",
                ".jpg",
                ".jpeg",
                ".gif",
                ".webp",
            }
            return f"{'!' if embed and is_image else ''}[{label}]({quote(relative, safe='/-_.')})"

        return WIKI_LINK.sub(replace, body), missing
