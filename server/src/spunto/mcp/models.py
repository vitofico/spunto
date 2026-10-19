"""The public schemas of the seven core MCP tools (02 §8.3)."""

from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, model_validator


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


LabelPaths = Annotated[
    list[Annotated[str, Field(min_length=1, max_length=128)]], Field(max_length=500)
]
NoteId = Annotated[str, Field(min_length=1, max_length=64)]


class Search(Input):
    query: Annotated[str, Field(max_length=500)] = ""
    sort: Literal["relevance", "recent"] = "relevance"
    labels: LabelPaths = []
    from_date: date | None = Field(None, alias="from")
    to_date: date | None = Field(None, alias="to")
    include_archived: bool = False
    mode: Literal["hybrid", "keyword"] = "hybrid"
    limit: Annotated[int, Field(ge=1, le=25, strict=True)] = 10
    cursor: Annotated[str, Field(max_length=8192)] | None = None


class Get(Input):
    ids: Annotated[list[NoteId], Field(min_length=1, max_length=20)]
    max_chars: Annotated[int, Field(ge=1000, le=50000, strict=True)] = 20000


class Labels(Input):
    prefix: Annotated[str, Field(max_length=128)] | None = None
    limit: Annotated[int, Field(ge=1, le=500, strict=True)] = 200


class Create(Input):
    body: Annotated[str, Field(min_length=1, max_length=20000)]
    labels: LabelPaths = []
    source_url: HttpUrl | None = None
    request_id: Annotated[str, Field(min_length=8, max_length=64)] | None = None


class Append(Input):
    id: NoteId
    text: Annotated[str, Field(min_length=1, max_length=10000)]
    expected_version: Annotated[int, Field(ge=1, strict=True)] | None = None
    separator: Literal["paragraph", "line"] = "paragraph"


class SetLabels(Input):
    id: NoteId
    add: LabelPaths = []
    remove: LabelPaths = []

    @model_validator(mode="after")
    def nonempty(self):
        if not self.add and not self.remove:
            raise ValueError("Give at least one label to add or remove")
        return self


class Archive(Input):
    id: NoteId
    archived: bool = True
