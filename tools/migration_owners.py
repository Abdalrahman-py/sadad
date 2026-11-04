#!/usr/bin/env python3
"""Refuse function owner changes in migrations that would fail on the cloud.

The database test runs on PGlite as superuser, so it passes what `supabase db push` rejects with
"permission denied for schema": a new owner needs CREATE on the function's schema, and
schemas/99_revoke_create.sql takes that away from the internal roles. So a new owner sits between
`grant create` and `revoke create`, and no internal role owns anything in public (use a function
in sadid_private behind a security invoker wrapper). The same owner again is a no-op and passes.
"""
import pathlib
import re
import sys

MIGRATIONS = pathlib.Path(__file__).parent.parent / "Supabase-Setup/supabase/migrations"
STATEMENT = re.compile(
    r"alter\s+function\s+(?P<schema>\w+)\.(?P<name>\w+)\s*(?P<args>\([^)]*\))?\s+owner\s+to\s+(?P<owner>\w+)"
    r"|drop\s+function\s+(?:if\s+exists\s+)?(?P<dropped>\w+\.\w+)\s*(?P<dropped_args>\([^)]*\))?"
    r"|(?P<verb>grant|revoke)\s+create\s+on\s+schema\s+(?P<on>\w+)\s+(?:to|from)\s+(?P<roles>[\w\s,]+?)\s*;"
)


def signature(name, args):
    return name + re.sub(r"\s+", " ", args or "()")


def problems(migrations):
    """Check (file name, SQL) pairs, oldest first; return one line per refused statement."""
    owners, found = {}, []
    for file, sql in migrations:
        open_grants = set()
        for m in STATEMENT.finditer(re.sub(r"--[^\n]*", "", sql.lower())):
            if m["verb"]:
                grants = {(m["on"], role.strip()) for role in m["roles"].split(",")}
                open_grants = open_grants | grants if m["verb"] == "grant" else open_grants - grants
            elif m["dropped"]:
                owners.pop(signature(m["dropped"], m["dropped_args"]), None)
            else:
                schema, owner = m["schema"], m["owner"]
                function = signature(f"{schema}.{m['name']}", m["args"])
                if owner != "postgres" and owners.get(function) != owner:
                    if schema == "public":
                        found.append(f"{file}: {function}: {owner} may not own anything in public")
                    elif (schema, owner) not in open_grants:
                        found.append(f"{file}: {function}: {owner} needs `grant create on schema {schema}` first")
                owners[function] = owner
        found += [f"{file}: no `revoke create on schema {s} from {r}`" for s, r in sorted(open_grants)]
    return found


if __name__ == "__main__":
    refused = problems([(p.name, p.read_text()) for p in sorted(MIGRATIONS.glob("*.sql"))])
    for line in refused:
        print(line, file=sys.stderr)
    sys.exit(1 if refused else 0)
