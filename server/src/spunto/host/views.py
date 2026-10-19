"""Closed, parameterized view filters; mirrored by shared offline fixtures."""

from datetime import UTC, datetime

from spunto.host.manifest import ManifestError, closed


def compile_filter(value: dict, *, plugin_id: str, now: int, alias: str = "n") -> tuple[str, list]:
    closed(
        value,
        {
            "labels",
            "no_labels",
            "source",
            "open_tasks",
            "pinned",
            "archived",
            "days",
            "created_within_days",
            "member",
            "not_member",
        },
        "view filter",
    )
    if alias not in {"n", "note"}:
        raise ManifestError("invalid SQL alias")
    clauses, args = [], []
    for name, arg in value.items():
        if name == "labels":
            if not isinstance(arg, list) or not all(isinstance(x, str) and x for x in arg):
                raise ManifestError("labels must be a list of paths")
            for path in arg:
                from spunto.core.text import label_key

                key = label_key(path)
                clauses.append(
                    "EXISTS (SELECT 1 FROM note_label nl JOIN label l ON l.id=nl.label_id "  # noqa: S608
                    f"WHERE nl.note_id={alias}.id "
                    "AND (l.key=? OR substr(l.key,1,length(?)+1)=?||'/'))"  # noqa: S608
                )
                args.extend([key, key, key])
        elif name == "no_labels":
            if type(arg) is not bool:
                raise ManifestError("no_labels must be boolean")
            clauses.append(
                f"{'NOT ' if arg else ''}EXISTS (SELECT 1 FROM note_label nl "  # noqa: S608
                f"WHERE nl.note_id={alias}.id)"  # noqa: S608
            )
        elif name == "source":
            if not isinstance(arg, str) or (
                arg
                not in {
                    "owner",
                    "web",
                    "android",
                    "share",
                    "selection",
                    "api",
                    "plugin",
                    "import",
                    "agent",
                }
                and not arg.startswith("plugin:")
            ):
                raise ManifestError("unknown source")
            if arg.startswith("plugin:"):
                clauses.append(f"{alias}.source_ref=?")
                args.append(arg[7:])
            elif arg == "owner":
                clauses.append(f"{alias}.origin='owner'")
            elif arg == "agent":
                clauses.append(
                    f"({alias}.source_kind='agent' OR EXISTS (SELECT 1 FROM credential c "  # noqa: S608
                    f"WHERE c.id={alias}.updated_by AND c.kind='pat'))"  # noqa: S608
                )
            else:
                clauses.append(f"{alias}.source_kind=?")
                args.append(arg)
        elif name in {"open_tasks", "pinned", "archived"}:
            if type(arg) is not bool:
                raise ManifestError(f"{name} must be boolean")
            if name == "archived":
                clauses.append(f"{alias}.archived_at IS {'NOT ' if arg else ''}NULL")
                continue
            column = "open_tasks" if name == "open_tasks" else name
            clauses.append(f"{alias}.{column}{'>' if arg and name == 'open_tasks' else '='}?")
            args.append(0 if name == "open_tasks" else int(arg))
        elif name in {"days", "created_within_days"}:
            if type(arg) is not int or not 1 <= arg <= 36500:
                raise ManifestError("date window must be 1 to 36500 days")
            midnight = datetime.fromtimestamp(now / 1000, UTC).replace(
                hour=0, minute=0, second=0, microsecond=0
            )
            clauses.append(f"{alias}.created_at>=?")
            args.append(int(midnight.timestamp() * 1000) - (arg - 1) * 86400000)
        else:
            if not isinstance(arg, str) or not arg:
                raise ManifestError("member must name a view")
            clauses.append(
                f"{'NOT ' if name == 'not_member' else ''}EXISTS (SELECT 1 FROM annotation a "  # noqa: S608
                f"WHERE a.note_id={alias}.id AND a.plugin_id=? AND a.kind=?)"  # noqa: S608
            )
            args.extend([plugin_id, "member:" + arg])
    return " AND ".join(clauses) or "1", args
