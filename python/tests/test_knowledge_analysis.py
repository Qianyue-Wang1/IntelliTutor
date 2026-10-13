import asyncio
import json
import unittest
from unittest.mock import AsyncMock, Mock

from common.knowledge_analysis import (
    KnowledgeAnalysisError,
    parse_knowledge_analysis,
    salvage_core_knowledge,
)
from explanation.prompt.zh.prompt import EXTRACT_KNOWLEDGE_POINTS_PROMPT
from discussion.prompt.zh.prompt import ANALYZE_PROMPT

SERVICE_IMPORT_ERROR = None
try:
    from discussion.discussionSession import DiscussionSystem
    from explanation.explanationSession import ExplanationSession
    from server import _apply_explanation_knowledge_analysis
except ModuleNotFoundError as error:
    SERVICE_IMPORT_ERROR = error


VALID_PAYLOAD = {
    "core_knowledge": [
        {
            "id": 1,
            "name": "平方的运算规则",
            "type": "运算类",
            "level": "基础层级",
            "content": "$a^2=a\\times a$，平方是乘法的特殊形式",
        },
        {
            "id": 2,
            "name": "完全平方差公式",
            "type": "公式类",
            "level": "核心方法层级",
            "content": "$(a-b)^2=a^2-2ab+b^2$",
        },
    ],
    "problem_solving_hint": {
        "basic_idea": "分别计算两个数的平方，再相加求和",
        "skill_enlightenment": "观察98接近100，可写成100-2。联想完全平方差公式，应该如何代入？",
        "core_mathematical_thought": "凑整简化、化归思想",
        "related_method": "完全平方差公式",
    },
}


GENERAL_PAYLOAD = {
    "core_knowledge": [
        {
            "id": 1,
            "name": "工业革命的背景事实",
            "type": "事实类",
            "level": "基础层级",
            "content": "工业革命发生需要人口、资本、市场和技术等条件",
        },
        {
            "id": 2,
            "name": "历史因果分析方法",
            "type": "方法类",
            "level": "核心方法层级",
            "content": "区分长期条件、直接原因和推动因素",
        },
    ],
    "problem_solving_hint": {
        "basic_idea": "先识别背景条件，再区分不同原因的作用",
        "skill_enlightenment": "观察材料中的时间与条件，联想因果层次，再判断各因素如何共同作用",
        "core_mathematical_thought": "多因素因果分析",
        "related_method": "历史因果分析方法",
    },
}


def payload_text(payload=VALID_PAYLOAD):
    return json.dumps(payload, ensure_ascii=False)


class KnowledgeAnalysisParserTest(unittest.TestCase):
    def test_parses_general_domain_knowledge_types(self):
        analysis = parse_knowledge_analysis(payload_text(GENERAL_PAYLOAD))

        self.assertEqual("事实类", analysis.core_knowledge[0]["type"])
        self.assertEqual("方法类", analysis.core_knowledge[1]["type"])
        self.assertEqual("历史因果分析方法", analysis.problem_solving_hint["related_method"])

    def test_parses_programming_skill_knowledge_type(self):
        payload = {
            "core_knowledge": [
                {
                    "id": 1,
                    "name": "列表推导式语法",
                    "type": "规则类",
                    "level": "基础层级",
                    "content": "用表达式、for 子句和可选 if 子句构造列表",
                },
                {
                    "id": 2,
                    "name": "列表推导式改写技能",
                    "type": "技能类",
                    "level": "核心方法层级",
                    "content": "将简单循环的累积逻辑改写为清晰的列表推导式",
                },
            ],
            "problem_solving_hint": {
                "basic_idea": "先识别循环中的输出表达式和筛选条件",
                "skill_enlightenment": "对照循环的每一步，决定表达式、遍历对象和筛选条件",
                "core_mathematical_thought": "结构化改写",
                "related_method": "列表推导式改写技能",
            },
        }

        analysis = parse_knowledge_analysis(payload_text(payload))

        self.assertEqual("技能类", analysis.core_knowledge[1]["type"])
        self.assertEqual("列表推导式改写技能", analysis.problem_solving_hint["related_method"])

    def test_parses_pure_or_fenced_json_and_normalizes_whitespace(self):
        pure = parse_knowledge_analysis(payload_text())
        fenced = parse_knowledge_analysis(f"说明文字\n```json\n{payload_text()}\n```")

        self.assertEqual("完全平方差公式", pure.problem_solving_hint["related_method"])
        self.assertEqual(pure.core_knowledge, fenced.core_knowledge)

    def test_rejects_missing_level_and_unlinked_method(self):
        invalid = json.loads(payload_text())
        invalid["core_knowledge"] = invalid["core_knowledge"][:1]
        invalid["problem_solving_hint"]["related_method"] = "不存在的方法"

        with self.assertRaises(KnowledgeAnalysisError) as raised:
            parse_knowledge_analysis(payload_text(invalid))

        self.assertIn("缺少核心方法层级", str(raised.exception))
        self.assertIn("完全一致", str(raised.exception))
        self.assertEqual(1, len(salvage_core_knowledge(payload_text(invalid))))

    def test_prompts_remain_safe_for_str_format(self):
        self.assertIn("98²+2²", EXTRACT_KNOWLEDGE_POINTS_PROMPT.format(question="98²+2²"))
        self.assertIn("98²+2²", ANALYZE_PROMPT.format(question="98²+2²"))


@unittest.skipIf(
    SERVICE_IMPORT_ERROR is not None,
    f"Python service dependencies are not installed: {getattr(SERVICE_IMPORT_ERROR, 'name', '')}",
)
class KnowledgeAnalysisRetryTest(unittest.TestCase):
    def test_explanation_retries_once_and_accepts_repaired_json(self):
        session = ExplanationSession("explanation-1", "98²+2²")
        session.call_llm_async = AsyncMock(return_value=payload_text())

        asyncio.run(_apply_explanation_knowledge_analysis(session, "not json", session.question))

        session.call_llm_async.assert_awaited_once()
        self.assertEqual("完全平方差公式", session.problem_solving_hint["related_method"])
        self.assertIn("完全平方差公式", session.knowledge_dict)

    def test_discussion_retries_once_then_degrades_without_hint(self):
        invalid = json.loads(payload_text())
        invalid["problem_solving_hint"]["related_method"] = "不存在的方法"
        session = DiscussionSystem("discussion-1", "98²+2²")
        session.call_llm = Mock(side_effect=["not json", payload_text(invalid)])

        analysis = session.analyze_question()

        self.assertEqual(2, session.call_llm.call_count)
        self.assertIsNone(session.problem_solving_hint)
        self.assertEqual(2, len(session.core_knowledge))
        self.assertIn("完全平方差公式", analysis)


if __name__ == "__main__":
    unittest.main()
