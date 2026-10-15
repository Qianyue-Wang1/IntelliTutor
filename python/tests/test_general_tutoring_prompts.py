from pathlib import Path
import unittest

from common.knowledge_analysis import parse_knowledge_analysis
from explanation.prompt.zh.prompt import (
    EXTRACT_KNOWLEDGE_POINTS_PROMPT,
    GENERATE_STEPS_PROMPT,
    INCORRECT_ANSWER_PROMPT,
    ANALYZE_BEHAVIOR_PROMPT,
    ANALYZE_USER_INTENT_PROMPT,
    INSTRUCTION_EXPLANATION_PROMPT,
    OPTION_GENERATION_PROMPT,
    OPTION_GENERATION_PERSONALIZED_PROMPT,
    PARSE_USER_IDEA_PROMPT,
    SYSTEM_PROMPT_PERSONALIZED,
)
from discussion.prompt.zh.prompt import (
    ANALYZE_BEHAVIOR_PROMPT as DISCUSSION_ANALYZE_BEHAVIOR_PROMPT,
    ANALYZE_PROMPT,
    GENERATE_NEXT_STEP,
    GENERATE_NEXT_STEP_UPDATED,
    GENERATE_NEXT_SUBSTEP,
    GENERATE_NEXT_SUBSTEP_UPDATED,
    HANDLE_PARENT_STEP_STATUS,
    HANDLE_PARENT_STEP_STATUS_UPDATED,
    HANDLE_USER_FEEDBACK,
    HANDLE_USER_FEEDBACK_UPDATED,
    INIT_STEP_PROMPT,
    INSTRUCTION_PROMPT as DISCUSSION_INSTRUCTION_PROMPT,
    JUDGE_END_STATUS,
    JUDGE_END_STATUS_UPDATED,
    SUMMARY_PROMPT,
    SYSTEM_PROMPT,
    SYSTEM_PROMPT_PERSONALIZED as DISCUSSION_SYSTEM_PROMPT_PERSONALIZED,
    SYSTEM_PROMPT_PERSONALIZED_UPDATED,
)
from KT.ours.prompt import (
    ANNO_DIALOGUE_CORRECTNESS,
    ANNO_KC_SYSTEM_PROMPT,
    GENERATE_ANSWER_PROMPT,
    KT_SYSTEM_PROMPT,
    LLM_KT_SYSTEM_PROMPT,
    POST_DISCUSSION_KT_PROMPT,
    UPDATE_USER_PROFILE_PROMPT,
)
from common.prompt.zh.prompt import (
    CHECK_INPUT_PROMPT,
    GEOGRABRA_SYSTEM_PROMPT,
    IMAGE_INPUT_PROMPT,
    PARSE_USER_INTENT_PROMPT as COMMON_PARSE_USER_INTENT_PROMPT,
)


def load_ours_annotate_source():
    annotate_path = Path(__file__).resolve().parents[1] / "KT" / "ours" / "annotate.py"
    return annotate_path.read_text(encoding="utf-8")


