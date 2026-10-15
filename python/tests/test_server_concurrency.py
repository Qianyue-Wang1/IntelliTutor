import asyncio
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import server
from common.concurrency import shutdown
from tools.llm_api_service import close_clients


class ServerConcurrencyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncTearDown(self):
        server.session_manager.sessions.clear()
        server.session_manager.step_generation_locks.clear()
        await shutdown()
        await close_clients()

    async def test_blocking_learning_state_jobs_overlap_without_blocking_health(self):
        both = threading.Barrier(2)
        started = threading.Event()
        release = threading.Event()

        def make_session(session_id):
            session = SimpleNamespace(session_id=session_id, session_type="discussion",
                persist_learner_state=True, learner_state_status="PENDING", learner_state_error="",
                knowledge_tracing_history=[])

            def trace():
                both.wait(timeout=3)
                started.set()
                release.wait(timeout=3)
                session.learner_state_status = "READY"
            session.knowledge_tracing = trace
            return session

        server.session_manager.sessions.update({key: make_session(key) for key in ("a", "b")})
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app), base_url="http://test") as client:
            jobs = [asyncio.create_task(client.post("/api/v1/learnerstate/retry", json={"session_id": key}))
                    for key in ("a", "b")]
            try:
                async with asyncio.timeout(2):
                    while not started.is_set():
                        await asyncio.sleep(.001)
                health = await asyncio.wait_for(client.get("/healthz"), .5)
                self.assertEqual(health.json(), {"status": "ok"})
            finally:
                release.set()
                results = await asyncio.gather(*jobs)
            for result in results:
                self.assertEqual(result.status_code, 200)
                self.assertEqual(result.json()["data"]["learner_state_status"], "READY")

    async def test_failed_direct_followup_keeps_existing_messages(self):
        session = server.session_manager.create_session("direct", "question", "student")
        session.messages = [{"role": "assistant", "content": "original"}]
        with patch.object(server, "call_llm_async", new=AsyncMock(return_value=None)):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app), base_url="http://test") as client:
                result = await client.post("/api/v1/direct/followup",
                    json={"session_id": session.session_id, "message": "question"})
        self.assertEqual(result.status_code, 503)
        self.assertEqual(session.messages, [{"role": "assistant", "content": "original"}])

    async def test_unconfigured_vision_returns_503(self):
        with patch.dict("os.environ", {"VLLM_MODEL_VL": ""}):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app), base_url="http://test") as client:
                result = await client.post("/api/v1/imginput", json={"imgStr": "image", "userText": ""})
        self.assertEqual(result.status_code, 503)
