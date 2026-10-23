#!/usr/bin/env python3
"""Validate file-discovered Android and browser translation contracts."""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCALE_FOLDER = re.compile(
    r"values-(?:[a-z]{2,3}(?:-r(?:[A-Z]{2}|[0-9]{3}))?|b\+[a-z]{2,3}(?:\+[A-Za-z0-9]{2,8})*)"
)
LOCALE_TAG = re.compile(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT])?([A-Za-z%])")
PLACEHOLDER = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")
QUANTITIES = {"zero", "one", "two", "few", "many", "other"}


def arguments(text):
    """Java Formatter argument positions/types, including numbered reordering."""
    result = {}
    cursor = implicit = previous = 0
    while (start := text.find("%", cursor)) >= 0:
        match = FORMAT.match(text, start)
        if match is None:
            raise ValueError("invalid format argument")
        explicit, flags, date, conversion = match.groups()
        cursor = match.end()
        if conversion in ("%", "n"):
            continue
        if date is None and conversion not in "bBhHsScCdoxXeEfgGaA":
            raise ValueError("invalid format conversion")
        if "<" in flags:
            index = previous
        elif explicit:
            index = int(explicit)
        else:
            implicit += 1
            index = implicit
        if index < 1:
            raise ValueError("invalid format argument position")
        previous = index
        result.setdefault(index, set()).add(
            "t" + conversion if date else conversion.lower()
        )
    return result


def android_catalog(folder, translated=False):
    entries, issues = {}, []
    for file in sorted(folder.glob("*.xml")):
        try:
            raw = file.read_text(encoding="utf-8")
            if re.search(r"<!DOCTYPE|<!ENTITY", raw, re.IGNORECASE):
                raise ValueError("document types and entities are not allowed")
            tree = ET.fromstring(raw)
            if tree.tag != "resources":
                raise ValueError("expected resources root")
        except (OSError, ValueError, ET.ParseError) as error:
            issues.append(f"{file.name}: {error}")
            continue
        for node in tree:
            if node.tag not in ("string", "plurals", "string-array"):
                continue
            name = node.get("name", "")
            key = f"{node.tag}/{name}"
            if not re.fullmatch(r"[a-z][a-z0-9_]*", name):
                issues.append(f"invalid resource name {key}")
            if node.get("translatable") == "false":
                if translated:
                    issues.append(f"non-translatable resource {key}")
                continue
            formatted = node.get("formatted") != "false"
            variants = {}
            if node.tag == "string":
                variants[""] = "".join(node.itertext())
            else:
                for index, item in enumerate(node):
                    if item.tag != "item":
                        issues.append(f"unexpected child in {key}")
                        continue
                    variant = (
                        item.get("quantity", "")
                        if node.tag == "plurals"
                        else str(index)
                    )
                    if variant in variants:
                        issues.append(f"duplicate branch {key}[{variant}]")
                    variants[variant] = "".join(item.itertext())
                    if node.tag == "plurals" and variant not in QUANTITIES:
                        issues.append(f"invalid plural quantity {key}[{variant}]")
                if node.tag == "plurals" and "other" not in variants:
                    issues.append(f"missing other {key}")
            if not variants or any(
                not value.strip().strip('"').strip() for value in variants.values()
            ):
                issues.append(f"blank {key}")
            if formatted:
                for variant, text in variants.items():
                    try:
                        arguments(text)
                    except ValueError as error:
                        issues.append(f"format {key}[{variant}]: {error}")
            if key in entries:
                issues.append(f"duplicate {key}")
            entries[key] = (formatted, variants)
    return entries, issues


def android_roots(root):
    return [
        folder
        for folder in sorted((root / "android").glob("**/src/main/res"))
        if any(android_catalog(folder / "values"))
    ]


