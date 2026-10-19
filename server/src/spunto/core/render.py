"""A single sanitized Markdown renderer; remote images never load on read."""

import re
import uuid
from bisect import bisect_right
from html import escape
from urllib.parse import quote

import nh3
from markdown_it import MarkdownIt

from spunto.core.text import _code_mask, labels

_MARKDOWN = MarkdownIt("commonmark", {"html": False})


def _image(tokens, idx, options, env):
    token = tokens[idx]
    src = token.attrGet("src") or ""
    text = escape(token.content or "Image")
    if src.startswith("/a/"):
        return f'<img src="{escape(src, quote=True)}" alt="{text}">'
    return f'<a href="{escape(src, quote=True)}">{text}</a>'


def _label_rule(state, silent):
    if state.src[state.pos] != "#" or state.linkLevel:
        return False
    matches = getattr(state, "spunto_labels", None)
    if matches is None:
        matches = state.spunto_labels = {label.start: label for label in labels(state.src)}
    label = matches.get(state.pos)
    if label is None or label.end > state.posMax:
        return False
    if not silent:
        token = state.push("spunto_label", "a", 0)
        token.content = state.src[label.start : label.end]
        token.meta = {"path": label.path}
    state.pos = label.end
    return True


def _label(tokens, idx, options, env):
    token = tokens[idx]
    href = "/l/" + quote(token.meta["path"], safe="/")
    return f'<a class="label" href="{escape(href, quote=True)}">{escape(token.content)}</a>'


def _text(tokens, idx, options, env):
    text = tokens[idx].content
    nonce = env.get("spunto_nonce")
    if not nonce:
        return escape(text, quote=False)
    parts = []
    start = 0
    for match in re.finditer(re.escape(nonce) + r"t(\d+)\uE001", text):
        parts.append(escape(text[start : match.start()], quote=False))
        item = env["spunto_tasks"].get(int(match[1]))
        if item is None:
            parts.append(escape(match[0], quote=False))
        else:
            checked = ' checked=""' if item["checked"] else ""
            parts.append(
                f'<input type="checkbox" data-checkbox-index="{match[1]}" '
                f'data-checkbox-offset="{item["offset"]}" aria-label="Toggle task"{checked}>'
            )
        start = match.end()
    parts.append(escape(text[start:], quote=False))
    return "".join(parts)


_MARKDOWN.renderer.rules["image"] = _image
_MARKDOWN.renderer.rules["text"] = _text
_MARKDOWN.inline.add_terminator_char("#")
_MARKDOWN.inline.ruler.before("text", "spunto_label", _label_rule)
_MARKDOWN.renderer.rules["spunto_label"] = _label


def _taskify(tokens, env, body):
    lines = body.splitlines(keepends=True)
    line_offsets = [0]
    for line in lines:
        line_offsets.append(line_offsets[-1] + len(line))
    utf16 = [0]
    for char in body:
        utf16.append(utf16[-1] + (2 if ord(char) > 65535 else 1))
    nonce = "\ue000" + uuid.uuid4().hex
    env["spunto_nonce"] = nonce
    env["spunto_tasks"] = {}
    index = 0
    for token in tokens:
        if token.type != "inline" or not token.map:
            continue
        mask = _code_mask(token.content)
        inline_lines = token.content.splitlines()
        newline_offsets = [-1, *(match.start() for match in re.finditer("\n", token.content))]
        edits = []
        for match in re.finditer(r"(?m)^\s*\[([ xX])\]\s", token.content):
            at = match.start(1)
            if mask[at]:
                continue
            relative_line = bisect_right(newline_offsets, at) - 1
            line_no = token.map[0] + relative_line
            if line_no >= len(lines) or relative_line >= len(inline_lines):
                continue
            content_line = inline_lines[relative_line]
            within = at - (newline_offsets[relative_line] + 1)
            offset = lines[line_no].find(content_line)
            if offset < 0:
                continue
            source = line_offsets[line_no] + offset + within
            env["spunto_tasks"][index] = {"offset": utf16[source], "checked": match[1] != " "}
            edits.append((at - 1, at + 2, nonce + "t" + str(index) + "\ue001"))
            index += 1
        if edits:
            parts = []
            start = 0
            for left, right, value in sorted(edits):
                parts.extend((token.content[start:left], value))
                start = right
            parts.append(token.content[start:])
            content = "".join(parts)
            token.children = []
            _MARKDOWN.inline.parse(content, _MARKDOWN, env, token.children)


def render_markdown(body: str) -> str:
    env = {}
    tokens = _MARKDOWN.parse(body, env)
    _taskify(tokens, env, body)
    tags = set(nh3.ALLOWED_TAGS) | {"input"}
    attributes = {key: set(value) for key, value in nh3.ALLOWED_ATTRIBUTES.items()}
    attributes.setdefault("a", set()).add("class")
    attributes["input"] = {
        "type",
        "checked",
        "data-checkbox-index",
        "data-checkbox-offset",
        "aria-label",
    }
    return nh3.clean(
        _MARKDOWN.renderer.render(tokens, _MARKDOWN.options, env),
        tags=tags,
        attributes=attributes,
        link_rel="noopener noreferrer nofollow",
    )
