import asyncio
import os
import threading
import unittest
from unittest.mock import patch
from fastapi import HTTPException
from common.concurrency import register_new_session, run_blocking, session_operation, shutdown


class ConcurrencyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncTearDown(self):
        await shutdown()

    async def test_different_sessions_overlap_same_session_serializes_and_snapshot_detaches(self):
        entered = asyncio.Event()
        release = asyncio.Event()
        history = []

        @session_operation
        async def operation(session_id, wait=False):
            history.append(session_id)
            entered.set()
            if wait:
                await release.wait()
            return {"history": history}

        first = asyncio.create_task(operation("a", True))
        await entered.wait()
        second = asyncio.create_task(operation("a"))
        result = await operation("b")
        self.assertEqual(history, ["a", "b"])
        self.assertFalse(second.done())
        release.set()
        await asyncio.gather(first, second)
        self.assertEqual(result["history"], ["a", "b"])
        self.assertEqual(history, ["a", "b", "a"])

    async def test_nested_endpoint_does_not_deadlock_with_single_admission_slot(self):
        @session_operation
        async def snapshot(session_id):
            return session_id

        @session_operation
        async def update(session_id):
            return await snapshot(session_id)

        with patch.dict(os.environ, {"PYTHON_MAX_OPERATIONS": "1"}):
            self.assertEqual(await asyncio.wait_for(update("a"), 1), "a")

    async def test_cancellation_keeps_session_lock_until_worker_exits(self):
        started = threading.Event()
        release = threading.Event()
        history = []

        def blocking():
            started.set()
            release.wait(3)
            history.append("worker-finished")

        @session_operation
        async def operation(session_id, wait=False):
            if wait:
                await run_blocking(blocking)
            else:
                history.append("successor")

        first = asyncio.create_task(operation("a", True))
        while not started.is_set():
            await asyncio.sleep(0.001)
        first.cancel()
        second = asyncio.create_task(operation("a"))
        try:
            await asyncio.sleep(0.02)
            self.assertFalse(first.done())
            self.assertFalse(second.done())
        finally:
            release.set()
        with self.assertRaises(asyncio.CancelledError):
            await first
        await second
        self.assertEqual(history, ["worker-finished", "successor"])

    async def test_cancelled_lock_waiter_does_not_release_owner(self):
        entered = asyncio.Event()
        release = asyncio.Event()

        @session_operation
        async def operation(session_id, wait=False):
            if wait:
                entered.set()
                await release.wait()

        first = asyncio.create_task(operation("a", True))
        await entered.wait()
        waiter = asyncio.create_task(operation("a"))
        await asyncio.sleep(0)
        waiter.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await waiter
        third = asyncio.create_task(operation("a"))
        await asyncio.sleep(0)
        self.assertFalse(third.done())
        release.set()
        await asyncio.gather(first, third)

    async def test_timeout_returns_504_and_next_request_can_run(self):
        @session_operation
        async def operation(session_id):
            await asyncio.sleep(5)

        with patch.dict(os.environ, {"PYTHON_OPERATION_TIMEOUT_SECONDS": "1"}):
            with self.assertRaises(HTTPException) as caught:
                await operation("a")
        self.assertEqual(caught.exception.status_code, 504)

    async def test_snapshot_waits_for_initialization(self):
        created = asyncio.Event()
        release = asyncio.Event()
        sessions = {}

        @session_operation
        async def initialize():
            sessions["new"] = {"ready": False}
            register_new_session("new", lambda: sessions.pop("new", None))
            created.set()
            await release.wait()
            sessions["new"]["ready"] = True

        @session_operation
        async def snapshot(session_id):
            return sessions[session_id]

        initialization = asyncio.create_task(initialize())
        await created.wait()
        reading = asyncio.create_task(snapshot("new"))
        await asyncio.sleep(0)
        self.assertFalse(reading.done())
        release.set()
        await initialization
        self.assertEqual(await reading, {"ready": True})

    async def test_failed_initialization_removes_partial_session(self):
        sessions = {}

        @session_operation
        async def initialize():
            sessions["new"] = {}
            register_new_session("new", lambda: sessions.pop("new", None))
            raise ValueError("invalid generated content")

        with self.assertRaises(ValueError):
            await initialize()
        self.assertEqual(sessions, {})