def check_android(root):
    folders = android_roots(root)
    languages = {
        child.name
        for folder in folders
        for child in folder.iterdir()
        if child.is_dir() and LOCALE_FOLDER.fullmatch(child.name)
    }
    issues = []
    for folder in folders:
        base, problems = android_catalog(folder / "values")
        prefix = str(folder.relative_to(root))
        issues.extend(f"{prefix}/values: {problem}" for problem in problems)
        for language in sorted(languages):
            target, problems = android_catalog(folder / language, translated=True)
            issues.extend(f"{prefix}/{language}: {problem}" for problem in problems)
            for key in sorted(base.keys() - target.keys()):
                issues.append(f"{prefix}/{language}: missing {key}")
            for key in sorted(target.keys() - base.keys()):
                issues.append(f"{prefix}/{language}: unexpected {key}")
            for key in sorted(base.keys() & target.keys()):
                source_formatted, source = base[key]
                target_formatted, translated = target[key]
                if source_formatted != target_formatted:
                    issues.append(f"{prefix}/{language}: formatted contract {key}")
                if (
                    key.startswith("string-array/")
                    and source.keys() != translated.keys()
                ):
                    issues.append(f"{prefix}/{language}: array entries {key}")
                if source_formatted and target_formatted:
                    for variant, text in translated.items():
                        reference = source.get(variant, source.get("other"))
                        if reference is None:
                            continue
                        try:
                            if arguments(reference) != arguments(text):
                                issues.append(
                                    f"{prefix}/{language}: format {key}[{variant}]"
                                )
                        except ValueError:
                            pass  # The malformed source or target was reported above.
    return issues


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key}")
        result[key] = value
    return result


def browser_catalog(file):
    data = json.loads(file.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    if not isinstance(data, dict) or set(data) != {
        "nativeName",
        "direction",
        "messages",
    }:
        raise ValueError("expected nativeName, direction and messages")
    if not isinstance(data["nativeName"], str) or not data["nativeName"].strip():
        raise ValueError("nativeName must name the language")
    if (
        data["direction"] not in ("ltr", "rtl")
        or not isinstance(data["messages"], dict)
        or not data["messages"]
    ):
        raise ValueError("invalid direction or messages")
    return data


def web_variants(value):
    if isinstance(value, str):
        return {"": value}
    if (
        isinstance(value, dict)
        and "other" in value
        and set(value) <= QUANTITIES
        and all(isinstance(text, str) for text in value.values())
    ):
        return value
    raise ValueError("expected text or plural branches including other")


def check_web(root):
    folder = root / "web/src/locales"
    if not folder.exists():
        return ["web/src/locales: missing English catalog"]
    try:
        base = browser_catalog(folder / "en.json")["messages"]
    except (OSError, ValueError) as error:
        return [f"web/src/locales/en.json: {error}"]
    issues = []
    for file in sorted(folder.glob("*.json")):
        prefix = str(file.relative_to(root))
        if not LOCALE_TAG.fullmatch(file.stem):
            issues.append(f"{prefix}: invalid language tag")
        try:
            target = browser_catalog(file)["messages"]
        except (OSError, ValueError) as error:
            issues.append(f"{prefix}: {error}")
            continue
        for key in sorted(base.keys() - target.keys()):
            issues.append(f"{prefix}: missing {key}")
        for key in sorted(target.keys() - base.keys()):
            issues.append(f"{prefix}: unexpected {key}")
        for key, value in target.items():
            try:
                variants = web_variants(value)
                if any(not text.strip() for text in variants.values()):
                    raise ValueError("blank text")
                if key not in base:
                    continue
                reference = web_variants(base[key])
                if ("" in variants) != ("" in reference):
                    raise ValueError("resource type differs")
                for variant, text in variants.items():
                    original = reference.get(variant, reference.get("other", ""))
                    if Counter(PLACEHOLDER.findall(original)) != Counter(
                        PLACEHOLDER.findall(text)
                    ):
                        raise ValueError(f"placeholder contract [{variant}]")
            except ValueError as error:
                issues.append(f"{prefix}:{key}: {error}")
    return issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    issues = check_android(args.root) + check_web(args.root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("Android and browser translation contracts passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
