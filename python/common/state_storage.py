"""Scoped, thread-safe learner-state updates and atomic JSON persistence."""
import functools
import inspect
import json
import os
import tempfile
import threading
from contextlib import contextmanager

from common.learner_state import learner_state_key

_registry_lock = threading.Lock()
_locks = {}


@contextmanager
def learner_state_lock(key):
    with _registry_lock:
        entry = _locks.setdefault(key, [threading.RLock(), 0])
        entry[1] += 1
    try:
        with entry[0]:
            yield
    finally:
        with _registry_lock:
            entry[1] -= 1
            if entry[1] == 0:
                _locks.pop(key, None)


def learner_state_operation(function):
    signature = inspect.signature(function)

    @functools.wraps(function)
    def wrapped(*args, **kwargs):
        arguments = signature.bind(*args, **kwargs)
        arguments.apply_defaults()
        session = arguments.arguments.get("self")
        key = (learner_state_key(session.user_id, session.learner_state_scope) if session
               else arguments.arguments.get("user_id", "default"))
        with learner_state_lock(key):
            return function(*args, **kwargs)

    return wrapped


def atomic_write_json(path, data):
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".json-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2, default=str)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
