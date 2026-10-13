import os
import unittest
from unittest.mock import patch

from tools.llm_api_service import get_api_configs


class LlmApiServiceTests(unittest.TestCase):
    def test_prefers_the_configured_dashscope_provider(self):
        with patch.dict(os.environ, {
            "OPENROUTER_API_URL": "https://openrouter.example/v1/",
            "OPENROUTER_API_KEY": "openrouter-key",
            "OPENROUTER_MODEL_LARGE": "openrouter-model",
            "QWEN_API_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1/",
            "QWEN_API_KEY": "dashscope-key",
            "QWEN_MODEL_LARGE": "qwen-plus",
        }, clear=False):
            configs = get_api_configs("large")

        self.assertEqual("qwen-plus", configs[0]["model"])

