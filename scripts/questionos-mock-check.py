#!/usr/bin/env python3
"""Verify the legacy mock response contract without network or database access."""

import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "v0.2" / "mock_response.py"

spec = importlib.util.spec_from_file_location("questionos_mock_response", MODULE_PATH)
if spec is None or spec.loader is None:
    raise RuntimeError(f"cannot load mock module: {MODULE_PATH}")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

cases = [
    {"id": "empty", "messages": []},
    {"id": "short", "messages": [{"role": "user", "content": "去留"}]},
    {
        "id": "last-user-wins",
        "messages": [
            {"role": "user", "content": "旧问题"},
            {"role": "assistant", "content": "旧回答"},
            {"role": "user", "content": "新问题更长"},
        ],
    },
]

results = []
for case in cases:
    first = module.mock_chat_response(case["messages"])
    second = module.mock_chat_response(case["messages"])
    if first != second:
        raise AssertionError(f'{case["id"]}: repeated calls returned different results')
    content = first.get("choices", [{}])[0].get("message", {}).get("content")
    if not isinstance(content, str) or not content:
        raise AssertionError(f'{case["id"]}: invalid chat-completions response shape')
    results.append({"id": case["id"], "ok": True, "content": content})

print(json.dumps({"schemaVersion": 1, "ok": True, "results": results}, ensure_ascii=False, indent=2))
