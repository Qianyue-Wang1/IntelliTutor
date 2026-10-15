"""Local-only OpenAI-compatible inference; replicas are scheduled by vLLM."""
import asyncio
import logging
import os
import sys
import threading
import time
from weakref import WeakKeyDictionary

import httpx
from dotenv import load_dotenv
from openai import AsyncOpenAI, OpenAI
from common.concurrency import operation_time_remaining

_env_path = (os.path.join(sys._MEIPASS, '.env') if getattr(sys, 'frozen', False)
             else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '.env'))
load_dotenv(_env_path)
logger = logging.getLogger("intellitutor.llm")
_client_lock = threading.Lock()
_sync_clients = {}
_async_clients = WeakKeyDictionary()
TEXT_MODEL_TYPES = {"instruct", "large", "small", "fast", "reasoning"}


def positive_int(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def get_api_configs(model_type: str = "instruct") -> list[dict]:
    """Never fall back to cloud providers or send images to a text model."""
    if model_type not in TEXT_MODEL_TYPES | {"vl", "vlfast"}:
        raise ValueError(f"Unsupported model type: {model_type}")
    url = os.getenv("VLLM_API_URL", "").strip().rstrip("/")
    model = os.getenv(f"VLLM_MODEL_{model_type.upper()}", "").strip()
    if not model and model_type in TEXT_MODEL_TYPES:
        model = os.getenv("VLLM_MODEL", "").strip()
    if not url or not model:
        raise ValueError(f"Missing local vLLM URL or model configuration for {model_type}")
    if not url.startswith(("http://", "https://")) or not url.endswith("/v1"):
        raise ValueError("VLLM_API_URL must be an HTTP(S) URL ending in /v1")
    return [{"base_url": url, "key": os.getenv("VLLM_API_KEY") or "EMPTY", "model": model}]


def validate_config():
    get_api_configs()
    for name, default in (("VLLM_REQUEST_TIMEOUT_SECONDS", 180),
                          ("VLLM_MAX_OUTPUT_TOKENS", 4096), ("VLLM_MAX_CONNECTIONS", 32)):
        positive_int(name, default)
    for role in TEXT_MODEL_TYPES:
        positive_int(f"VLLM_MAX_OUTPUT_TOKENS_{role.upper()}", 4096)


def build_messages(prompt: str, system_prompt: str | None = None) -> list[dict]:
    messages = [{"role": "system", "content": system_prompt}] if system_prompt else []
    return messages + [{"role": "user", "content": prompt}]


def _client(config, asynchronous=False):
    key = (config["base_url"], config["key"], positive_int("VLLM_MAX_CONNECTIONS", 32))
    limits = httpx.Limits(max_connections=key[2], max_keepalive_connections=key[2])
    if asynchronous:
        clients = _async_clients.setdefault(asyncio.get_running_loop(), {})
        if key not in clients:
            clients[key] = AsyncOpenAI(base_url=key[0], api_key=key[1], max_retries=0,
                                      http_client=httpx.AsyncClient(limits=limits))
        return clients[key]
    with _client_lock:
        if key not in _sync_clients:
            _sync_clients[key] = OpenAI(base_url=key[0], api_key=key[1], max_retries=0,
                                       http_client=httpx.Client(limits=limits))
        return _sync_clients[key]


async def close_clients():
    for client in _async_clients.pop(asyncio.get_running_loop(), {}).values():
        await client.close()
    with _client_lock:
        clients = list(_sync_clients.values())
        _sync_clients.clear()
    for client in clients:
        client.close()


def _parameters(config, messages, model_type):
    remaining = operation_time_remaining()
    if remaining is not None and remaining <= 0:
        raise TimeoutError("Inference operation deadline exceeded")
    timeout = positive_int("VLLM_REQUEST_TIMEOUT_SECONDS", 180)
    if remaining is not None:
        timeout = min(timeout, remaining)
    return dict(
        model=config["model"], messages=messages, stream=False,
        max_tokens=positive_int(f"VLLM_MAX_OUTPUT_TOKENS_{model_type.upper()}",
                                positive_int("VLLM_MAX_OUTPUT_TOKENS", 4096)),
        extra_body={"chat_template_kwargs": {"enable_thinking": False}},
        timeout=httpx.Timeout(timeout, connect=min(5.0, timeout)),
    )


def _content(response, model_type, started):
    choice = response.choices[0]
    usage = getattr(response, "usage", None)
    logger.info("vLLM task=%s model=%s elapsed=%.3f finish=%s prompt_tokens=%s completion_tokens=%s",
                model_type, getattr(response, "model", "unknown"), time.monotonic() - started,
                choice.finish_reason, getattr(usage, "prompt_tokens", None),
                getattr(usage, "completion_tokens", None))
    if choice.finish_reason == "length":
        logger.warning("vLLM truncated response task=%s; rejecting incomplete content", model_type)
        return None
    content = choice.message.content
    if not content or not content.strip():
        logger.warning("vLLM empty response task=%s", model_type)
        return None
    return content


def call_llm(prompt: str, system_prompt: str | None = None,
             model_type: str = "instruct") -> str | None:
    config = get_api_configs(model_type)[0]
    started = time.monotonic()
    try:
        response = _client(config).chat.completions.create(
            **_parameters(config, build_messages(prompt, system_prompt), model_type))
        return _content(response, model_type, started)
    except Exception as exc:
        # Preserve business fallback contracts; never log credentials or full prompts.
        logger.warning("vLLM request failed task=%s error_type=%s elapsed=%.3f",
                       model_type, type(exc).__name__, time.monotonic() - started)
        return None


async def call_llm_async(prompt: str, system_prompt: str | None = None,
                         model_type: str = "instruct") -> str | None:
    config = get_api_configs(model_type)[0]
    started = time.monotonic()
    try:
        response = await _client(config, asynchronous=True).chat.completions.create(
            **_parameters(config, build_messages(prompt, system_prompt), model_type))
        return _content(response, model_type, started)
    except Exception as exc:
        logger.warning("vLLM async request failed task=%s error_type=%s elapsed=%.3f",
                       model_type, type(exc).__name__, time.monotonic() - started)
        return None


def call_llm_fast(prompt: str, system_prompt: str | None = None) -> str | None:
    return call_llm(prompt, system_prompt, model_type="fast")


def call_llm_vl(base64_image: str, type: str = "vl", system_prompt: str | None = None,
                prompt: str = "") -> str | None:
    model_type = "vlfast" if type == "vlfast" else "vl"
    config = get_api_configs(model_type)[0]
    if "base64," in base64_image:
        base64_image = base64_image.split("base64,", 1)[1]
    messages = build_messages(prompt, system_prompt)
    messages[-1]["content"] = [
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"}},
    ]
    started = time.monotonic()
    try:
        response = _client(config).chat.completions.create(**_parameters(config, messages, model_type))
        return _content(response, model_type, started)
    except Exception as exc:
        logger.warning("vLLM vision request failed error_type=%s", type(exc).__name__)
        return None
