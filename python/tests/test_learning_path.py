import unittest

from common.learning_path import (
    LearningPathError,
    build_discussion_learning_path,
    parse_learning_path,
)


class LearningPathTest(unittest.TestCase):
    def test_builds_connected_discussion_plan_from_ordered_core_knowledge(self):
        core = [
            {"name": "降维目标", "content": "用更少特征保留主要信息"},
            {"name": "方差与协方差", "content": "描述变化大小和共同变化"},
            {"name": "主成分选择", "content": "选择投影方差较大的方向"},
        ]

        path = build_discussion_learning_path(core)

        self.assertEqual([1, 2, 3], [step["step_number"] for step in path])
        self.assertEqual("降维目标", path[0]["title"])
        self.assertIn("方差与协方差", path[0]["connection"])
        self.assertIn("主成分选择", path[1]["connection"])
        self.assertIn("完成整体理解", path[2]["connection"])
        self.assertEqual(
            ["降维目标", "方差与协方差", "主成分选择"],
            [step["knowledge_points"][0] for step in path],
        )

    def test_parses_ordered_learning_path_and_splits_knowledge_points(self):
        steps = {
            "步骤1": {
                "学习主题": "理解降维与方差",
                "本步知识点": "降维；投影、方差",
                "承接关系": "先理解信息保留，为协方差矩阵建立基础。",
                "检验重点": "根据投影方差判断信息保留程度",
            },
            "步骤2": {
                "学习主题": "构造协方差矩阵",
                "本步知识点": "中心化, 协方差矩阵",
                "承接关系": "使用方差概念描述多个特征的共同变化。",
                "检验重点": "解释协方差矩阵如何描述特征关系",
            },
        }

        self.assertEqual([
            {
                "step_number": 1,
                "title": "理解降维与方差",
                "knowledge_points": ["降维", "投影", "方差"],
                "connection": "先理解信息保留，为协方差矩阵建立基础。",
                "assessment_focus": "根据投影方差判断信息保留程度",
            },
            {
                "step_number": 2,
                "title": "构造协方差矩阵",
                "knowledge_points": ["中心化", "协方差矩阵"],
                "connection": "使用方差概念描述多个特征的共同变化。",
                "assessment_focus": "解释协方差矩阵如何描述特征关系",
            },
        ], parse_learning_path(steps))

    def test_rejects_missing_fields(self):
        with self.assertRaisesRegex(LearningPathError, "本步知识点"):
            parse_learning_path({"步骤1": {"学习主题": "降维", "承接关系": "起点", "检验重点": "判断目标"}})

    def test_rejects_nonconsecutive_steps(self):
        steps = {
            "步骤2": {"学习主题": "降维", "本步知识点": "方差", "承接关系": "起点", "检验重点": "判断目标"}
        }
        with self.assertRaisesRegex(LearningPathError, "连续"):
            parse_learning_path(steps)

    def test_rejects_duplicate_assessment_focus(self):
        step = {"学习主题": "降维", "本步知识点": "方差", "承接关系": "起点", "检验重点": "判断目标"}
        with self.assertRaisesRegex(LearningPathError, "不可重复"):
            parse_learning_path({"步骤1": step, "步骤2": {**step, "学习主题": "协方差"}})


if __name__ == "__main__":
    unittest.main()
