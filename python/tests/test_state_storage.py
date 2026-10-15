import json
import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch

from common.learner_state import learner_state_key
from common.state_storage import atomic_write_json, learner_state_operation
from tools import record_interaction as records


class StateStorageTests(unittest.TestCase):
    def test_atomic_replacement_is_readable_during_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "profile.json")
            atomic_write_json(path, {"generation": 0})
            done = threading.Event()

            def writer():
                try:
                    for generation in range(1, 20):
                        atomic_write_json(path, {"generation": generation})
                finally:
                    done.set()

            with ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(writer)
                while not done.is_set():
                    with open(path) as stream:
                        self.assertIn(json.load(stream)["generation"], range(20))
                future.result()
            with open(path) as stream:
                self.assertEqual(json.load(stream)["generation"], 19)

    def test_same_scope_serializes_read_modify_write(self):
        counts = {"scope": 0}

        @learner_state_operation
        def update(user_id):
            previous = counts[user_id]
            threading.Event().wait(.001)
            counts[user_id] = previous + 1

        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(update, ["scope"] * 100))
        self.assertEqual(counts["scope"], 100)

    def test_interaction_reads_scoped_profile_and_background_uses_detached_snapshot(self):
        session = SimpleNamespace(user_id="student", session_id="session", learner_state_scope="experiment:a",
                                  history=[{"content": "original"}])
        with tempfile.TemporaryDirectory() as directory:
            state_key = learner_state_key(session.user_id, session.learner_state_scope)
            atomic_write_json(os.path.join(directory, state_key, "user_profile.json"), {"scope": "a"})
            with patch.object(records, "USER_DIR", directory), patch.object(records, "DATA_DIR", directory):
                path = records.record_interaction(session)
                with open(path) as stream:
                    self.assertEqual(json.load(stream)["user_profile_snapshot"], {"scope": "a"})
                with patch("threading.Thread") as thread:
                    records.record_interaction_async(session)
                    snapshot = thread.call_args.kwargs["args"][0]
                    session.history[0]["content"] = "changed"
                    self.assertEqual(snapshot.history[0]["content"], "original")
