"""The same built-in theme tokens are shipped to the web and native clients."""

import copy
import hashlib
import json
import re
import tomllib
from functools import cache
from importlib.resources import files

from spunto.host.manifest import ID


@cache
def _themes():
    return json.loads(files("spunto.host").joinpath("theme_tokens.json").read_text())


def theme_ids():
    return tuple(_themes())


@cache
def theme_catalog():
    return json.loads(files("spunto.host").joinpath("theme_catalog.json").read_text())


COLOR_ROLES = (
    "paper",
    "raised",
    "sunken",
    "ink",
    "ink-2",
    "ink-3",
    "rule",
    "rule-strong",
    "field",
    "accent",
    "accent-strong",
    "accent-soft",
    "on-accent",
    "highlight",
    "danger",
    "warn",
)
CONTRAST_PAIRS = (
    *(
        (ink, paper, 4.5)
        for ink in ("ink", "ink-2", "ink-3", "accent", "accent-strong", "danger")
        for paper in ("paper", "sunken", "raised")
    ),
    *((ink, "accent-soft", 4.5) for ink in ("ink", "ink-2", "ink-3", "accent", "accent-strong")),
    ("ink", "highlight", 4.5),
    ("on-accent", "accent", 4.5),
    ("on-accent", "accent-strong", 4.5),
    *(("field", paper, 3) for paper in ("paper", "raised", "sunken")),
)


def contrast(first, second):
    def luminance(color):
        channels = [int(color[i : i + 2], 16) / 255 for i in (1, 3, 5)]
        return sum(
            weight * (channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4)
            for weight, channel in zip((0.2126, 0.7152, 0.0722), channels, strict=True)
        )

    low, high = sorted((luminance(first), luminance(second)))
    return (high + 0.05) / (low + 0.05)


def theme_errors(modes):
    """Validate colour-only packs without interpreting or loading executable assets."""
    errors = []
    for mode in ("light", "dark"):
        values = modes.get(mode) if isinstance(modes, dict) else None
        if not isinstance(values, dict) or any(
            not isinstance(values.get(role), str)
            or not re.fullmatch(r"#[0-9a-fA-F]{6}", values[role])
            for role in COLOR_ROLES
        ):
            errors.append(f"Missing or invalid colour roles in {mode}")
            continue
        for foreground, background, minimum in CONTRAST_PAIRS:
            ratio = contrast(values[foreground], values[background])
            if ratio < minimum:
                errors.append(
                    f"{foreground} on {background} is {ratio:.2f}:1 in {mode} "
                    f"(requires {minimum}:1)"
                )
    return errors


def theme_manifest(theme_id):
    return {"id": theme_id, "tokens": 1, **copy.deepcopy(_themes()[theme_id])}


ACCENT_ROLES = ("accent", "accent-strong", "accent-soft", "on-accent")


def _derived(modes):
    result = copy.deepcopy(modes)
    for mode, values in result.items():
        ink = [int(values["ink"][i : i + 2], 16) for i in (1, 3, 5)]
        paper = [int(values["paper"][i : i + 2], 16) for i in (1, 3, 5)]
        # Shadows and the scrim must darken the page: ink in light mode, black in dark
        # mode, where ink is a light colour.
        values["sh"] = " ".join(map(str, ink)) if mode == "light" else "0 0 0"
        values["chrome"] = "#" + "".join(
            f"{round(p * 0.94 + i * 0.06):02X}" for p, i in zip(paper, ink, strict=True)
        )
        values["chrome-pill"] = values["paper"] if mode == "light" else values["raised"]
    return result