class GeneralKnowledgeContractTest(unittest.TestCase):
    def test_explanation_prompts_define_progressive_learning_contract(self):
        self.assertIn("学习原因", EXTRACT_KNOWLEDGE_POINTS_PROMPT)
        self.assertIn("前置知识", EXTRACT_KNOWLEDGE_POINTS_PROMPT)
        self.assertIn("知识点之间", EXTRACT_KNOWLEDGE_POINTS_PROMPT)
        self.assertIn("学习顺序", EXTRACT_KNOWLEDGE_POINTS_PROMPT)
        self.assertIn("学习目标", GENERATE_STEPS_PROMPT)
        self.assertIn("详细讲解", GENERATE_STEPS_PROMPT)
        self.assertIn("掌握检验", OPTION_GENERATION_PROMPT)
        self.assertIn("单答案客观题", OPTION_GENERATION_PROMPT)
        self.assertIn("不得生成主观题", OPTION_GENERATION_PROMPT)
        self.assertIn("正确答案解析", INCORRECT_ANSWER_PROMPT)

    def test_each_learning_step_teaches_a_self_contained_knowledge_bundle(self):
        for required_part in (
            "核心概念及术语含义",
            "原理、因果关系或推导过程",
            "具体例子或演示",
            "常见误区",
        ):
            self.assertIn(required_part, GENERATE_STEPS_PROMPT)
        self.assertIn("300～600 字", GENERATE_STEPS_PROMPT)
        self.assertIn("不得只写", GENERATE_STEPS_PROMPT)
        self.assertIn("直接讲解知识本身", GENERATE_STEPS_PROMPT)
        self.assertIn("不得询问学习者是否已经了解", GENERATE_STEPS_PROMPT)
        self.assertIn("必须在当前讲解中补充", GENERATE_STEPS_PROMPT)
        self.assertIn("不要把“确认基础”", GENERATE_STEPS_PROMPT)
        self.assertIn("允许正确答案直接来自知识讲解", OPTION_GENERATION_PROMPT)
        for forbidden_meta_phrase in (
            "首先明晰你的需求",
            "需要确认你是否了解",
            "本步学习目标是",
            "掌握检验时",
        ):
            self.assertIn(forbidden_meta_phrase, GENERATE_STEPS_PROMPT)

    def test_mastery_check_options_are_clear_without_ambiguous_references(self):
        for prompt in (OPTION_GENERATION_PROMPT, OPTION_GENERATION_PERSONALIZED_PROMPT):
            self.assertIn("脱离其他选项也能独立理解", prompt)
            self.assertIn("明确写出判断对象和结论", prompt)
            self.assertIn("该说法", prompt)
            self.assertIn("这种情况", prompt)
            self.assertIn("上述内容", prompt)
            self.assertIn("必须直接重述", prompt)

    def test_accepts_cross_domain_knowledge_types(self):
        payload = """
        {
          "core_knowledge": [
            {"id": 1, "name": "现在完成时的构成", "type": "规则类", "level": "基础层级", "content": "have/has 加过去分词"},
            {"id": 2, "name": "语境辨析方法", "type": "方法类", "level": "核心方法层级", "content": "根据时间标志和语义判断时态"}
          ],
          "problem_solving_hint": {
            "basic_idea": "先识别时间语境，再选择时态结构",
            "skill_enlightenment": "观察时间标志，联想动作与现在的联系，再判断应使用哪种时态",
            "core_mathematical_thought": "语境与形式相互验证",
            "related_method": "语境辨析方法"
          }
        }
        """

        analysis = parse_knowledge_analysis(payload)

        self.assertEqual("规则类", analysis.core_knowledge[0]["type"])
        self.assertEqual("方法类", analysis.core_knowledge[1]["type"])

    def test_core_prompts_define_a_general_learning_tutor(self):
        prompts = (
            SYSTEM_PROMPT_PERSONALIZED,
            EXTRACT_KNOWLEDGE_POINTS_PROMPT,
            SYSTEM_PROMPT,
            ANALYZE_PROMPT,
            KT_SYSTEM_PROMPT,
            LLM_KT_SYSTEM_PROMPT,
        )

        for prompt in prompts:
            self.assertNotIn("数学老师", prompt)
            self.assertNotIn("数学教育专家", prompt)
            self.assertNotIn("初中数学", prompt)

        self.assertIn("学习辅导", SYSTEM_PROMPT_PERSONALIZED)
        self.assertIn("学习任务", EXTRACT_KNOWLEDGE_POINTS_PROMPT)

    def test_math_specific_geogebra_capability_is_preserved(self):
        self.assertIn("GeoGebra", GEOGRABRA_SYSTEM_PROMPT)
        self.assertTrue("几何" in GEOGRABRA_SYSTEM_PROMPT or "函数" in GEOGRABRA_SYSTEM_PROMPT)

    def test_explanation_prompts_support_general_learning_tasks_without_changing_protocol(self):
        prompts = (
            SYSTEM_PROMPT_PERSONALIZED,
            GENERATE_STEPS_PROMPT,
            OPTION_GENERATION_PROMPT,
            INCORRECT_ANSWER_PROMPT,
        )

        for prompt in prompts:
            self.assertIn("学习任务", prompt)
            self.assertNotIn("初中数学", prompt)

        self.assertIn("开放任务", GENERATE_STEPS_PROMPT)
        self.assertIn("不确定", GENERATE_STEPS_PROMPT)
        self.assertIn("## 解题步骤", GENERATE_STEPS_PROMPT)
        self.assertIn("分析与提示", GENERATE_STEPS_PROMPT)
        self.assertIn("具体操作", GENERATE_STEPS_PROMPT)
        self.assertIn("第 1 个为正确选项", OPTION_GENERATION_PROMPT)
        self.assertIn("后 3 个为混淆选项", OPTION_GENERATION_PROMPT)

    def test_explanation_prompt_forbids_invented_precision_and_domain_shortcuts(self):
        self.assertIn("即使作为示例、假设、占位内容或方法演示", SYSTEM_PROMPT_PERSONALIZED)
        self.assertIn("错误：[待核实：2023年某白皮书]", SYSTEM_PROMPT_PERSONALIZED)
        self.assertIn("正确：[待核实：支持该判断的同行评审研究]", SYSTEM_PROMPT_PERSONALIZED)
        self.assertIn("精确年份、百分比、统计数据、文献名或来源", GENERATE_STEPS_PROMPT)
        self.assertIn("不得填入猜测值", GENERATE_STEPS_PROMPT)
        self.assertIn("包括作为示例、占位符或假设的数值", GENERATE_STEPS_PROMPT)
        self.assertIn("变量或高/中/低", GENERATE_STEPS_PROMPT)
        self.assertIn("肯定、否定、疑问", GENERATE_STEPS_PROMPT)
        self.assertIn("直接产物、中间产物和后续合成产物", GENERATE_STEPS_PROMPT)
        self.assertIn("卡尔文循环的直接产物", GENERATE_STEPS_PROMPT)
        self.assertIn("每个顶层部分只能出现一次", GENERATE_STEPS_PROMPT)
        self.assertIn("待核实占位符中含有四位年份、机构名或文献名", GENERATE_STEPS_PROMPT)
        self.assertIn("只能写在第 3 部分的最后一个子步骤内", GENERATE_STEPS_PROMPT)

    def test_explanation_prompt_templates_accept_runtime_arguments(self):
        question = "解释光合作用的主要步骤"
        operation = "识别反应场所和输入输出物"

        rendered_prompts = (
            SYSTEM_PROMPT_PERSONALIZED.format(
                user_profile="学习者偏好图示",
                knowledge_graph="尚无掌握度记录",
            ),
            EXTRACT_KNOWLEDGE_POINTS_PROMPT.format(question=question),
            GENERATE_STEPS_PROMPT.format(question=question),
            OPTION_GENERATION_PROMPT.format(
                question=question,
                analysis="先建立光反应与碳反应的关系",
                current_step_specific_operation_and_correct_answer=operation,
                assessment_focus="区分光反应与碳反应的场所和产物",
                previous_assessments="暂无前序检验记录",
            ),
            OPTION_GENERATION_PERSONALIZED_PROMPT.format(
                question=question,
                analysis="先建立光反应与碳反应的关系",
                current_step_specific_operation_and_correct_answer=operation,
                common_mistakes="把场所与产物混淆",
                study_habits="倾向先看图示",
                assessment_focus="区分光反应与碳反应的场所和产物",
                previous_assessments="暂无前序检验记录",
            ),
            INCORRECT_ANSWER_PROMPT.format(
                question=question,
                lesson="光合作用把光能转化为化学能。",
                correct_answer=operation,
                incorrect_answer="在线粒体内完成",
            ),
            PARSE_USER_IDEA_PROMPT.format(question=question, user_input="先在叶绿体中吸收光能"),
            ANALYZE_USER_INTENT_PROMPT.format(question=question, user_input="我想知道下一步"),
            ANALYZE_BEHAVIOR_PROMPT.format(
                question=question,
                current_step=operation,
                user_feedback="我认为发生在叶绿体",
                behavior_data="停留 20 秒后作答",
                analysis="学习者已识别场所",
            ),
            INSTRUCTION_EXPLANATION_PROMPT.format(
                profile_str="偏好图示",
                weak_points_str="输入输出物关系",
                question=question,
            ),
        )

        self.assertTrue(all(question in prompt for prompt in rendered_prompts[1:]))
        self.assertIn("### 错在哪里", rendered_prompts[5])
        self.assertIn("### 正确理解", rendered_prompts[5])
        self.assertFalse(rendered_prompts[5].rstrip().endswith("错误分析提示："))

    def test_discussion_prompts_support_general_learning_tasks_and_keep_protocols(self):
        prompts = (
            SYSTEM_PROMPT,
            DISCUSSION_SYSTEM_PROMPT_PERSONALIZED,
            SYSTEM_PROMPT_PERSONALIZED_UPDATED,
            INIT_STEP_PROMPT,
            HANDLE_USER_FEEDBACK,
            HANDLE_USER_FEEDBACK_UPDATED,
            HANDLE_PARENT_STEP_STATUS,
            HANDLE_PARENT_STEP_STATUS_UPDATED,
            GENERATE_NEXT_STEP,
            GENERATE_NEXT_STEP_UPDATED,
            GENERATE_NEXT_SUBSTEP,
            GENERATE_NEXT_SUBSTEP_UPDATED,
            JUDGE_END_STATUS,
            JUDGE_END_STATUS_UPDATED,
            SUMMARY_PROMPT,
            DISCUSSION_ANALYZE_BEHAVIOR_PROMPT,
            DISCUSSION_INSTRUCTION_PROMPT,
        )

        for prompt in prompts:
            self.assertIn("学习任务", prompt)
            self.assertNotIn("初中数学", prompt)

        self.assertIn("概念", INIT_STEP_PROMPT)
        self.assertIn("开放任务", GENERATE_NEXT_SUBSTEP_UPDATED)
        self.assertIn("事实", JUDGE_END_STATUS_UPDATED)
        self.assertIn('"status"', HANDLE_USER_FEEDBACK_UPDATED)
        self.assertIn("完成", HANDLE_PARENT_STEP_STATUS_UPDATED)
        self.assertIn("## 初始解题步骤", INIT_STEP_PROMPT)
        self.assertIn("## 下一步解题步骤", GENERATE_NEXT_STEP_UPDATED)
        self.assertIn("## 下一步解题子步骤", GENERATE_NEXT_SUBSTEP_UPDATED)
        self.assertIn("问题已解决", JUDGE_END_STATUS_UPDATED)
        self.assertIn("问题未解决", JUDGE_END_STATUS_UPDATED)
        self.assertIn("## 问题总结", SUMMARY_PROMPT)

    def test_discussion_prompt_templates_accept_runtime_arguments(self):
        question = "分析工业革命为什么首先发生在英国"
        current_step = "区分长期条件和直接诱因"
        history = '[{"role": "user", "content": "我认为英国有资本"}]'

        rendered_prompts = (
            DISCUSSION_SYSTEM_PROMPT_PERSONALIZED.format(
                user_profile="偏好先整理时间线",
                knowledge_graph="历史因果分析：中等掌握",
            ),
            SYSTEM_PROMPT_PERSONALIZED_UPDATED.format(
                user_profile="偏好先整理时间线",
                knowledge_graph="历史因果分析：中等掌握",
            ),
            INIT_STEP_PROMPT.format(question=question),
            HANDLE_USER_FEEDBACK.format(
                question=question,
                current_step=current_step,
                user_feedback="资本积累提供了投资条件",
            ),
            HANDLE_USER_FEEDBACK_UPDATED.format(
                question=question,
                current_step=current_step,
                user_feedback="资本积累提供了投资条件",
            ),
            HANDLE_PARENT_STEP_STATUS.format(
                question=question,
                current_step=current_step,
                sub_history=history,
            ),
            HANDLE_PARENT_STEP_STATUS_UPDATED.format(
                question=question,
                current_step=current_step,
                sub_history=history,
            ),
            GENERATE_NEXT_STEP.format(
                question=question,
                current_step=current_step,
                accomplished_step="已说明资本条件",
                next_step_number="2",
                history=history,
            ),
            GENERATE_NEXT_STEP_UPDATED.format(
                question=question,
                current_step=current_step,
                accomplished_step="已说明资本条件",
                next_step_number="2",
                history=history,
            ),
            GENERATE_NEXT_SUBSTEP.format(
                question=question,
                current_step=current_step,
                user_feedback="我不知道直接诱因",
                next_step_number="1.1",
                history=history,
            ),
            GENERATE_NEXT_SUBSTEP_UPDATED.format(
                question=question,
                current_step=current_step,
                user_feedback="我不知道直接诱因",
                next_step_number="1.1",
                history=history,
            ),
            JUDGE_END_STATUS.format(question=question, history=history),
            JUDGE_END_STATUS_UPDATED.format(question=question, history=history),
            SUMMARY_PROMPT.format(question=question, history=history),
            DISCUSSION_ANALYZE_BEHAVIOR_PROMPT.format(
                question=question,
                current_step=current_step,
                user_feedback="资本积累提供了投资条件",
                behavior_data="停留 30 秒后作答",
                analysis="学习者能提出一个长期条件",
            ),
            DISCUSSION_INSTRUCTION_PROMPT.format(
                profile_str="偏好先整理时间线",
                weak_points_str="区分原因层次",
                question=question,
            ),
        )

        self.assertTrue(all(question in prompt for prompt in rendered_prompts[2:]))

    def test_knowledge_tracking_prompts_support_general_learning_and_keep_boolean_protocol(self):
        prompts = (
            GENERATE_ANSWER_PROMPT,
            ANNO_KC_SYSTEM_PROMPT,
            ANNO_DIALOGUE_CORRECTNESS,
            POST_DISCUSSION_KT_PROMPT,
            KT_SYSTEM_PROMPT,
            LLM_KT_SYSTEM_PROMPT,
            UPDATE_USER_PROFILE_PROMPT,
        )

        for prompt in prompts:
            self.assertIn("学习任务", prompt)
            self.assertNotIn("数学教育", prompt)
            self.assertNotIn("数学问题", prompt)

        self.assertIn("True", KT_SYSTEM_PROMPT)
        self.assertIn("False", KT_SYSTEM_PROMPT)
        self.assertIn("true/false/na", ANNO_DIALOGUE_CORRECTNESS)
        self.assertIn('"common_mistakes"', UPDATE_USER_PROFILE_PROMPT)
        self.assertIn('"study_habits"', UPDATE_USER_PROFILE_PROMPT)
        self.assertIn("通用学习标注专家", load_ours_annotate_source())

    def test_input_prompts_accept_cross_domain_text_and_images(self):
        for prompt in (COMMON_PARSE_USER_INTENT_PROMPT, CHECK_INPUT_PROMPT, IMAGE_INPUT_PROMPT):
            self.assertIn("学习任务", prompt)
            self.assertNotIn("初中数学", prompt)

        self.assertIn("英语", CHECK_INPUT_PROMPT)
        self.assertIn("历史", CHECK_INPUT_PROMPT)
        self.assertIn("编程", CHECK_INPUT_PROMPT)
        self.assertIn("表格", IMAGE_INPUT_PROMPT)
        self.assertIn("图示", IMAGE_INPUT_PROMPT)
        self.assertIn("仅对数学公式", IMAGE_INPUT_PROMPT)
        self.assertNotIn("其他学科内容", CHECK_INPUT_PROMPT)

        parsed_intent = COMMON_PARSE_USER_INTENT_PROMPT.format(
            question="解释现在完成时的使用条件",
            user_input="我知道 have 加过去分词，但分不清和一般过去时的区别",
        )
        checked_input = CHECK_INPUT_PROMPT.format(user_input="工业革命为何首先发生在英国？")
        self.assertIn("现在完成时", parsed_intent)
        self.assertIn("工业革命", checked_input)

    def test_direct_session_prompt_is_not_math_only(self):
        server_path = Path(__file__).resolve().parents[1] / "server.py"
        server_source = server_path.read_text(encoding="utf-8")

        self.assertIn("通用学习辅导者", server_source)
        self.assertNotIn("你是一名严谨的数学教师", server_source)

    def test_runtime_session_defaults_are_not_math_only(self):
        python_root = Path(__file__).resolve().parents[1]
        explanation_source = (python_root / "explanation" / "explanationSession.py").read_text(encoding="utf-8")
        runtime_kt_source = (python_root / "KT" / "ours" / "main.py").read_text(encoding="utf-8")

        self.assertIn("通用学习辅导者", explanation_source)
        self.assertNotIn("初中数学解题专家", explanation_source)
        self.assertNotIn("学生正在尝试解决一个数学问题", runtime_kt_source)


if __name__ == "__main__":
    unittest.main()
