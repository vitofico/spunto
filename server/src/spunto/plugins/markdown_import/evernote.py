"""Bounded ENEX and ENML conversion for the existing Markdown import writer."""

import base64
import binascii
import hashlib
import json
import mimetypes
import re
from collections import defaultdict
from datetime import UTC, datetime
from html.entities import name2codepoint
from pathlib import PurePosixPath
from urllib.parse import quote, urlsplit
from xml.etree.ElementTree import TreeBuilder  # noqa: S405 - fed only by the bounded Expat parser
from xml.parsers import expat  # noqa: S406 - entity declarations and external resolution disabled


def xml_tree(data, root_name):
    """Accept Evernote's external DTD declaration without loading or expanding it."""
    tree = TreeBuilder()
    parser = expat.ParserCreate()
    depth = count = 0

    def start(tag, attributes):
        nonlocal depth, count
        depth += 1
        count += 1
        if depth > 48 or count > 150_000:
            raise ValueError("The Evernote export is too deeply nested or complex")
        tree.start(tag, attributes)

    def end(tag):
        nonlocal depth
        depth -= 1
        tree.end(tag)

    def doctype(name, _system_id, _public_id, internal):
        if name != root_name or internal:
            raise ValueError("The Evernote export contains an unsupported XML declaration")

    def skipped_entity(name, parameter):
        if root_name != "en-note" or parameter or name not in name2codepoint:
            raise ValueError("The Evernote export contains an unsupported XML entity")
        tree.data(chr(name2codepoint[name]))

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = tree.data
    parser.StartDoctypeDeclHandler = doctype
    parser.SkippedEntityHandler = skipped_entity
    parser.ExternalEntityRefHandler = lambda *_: 0
    try:
        parser.Parse(data, True)
        root = tree.close()
    except expat.ExpatError:
        raise ValueError("Choose a valid Evernote ENEX export") from None
    if root.tag != root_name:
        raise ValueError("Choose a valid Evernote ENEX export")
    return root


def markdown_literal(text):
    text = text.replace("&", "&amp;")
    return re.sub(r"([!#*_<>`\[\]\\~])", r"\\\1", text)


def timestamp(value):
    if value is None:
        return None
    try:
        return datetime.strptime(value, "%Y%m%dT%H%M%SZ").replace(tzinfo=UTC).isoformat()
    except ValueError:
        raise ValueError("An Evernote note has an invalid timestamp") from None


def resource_file(resource, maximum):
    data = resource.find("data")
    if data is None or data.get("encoding") != "base64":
        raise ValueError("An Evernote attachment has invalid data")
    try:
        content = base64.b64decode("".join((data.text or "").split()), validate=True)
    except binascii.Error:
        raise ValueError("An Evernote attachment has invalid data") from None
    if len(content) > maximum:
        raise ValueError("An Evernote attachment exceeds the import limit")
    mime = resource.findtext("mime", "")
    digest = hashlib.md5(content, usedforsecurity=False).hexdigest()  # ENML resource locator
    suffix = mimetypes.guess_extension(mime) or ""
    raw_name = resource.findtext("resource-attributes/file-name") or "attachment" + suffix
    filename = re.sub(r"[\x00-\x1f\x7f\\/:]", "_", raw_name).strip(". ")
    filename = filename[:200] or "attachment"
    if PurePosixPath(filename).suffix.lower() == ".md":
        filename += ".txt"  # A linked Markdown resource must stay an attachment.
    return digest, f"assets/{digest}/{filename}", content, mime.startswith("image/")


