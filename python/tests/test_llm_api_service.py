import asyncio
import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from tools import llm_api_service as api

CONFIG = {"VLLM_API_URL": "http://gpu-server:8001/v1", "VLLM_MODEL": "qwen3-32b",
          "VLLM_API_KEY": "test-key"}


def response(content="answer", finish="stop"):
    return SimpleNamespace(model="qwen3-32b", usage=None, choices=[
        SimpleNamespace(finish_reason=finish, message=SimpleNamespace(content=content))])


class LlmApiServiceTests(unittest.TestCase):
    def test_all_text_roles_use_local_model_even_with_cloud_credentials(self):
        with patch.dict(os.environ, {**CONFIG, "QWEN_API_KEY": "cloud",
                        "QWEN_API_URL": "https://cloud/v1", "QWEN_MODEL_LARGE": "cloud"}, clear=True):
            for role in api.TEXT_MODEL_TYPES:
                self.assertEqual(api.get_api_configs(role), [{"base_url": CONFIG["VLLM_API_URL"],
                                 "key": "test-key", "model": "qwen3-32b"}])

    def test_missing_config_fails_without_cloud_fallback(self):
        with patch.dict(os.environ, {"QWEN_API_KEY": "cloud"}, clear=True):
            with self.assertRaises(ValueError):
                api.get_api_configs()

    def test_vision_requires_explicit_model(self):
        with patch.dict(os.environ, CONFIG, clear=True):
            with self.assertRaises(ValueError):
                api.get_api_configs("vl")

    def test_role_override_and_url_validation(self):
        with patch.dict(os.environ, {**CONFIG, "VLLM_MODEL_LARGE": "other-local-model"}, clear=True):
            self.assertEqual(api.get_api_configs("large")[0]["model"], "other-local-model")
        with patch.dict(os.environ, {**CONFIG, "VLLM_API_URL": "http://gpu:8001"}, clear=True):
            with self.assertRaises(ValueError):
                api.validate_config()

    def test_sync_parameters_and_truncated_output(self):
        client = Mock()
        client.chat.completions.create.return_value = response(finish="length")
        with patch.dict(os.environ, CONFIG, clear=True), patch.object(api, "_client", return_value=client):
            self.assertIsNone(api.call_llm("question", "system"))
        kwargs = client.chat.completions.create.call_args.kwargs
        self.assertEqual(kwargs["extra_body"], {"chat_template_kwargs": {"enable_thinking": False}})
        self.assertEqual(kwargs["messages"][0], {"role": "system", "content": "system"})
        self.assertEqual(kwargs["model"], "qwen3-32b")

    def test_empty_and_transport_failure_return_none(self):
        client = Mock()
        with patch.dict(os.environ, CONFIG, clear=True), patch.object(api, "_client", return_value=client):
            client.chat.completions.create.return_value = response(" ")
            self.assertIsNone(api.call_llm("question"))
            client.chat.completions.create.side_effect = TimeoutError()
            self.assertIsNone(api.call_llm("question"))
            self.assertEqual(client.chat.completions.create.call_count, 2)

    def test_fast_uses_local_entrypoint(self):
        with patch.object(api, "call_llm", return_value="ok") as call:
            self.assertEqual(api.call_llm_fast("q", "s"), "ok")
        call.assert_called_once_with("q", "s", model_type="fast")


class AsyncLlmTests(unittest.IsolatedAsyncioTestCase):
    async def asyncTearDown(self):
        await api.close_clients()

    async def test_async_calls_overlap_and_share_parameters(self):
        entered = 0
        both = asyncio.Event()

        async def generate(**kwargs):
            nonlocal entered
            entered += 1
            if entered == 2:
                both.set()
            await asyncio.wait_for(both.wait(), 1)
            return response()

        client = Mock()
        client.chat.completions.create = AsyncMock(side_effect=generate)
        with patch.dict(os.environ, CONFIG, clear=True), patch.object(api, "_client", return_value=client):
            self.assertEqual(await asyncio.gather(api.call_llm_async("a"), api.call_llm_async("b")),
                             ["answer", "answer"])
        for call in client.chat.completions.create.call_args_list:
            self.assertFalse(call.kwargs["extra_body"]["chat_template_kwargs"]["enable_thinking"])

    async def test_clients_are_reused_and_closed_without_network(self):
        with patch.dict(os.environ, CONFIG, clear=True):
            config = api.get_api_configs()[0]
            sync = api._client(config)
            asynchronous = api._client(config, asynchronous=True)
            self.assertIs(api._client(config), sync)
            self.assertIs(api._client(config, asynchronous=True), asynchronous)
            self.assertEqual(sync.max_retries, 0)
            self.assertEqual(asynchronous.max_retries, 0)
            await api.close_clients()
            self.assertTrue(sync.is_closed())
            self.assertTrue(asynchronous.is_closed())


class SdkTransportTests(unittest.IsolatedAsyncioTestCase):
    async def asyncTearDown(self):
        await api.close_clients()

    async def test_real_sdk_serializes_local_requests_for_both_clients(self):
        import json
        import httpx
        from openai import AsyncOpenAI, OpenAI
        received = []

        def serve(request):
            self.assertEqual(str(request.url), "http://gpu-server:8001/v1/chat/completions")
            self.assertEqual(request.headers["Authorization"], "Bearer test-key")
            received.append(json.loads(request.content))
            return httpx.Response(200, json={"id": "test", "object": "chat.completion", "created": 0,
                "model": "qwen3-32b", "choices": [{"index": 0, "finish_reason": "stop",
                "message": {"role": "assistant", "content": "ok"}}]})

        transport = httpx.MockTransport(serve)
        with patch.dict(os.environ, CONFIG, clear=True):
            key = (CONFIG["VLLM_API_URL"], "test-key", 32)
            api._sync_clients[key] = OpenAI(base_url=CONFIG["VLLM_API_URL"], api_key="test-key",
                max_retries=0, http_client=httpx.Client(transport=transport))
            api._async_clients[asyncio.get_running_loop()] = {key: AsyncOpenAI(
                base_url=CONFIG["VLLM_API_URL"], api_key="test-key", max_retries=0,
                http_client=httpx.AsyncClient(transport=transport))}
            self.assertEqual(api.call_llm("q"), "ok")
            self.assertEqual(await api.call_llm_async("q"), "ok")
        self.assertEqual(len(received), 2)
        for body in received:
            self.assertEqual(body["model"], "qwen3-32b")
            self.assertEqual(body["chat_template_kwargs"], {"enable_thinking": False})
            self.assertNotIn("reasoning", body)
