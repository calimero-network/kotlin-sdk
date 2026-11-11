#!/usr/bin/env python3
"""Write the copy of openapi/openapi.json that OpenAPI Generator reads.

    python3 codegen/prepare-spec.py <openapi.json> <out.json>

Two changes. An inline request body that is a `oneOf` moves to `components/schemas` as
`<OperationId>Request`, so `schemaMappings` can name the hand-written type that writes it (the
generator's kotlinx output renders such a `oneOf` as a sealed class no branch extends). And an
object schema with `properties` drops `additionalProperties`. The node's answers
keep fields beyond the listed ones open (`additionalProperties: {}`), which OpenAPI Generator
7.26's Kotlin client renders as a data class that also extends HashMap and does not compile
(`) : HashMap<...>()() {`). The generated client ignores unknown keys anyway, so nothing a caller
reads is lost. A schema with `additionalProperties` and no `properties` (a free-form map) is kept.
"""

import json
import sys


def walk(node):
    if isinstance(node, dict):
        if node.get("type") == "object" and "properties" in node and "additionalProperties" in node:
            del node["additionalProperties"]
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)


def hoist_one_of_bodies(spec):
    schemas = spec.setdefault("components", {}).setdefault("schemas", {})
    for item in spec["paths"].values():
        for op in item.values():
            if not isinstance(op, dict):
                continue
            for media in op.get("requestBody", {}).get("content", {}).values():
                schema = media.get("schema", {})
                if "oneOf" in schema:
                    name = "".join(p[:1].upper() + p[1:] for p in op["operationId"].split("_")) + "Request"
                    schemas[name] = schema
                    media["schema"] = {"$ref": f"#/components/schemas/{name}"}


def main():
    spec = json.load(open(sys.argv[1]))
    hoist_one_of_bodies(spec)
    walk(spec)
    with open(sys.argv[2], "w") as out:
        json.dump(spec, out, indent=1, sort_keys=False)


if __name__ == "__main__":
    main()
