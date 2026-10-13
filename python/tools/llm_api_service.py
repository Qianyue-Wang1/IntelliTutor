import os
import sys
from openai import OpenAI, AsyncOpenAI
from dotenv import load_dotenv

# 兼容 PyInstaller 打包环境：优先从打包资源目录加载 .env
if getattr(sys, 'frozen', False):
    _env_path = os.path.join(sys._MEIPASS, '.env')
else:
    _env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '.env')
load_dotenv(_env_path)

# 供应商名称列表（按优先级排序）
PROVIDERS = ["QWEN", "OPENROUTER", "MODELSCOPE", "SILICON"]


def get_api_configs(model_type: str = "instruct") -> list[dict]:
    """根据 model_type 从 .env 中读取所有可用供应商的配置。"""
    suffix = f"MODEL_{model_type.upper()}"
    configs = []
    for provider in PROVIDERS:
        url = os.getenv(f"{provider}_API_URL")
        key = os.getenv(f"{provider}_API_KEY")
        model = os.getenv(f"{provider}_{suffix}")
        if url and key and model:
            configs.append({
                "base_url": url,
                "key": key,
                "model": model,
            })
    return configs


def build_messages(prompt: str, system_prompt: str | None = None) -> list[dict]:
    """构建 messages 列表。"""
    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({"role": "user", "content": prompt})
    return messages


def call_llm(prompt: str, system_prompt: str | None = None,
             model_type: str = "instruct") -> str | None:
    """同步 LLM 调用，自动在多个供应商之间 fallback。"""
    api_configs = get_api_configs(model_type)
    messages = build_messages(prompt, system_prompt)

    for i, config in enumerate(api_configs):
        try:
            client = OpenAI(base_url=config["base_url"], api_key=config["key"])
            if "aliyun" in config["base_url"]:
                extra_body={"enable_thinking": True} if "reasoning" in model_type else {"enable_thinking": False}
            elif "openrouter" in config["base_url"]:
                extra_body={"reasoning": {"enabled": True}} if "reasoning" in model_type else {}
            else:
                extra_body={"reasoning": {"enabled": True}} if "reasoning" in model_type else {}
            response = client.chat.completions.create(
                model=config["model"],
                messages=messages,
                stream=False,
                extra_body=extra_body,
                timeout=60.0,
            )
            content = response.choices[0].message.content
            if i > 0:
                print(f"成功切换到备用API{i+1}")
            return content
        except Exception as e:
            print(f"API{i+1}请求异常: {str(e)}")
            continue

    print("所有API都无法使用")
    return None

def call_llm_fast(prompt: str, system_prompt: str | None = None) -> str | None:
    """同步 LLM 调用，使用 Cerebras 多 key 之间 fallback。"""
    url = os.getenv("CEREBRAS_API_URL")
    model = os.getenv("CEREBRAS_MODEL_INSTRUCT")
    messages = build_messages(prompt, system_prompt)

    keys = [v for k, v in sorted(os.environ.items())
            if k.startswith("CEREBRAS_API_KEY") and v]

    if not keys:
        print("未配置任何 CEREBRAS_API_KEY")
        return None

    for i, key in enumerate(keys):
        try:
            client = OpenAI(base_url=url, api_key=key)
            response = client.chat.completions.create(
                model=model,
                messages=messages,
                stream=False,
                timeout=60.0,
            )
            content = response.choices[0].message.content
            if i > 0:
                print(f"成功切换到备用KEY{i+1}")
            return content
        except Exception as e:
            print(f"CEREBRAS KEY{i+1} 请求异常: {str(e)}")
            continue

    print("所有 CEREBRAS KEY 都无法使用")
    return None


def call_llm_vl(base64_image: str, type: str = "vl", system_prompt: str | None = None, prompt: str = "") -> str | None:
    """调用支持视觉输入的大模型。"""
    model_type = "vlfast" if type == "vlfast" else "vl"
    api_configs = get_api_configs(model_type=model_type)

    # 剥离 Data URL 前缀（如 "data:image/jpeg;base64,"），只保留纯 base64 数据
    if base64_image and "base64," in base64_image:
        base64_image = base64_image.split("base64,", 1)[1]

    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({
        "role": "user",
        "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"}},
        ],
    })

    for i, config in enumerate(api_configs):
        try:
            client = OpenAI(base_url=config["base_url"], api_key=config["key"])
            response = client.chat.completions.create(
                model=config["model"],
                messages=messages,
                response_format={"type": "json_object"},
                timeout=60.0,
            )
            content = response.choices[0].message.content
            if i > 0:
                print(f"成功切换到备用API{i+1}")
            return content
        except Exception as e:
            print(f"API{i+1}请求异常: {str(e)}")
            continue

    print("所有API都无法使用")
    return None


async def call_llm_async(prompt: str, system_prompt: str | None = None,
                         model_type: str = "instruct") -> str | None:
    """异步 LLM 调用（AsyncOpenAI），自动在多个供应商之间 fallback。"""
    api_configs = get_api_configs(model_type)
    messages = build_messages(prompt, system_prompt)

    for i, config in enumerate(api_configs):
        try:
            client = AsyncOpenAI(base_url=config["base_url"], api_key=config["key"])
            response = await client.chat.completions.create(
                model=config["model"],
                messages=messages,
                timeout=60.0,
            )
            content = response.choices[0].message.content
            if i > 0:
                print(f"成功切换到备用API{i+1}")
            return content
        except Exception as e:
            print(f"API{i+1}请求异常: {str(e)}")
            continue

    print("所有API都无法使用")
    return None

