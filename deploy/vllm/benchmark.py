"""Probe the real vLLM endpoint without participant data; run on the GPU server."""
import argparse
import asyncio
import json
import math
import os
import time
from pathlib import Path

from openai import AsyncOpenAI


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


async def benchmark(args):
    reports = []
    async with AsyncOpenAI(base_url=args.url, api_key=os.getenv("VLLM_API_KEY") or "EMPTY",
                           max_retries=0, timeout=args.timeout) as client:
        await client.models.list()
        # Warm the inference engine before recording results.
        await client.chat.completions.create(model=args.model, messages=[{"role": "user", "content": "你好"}],
            max_tokens=32, extra_body={"chat_template_kwargs": {"enable_thinking": False}})
        for concurrency in args.concurrency:
            gate = asyncio.Semaphore(concurrency)
            latencies, errors, token_counts = [], [], []

            async def request(index):
                async with gate:
                    started = time.monotonic()
                    try:
                        result = await client.chat.completions.create(
                            model=args.model, messages=[{"role": "user", "content":
                                f"第 {index} 个独立请求。" + args.prompt * args.repeat}],
                            max_tokens=args.max_tokens,
                            extra_body={"chat_template_kwargs": {"enable_thinking": False}})
                        if not result.choices[0].message.content:
                            raise ValueError("Empty response")
                        latencies.append(time.monotonic() - started)
                        token_counts.append(result.usage.completion_tokens if result.usage else 0)
                    except Exception as exc:
                        errors.append(type(exc).__name__)

            started = time.monotonic()
            await asyncio.gather(*(request(index) for index in range(args.requests)))
            elapsed = time.monotonic() - started
            report = dict(concurrency=concurrency, requests=args.requests, successes=len(latencies),
                          failures=len(errors), error_types=errors, elapsed_seconds=elapsed,
                          p50_seconds=percentile(latencies, .50), p95_seconds=percentile(latencies, .95),
                          output_tokens_per_second=sum(token_counts) / elapsed)
            reports.append(report)
            print(json.dumps(report, ensure_ascii=False), flush=True)
    Path(args.output).write_text(json.dumps({"model": args.model, "max_tokens": args.max_tokens,
        "prompt_repeat": args.repeat, "reports": reports}, indent=2), encoding="utf-8")
    return any(report["failures"] for report in reports)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.getenv("VLLM_API_URL", "http://127.0.0.1:8001/v1"))
    parser.add_argument("--model", default=os.getenv("VLLM_MODEL", "qwen3-32b"))
    parser.add_argument("--concurrency", nargs="+", type=int, default=[3, 6, 10, 15])
    parser.add_argument("--requests", type=int, default=30)
    parser.add_argument("--max-tokens", type=int, default=512)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--prompt", default="请解释 K-means 聚类的目标、步骤与局限，并给出一个例子。")
    parser.add_argument("--output", default="vllm-benchmark.json")
    args = parser.parse_args()
    if min(args.concurrency + [args.requests, args.max_tokens, args.repeat, args.timeout]) <= 0:
        parser.error("All load and timeout values must be positive")
    raise SystemExit(asyncio.run(benchmark(args)))