class ThemeRegistry:
    """Instance-local, restart-discovered colour data; never import pack code."""

    def __init__(self, settings):
        self.cards = {}
        self.values = {key: theme_manifest(key) for key in theme_ids()}
        self.builtin_ids = frozenset(self.values)
        for key, value in self.values.items():
            self.cards[key] = {
                "id": key,
                "name": theme_catalog()[key]["name"],
                "status": "available",
                "tokens": 1,
                "light": copy.deepcopy(value["light"]),
                "dark": copy.deepcopy(value["dark"]),
                "accents": [],
            }
        directory = getattr(settings, "themes_dir", None) or settings.data_dir / "themes"
        if not directory.is_dir():
            return
        try:
            candidates = sorted(directory.iterdir())
        except OSError:
            self._failure(directory, "Theme directory is unreadable.")
            return
        for path in candidates:
            if not path.is_dir():
                continue
            try:
                self._pack(path)
            except OSError, ValueError, TypeError, KeyError:
                self._failure(path, "Theme files are missing, unreadable or invalid.")

    def _failure(self, path, reason, *, key=None, name=None):
        number = 0
        if key is None or key in self.cards:
            while True:
                key = (
                    "failed-"
                    + hashlib.sha256(f"{path.resolve()}:{number}".encode()).hexdigest()[:16]
                )
                if key not in self.cards:
                    break
                number += 1
        self.cards[key] = {
            "id": key,
            "name": name or "Unavailable colour pack",
            "status": "failed",
            "reason": reason,
            "tokens": 1,
            "accents": [],
        }

    def _pack(self, path):
        documents = []
        for filename in ("plugin.toml", "theme.toml"):
            file = path / filename
            if file.stat().st_size > 65_536:
                self._failure(path, "Theme file exceeds 64 KiB.")
                return
            documents.append(tomllib.loads(file.read_text()))
        manifest, colors = documents
        key = manifest.get("id")
        name = manifest.get("name")
        if (
            not isinstance(key, str)
            or not ID.fullmatch(key)
            or not isinstance(name, str)
            or not 1 <= len(name) <= 80
            or not isinstance(manifest.get("version"), str)
            or not 1 <= len(manifest["version"]) <= 80
            or manifest.get("kind") != "theme"
            or type(manifest.get("tokens")) is not int
            or manifest["tokens"] != 1
        ):
            self._failure(
                path, "Theme manifest needs valid id, name, version, kind=theme and tokens=1."
            )
            return
        if key in self.cards:
            self._failure(path, "Theme ID conflicts with an already installed theme.")
            return
        modes = {
            mode: {
                role: colors[mode].get(role) if isinstance(colors.get(mode), dict) else None
                for role in COLOR_ROLES
            }
            for mode in ("light", "dark")
        }
        errors = theme_errors(modes)
        accents = colors.get("accents", {})
        if not isinstance(accents, dict) or len(accents) > 20:
            self._failure(path, "Theme accents must be a table with at most 20 entries.")
            return
        choices = []
        for accent_id, accent in accents.items():
            if (
                not ID.fullmatch(accent_id)
                or not isinstance(accent, dict)
                or not isinstance(accent.get("name"), str)
                or not 1 <= len(accent["name"]) <= 80
            ):
                self._failure(path, "Theme accent requires a valid id and name.")
                return
            resolved = copy.deepcopy(modes)
            for mode in ("light", "dark"):
                override = accent.get(mode)
                if not isinstance(override, dict):
                    self._failure(
                        path, f"Theme accent {accent_id} needs light and dark colour tables."
                    )
                    return
                for role in ACCENT_ROLES:
                    resolved[mode][role] = override.get(role)
            errors.extend(f"Accent {accent_id}: {error}" for error in theme_errors(resolved))
            choices.append({"id": accent_id, "name": accent["name"], **resolved})
        if errors:
            self._failure(path, errors[0], key=key, name=name)
            return
        modes = _derived(modes)
        choices = [
            {**choice, **_derived({mode: choice[mode] for mode in ("light", "dark")})}
            for choice in choices
        ]
        self.values[key] = {"id": key, "tokens": 1, **modes}
        self.cards[key] = {
            "id": key,
            "name": name,
            "status": "available",
            "tokens": 1,
            **copy.deepcopy(modes),
            "accents": choices,
        }

    def available(self, key):
        return isinstance(key, str) and key in self.values

    def valid_accent(self, key, accent):
        return accent is None or any(
            item["id"] == accent for item in self.cards.get(key, {}).get("accents", [])
        )

    def selected(self, key, accent=None):
        key = key if self.available(key) else "carta"
        result = copy.deepcopy(self.values[key])
        if accent is not None:
            choice = next(
                (item for item in self.cards[key]["accents"] if item["id"] == accent), None
            )
            if choice:
                result.update(
                    light=copy.deepcopy(choice["light"]),
                    dark=copy.deepcopy(choice["dark"]),
                    accent=accent,
                )
        return result

    def catalog(self):
        return copy.deepcopy(list(self.cards.values()))

    def css(self, key, accent=None):
        if not self.available(key) or not self.valid_accent(key, accent):
            raise KeyError(key)
        value = self.selected(key, accent)
        selectors = f':root[data-theme="{key}"]'
        if accent is not None:
            selectors += f'[data-accent="{accent}"]'
        lines = []
        for mode in ("light", "dark"):
            rules = ";".join(
                f"--{role}:{value[mode][role]}"
                for role in (*COLOR_ROLES, "sh", "chrome", "chrome-pill")
            )
            lines.append(f'{selectors}[data-mode="{mode}"]{{{rules};color-scheme:{mode};}}')
            system = f'{selectors}[data-mode="system"]{{{rules};color-scheme:{mode};}}'
            lines.append(
                system if mode == "light" else f"@media(prefers-color-scheme:dark){{{system}}}"
            )
        return "\n".join(lines) + "\n"
