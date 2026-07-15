"""Deterministic OpenAI-compatible responses for the legacy local mock server."""

RESPONSES = (
    "这个问题涉及哪些关键利益相关者？他们的诉求分别是什么？",
    "如果能完美解决这个问题，6个月后的情况会是什么样？",
    "目前阻碍你做决定的最大顾虑是什么？",
)


def mock_chat_response(messages):
    """Return a deterministic chat-completions response for the last user message."""
    last_user_message = ""
    for message in reversed(messages):
        if message.get("role") == "user":
            last_user_message = message.get("content", "")
            break

    response = RESPONSES[len(last_user_message) % len(RESPONSES)]
    return {"choices": [{"message": {"content": response}}]}
