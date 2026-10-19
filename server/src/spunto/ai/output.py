"""Closed, strictly typed model answers with explicit semantic field contracts."""

import json
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from spunto.core.text import label_key


class Output(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)

    @classmethod
    def model_json_schema(cls, *args, **kwargs):
        schema = super().model_json_schema(*args, **kwargs)

        def clean(value):
            if isinstance(value, dict):
                value.pop("x-spunto", None)
                for child in value.values():
                    clean(child)
            elif isinstance(value, list):
                for child in value:
                    clean(child)

        clean(schema)
        return schema

    def validate_meaning(self, allowed_note_ids: set[str]):
        for name, definition in type(self).model_fields.items():
            value = getattr(self, name)
            meaning = (definition.json_schema_extra or {}).get("x-spunto")
            if meaning == "note_ids" and not set(value).issubset(allowed_note_ids):
                raise ValueError("note_ids_outside_context")
            if meaning in {"label", "labels"}:
                for path in value if meaning == "labels" else [value]:
                    label_key(path)
            if isinstance(value, Output):
                value.validate_meaning(allowed_note_ids)
            if isinstance(value, list):
                for item in value:
                    if isinstance(item, Output):
                        item.validate_meaning(allowed_note_ids)


class field:
    @staticmethod
    def note_ids(*, max_count=32):
        return Field(max_length=max_count, json_schema_extra={"x-spunto": "note_ids"})

    @staticmethod
    def label():
        return Field(min_length=1, max_length=128, json_schema_extra={"x-spunto": "label"})

    @staticmethod
    def labels(*, max_count=10):
        return Field(max_length=max_count, json_schema_extra={"x-spunto": "labels"})

    @staticmethod
    def text(*, max_length=4000):
        return Field(max_length=max_length)


def parse_output(model, raw, note_ids):
    if not isinstance(model, type) or not issubclass(model, Output):
        raise TypeError("AI output models must inherit Output")
    if isinstance(raw, model):
        raw = raw.model_dump_json()
    if isinstance(raw, str):
        text = raw.strip()
        if text.startswith("```") and text.endswith("```"):
            if "\n" not in text:
                raise ValueError("invalid_json_fence")
            text = text.split("\n", 1)[1].rsplit("```", 1)[0].strip()
        value = model.model_validate_json(text, strict=True)
    else:
        value = model.model_validate_json(json.dumps(raw), strict=True)
    value.validate_meaning(note_ids)
    return value


class Transcript(Output):
    text: Annotated[str, Field(max_length=100000)]


def closed_schema(model):
    schema = model.model_json_schema()

    def check(value):
        if isinstance(value, dict):
            if value.get("type") == "object" and value.get("additionalProperties") is not False:
                raise TypeError("Every AI output object must use a closed Output model")
            for item in value.values():
                check(item)
        elif isinstance(value, list):
            for item in value:
                check(item)

    check(schema)
    return schema
