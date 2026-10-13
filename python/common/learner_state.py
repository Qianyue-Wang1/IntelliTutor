import hashlib


def learner_state_key(user_id: str, scope: str | None) -> str:
    """Keep ordinary users on their existing key and isolate scoped experiment state."""
    if not scope:
        return user_id
    return hashlib.sha256(f"{user_id}\0{scope}".encode("utf-8")).hexdigest()