class Markdown:
    def __init__(self, resources):
        self.resources = resources
        self.missing = 0
        self.unsupported = 0
        self.linked_assets = set()

    def children(self, node):
        return markdown_literal(node.text or "") + "".join(
            self.render(child) + markdown_literal(child.tail or "") for child in node
        )

    def media(self, node):
        resource = self.resources.get(node.get("hash", "").lower())
        if resource is None:
            self.missing += 1
            return ""
        path, image = resource
        self.linked_assets.add(path)
        label = markdown_literal(PurePosixPath(path).name)
        return f"{'!' if image else ''}[{label}]({quote(path, safe='/-_.')})"

    def render(self, node):
        tag = node.tag
        if tag == "en-media":
            return self.media(node)
        if tag in {"en-crypt", "img", "object", "script", "style"}:
            self.unsupported += 1
            return ""
        if tag == "en-todo":
            return "- [x] " if node.get("checked") == "true" else "- [ ] "
        if tag == "br":
            return "  \n"
        if tag == "hr":
            return "\n\n---\n\n"
        if tag in {"code", "pre"}:
            text = "".join(node.itertext())
            fence = "`" * max(
                1 if tag == "code" else 3, max(map(len, re.findall(r"`+", text)), default=0) + 1
            )
            if tag == "pre":
                return f"\n\n{fence}\n{text}\n{fence}\n\n"
            return f"{fence} {text} {fence}"
        if tag in {"ul", "ol"}:
            lines = []
            for number, item in enumerate(node, 1):
                marker = "-" if tag == "ul" else f"{number}."
                lines.append(f"{marker} " + self.children(item).strip().replace("\n", "\n    "))
            return "\n\n" + "\n".join(lines) + "\n\n"
        content = self.children(node)
        if tag in {"div", "p", "section", "tr"}:
            return content + "\n\n"
        if tag in {"td", "th"}:
            return content + " "
        if re.fullmatch(r"h[1-6]", tag):
            return f"\n\n{'#' * int(tag[1])} {content.strip()}\n\n"
        if tag in {"strong", "b", "em", "i", "s", "strike", "del"} and content.strip():
            marker = "**" if tag in {"strong", "b"} else "*" if tag in {"em", "i"} else "~~"
            return marker + content.strip() + marker
        if tag == "blockquote":
            return "\n\n" + "\n".join("> " + line for line in content.strip().splitlines()) + "\n\n"
        if tag == "a":
            href = node.get("href", "")
            try:
                scheme = urlsplit(href).scheme
            except ValueError:
                scheme = ""
            if scheme in {"http", "https", "mailto"}:
                return f"[{content}]({quote(href, safe=':/?&=%#@+,;~-._')})"
            if href:
                self.unsupported += 1
        return content


def convert(data, *, max_notes, max_attachment_bytes, max_expanded_bytes):
    root = xml_tree(data, "en-export")
    files = {}
    occurrences = defaultdict(int)
    unsupported = missing = expanded = 0
    for index, note in enumerate(root, 1):
        if note.tag != "note" or index > max_notes:
            raise ValueError("Choose an Evernote export with 1 to 10,000 notes")
        title = " ".join(note.findtext("title", "").split()) or "Untitled"
        content = note.findtext("content")
        if content is None or len(content) > 400_000:
            raise ValueError("An Evernote note is missing its content or exceeds the import limit")
        resources = {}
        assets = {}
        for entry in note.findall("resource"):
            digest, path, binary, image = resource_file(entry, max_attachment_bytes)
            if digest in resources and assets[resources[digest][0]] != binary:
                raise ValueError("An Evernote note contains conflicting attachment identifiers")
            resources[digest] = (path, image)
            assets[path] = binary
        render = Markdown(resources)
        body = render.children(xml_tree(content.strip(), "en-note")).strip()
        metadata = {
            "tags": [
                "-".join(tag.split()) for tag in (item.text or "" for item in note.findall("tag"))
            ],
            "created": timestamp(note.findtext("created")),
            "updated": timestamp(note.findtext("updated")),
        }
        document = (
            "---\n"
            + "\n".join(
                f"{key}: {json.dumps(value, ensure_ascii=False)}" for key, value in metadata.items()
            )
            + "\n---\n# "
            + markdown_literal(title)
            + "\n\n"
            + body
        ).encode("utf-8")
        identity = hashlib.sha256(document)
        for path, value in sorted(assets.items()):
            identity.update(path.encode())
            identity.update(hashlib.sha256(value).digest())
        key = identity.hexdigest()
        ordinal = occurrences[key]
        occurrences[key] += 1
        prefix = f"{key}-{ordinal}"
        files[f"{prefix}/note.md"] = document
        files.update({f"{prefix}/{path}": value for path, value in assets.items()})
        expanded += len(document) + sum(map(len, assets.values()))
        if expanded > max_expanded_bytes:
            raise ValueError("The Evernote export exceeds 64 MB expanded")
        unsupported += render.unsupported + len(assets.keys() - render.linked_assets)
        missing += render.missing
    return files, unsupported, missing
