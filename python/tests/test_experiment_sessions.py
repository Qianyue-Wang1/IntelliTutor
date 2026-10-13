import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from KT.ours.main import evaluate_mastery_with_api
from discussion.planning import (
    AnswerEvaluation,
    PlanningDecision,
    create_initial_plan,
    project_discussion_plan,
)
from common.learner_state import learner_state_key

try:
    import server as server_module
    from discussion.discussionSession import DiscussionSystem
    from explanation.explanationSession import ExplanationSession
    from server import (
        DirectFollowupRequest,
        DirectInitRequest,
        DirectRestoreRequest,
        DiscussionInitRequest,
        InitRequest,
        RestoreSessionRequest,
        StepNodeData,
        StepOption,
        direct_followup,
        init_direct_session,
        get_current_step,
        restore_direct_session,
        restore_explanation_session,
        session_manager,
        submit_answer,
    )
except ModuleNotFoundError as error:
    raise unittest.SkipTest(f"Python service dependencies are not installed: {error.name}") from error


class ExperimentSessionContractTest(unittest.TestCase):
    def tearDown(self):
        session_manager.sessions.clear()
        session_manager.step_generation_locks.clear()

    def test_direct_snapshot_round_trip_keeps_assigned_question_and_messages(self):
        response = asyncio.run(restore_direct_session(DirectRestoreRequest(
            java_session_id="java-direct",
            user_id="student-1",
            snapshot={
                "question": "immutable assigned question",
                "messages": [{"role": "assistant", "kind": "initial_explanation", "content": "answer"}],
                "initial_explanation_generated": True,
                "experiment_run_id": "run-1",
            },
        )))
        session = session_manager.get_session(response["data"]["session_id"])
        self.assertEqual("immutable assigned question", session.question)
        self.assertEqual("run-1", session.experiment_run_id)
        self.assertTrue(session.initial_explanation_generated)
        self.assertEqual(1, len(session.messages))

    def test_direct_initial_explanation_removes_internal_instruction_echo(self):
        with patch.object(server_module, "call_llm_async", new=AsyncMock(return_value=(
            "聚类会将相似样本归入同一组。\n\n"
            "以下为严谨、分层、概念清晰的完整讲解，严格限定在议题范围内，并引用权威资料。"
        ))):
            response = asyncio.run(init_direct_session(DirectInitRequest(
                question="请讲解聚类任务。",
                user_id="student-1",
            )))

        content = response["data"]["messages"][1]["content"]
        self.assertEqual("聚类会将相似样本归入同一组。", content)

    def test_structured_init_requests_preserve_experiment_learner_state_scope(self):
        scope = "experiment:experiment-1:assignment:assignment-1"
        explanation = InitRequest(processedQuestion="q", learnerStateScope=scope)
        discussion = DiscussionInitRequest(processedQuestion="q", learnerStateScope=scope)

        self.assertEqual(scope, explanation.learnerStateScope)
        self.assertEqual(scope, discussion.learnerStateScope)

    def test_structured_init_requests_accept_explicit_teaching_context(self):
        context = {
            "displayQuestion": "本轮学习主题：K-means 聚类",
            "topic": "K-means 聚类",
            "objectives": ["解释聚类目标"],
            "pretestSummary": {"P1": False},
            "priorRoundState": [{"round": 1, "learnerState": "READY"}],
            "interactionLimit": 30,
            "forceCoverageOnFinalTurn": True,
        }

        explanation = InitRequest(processedQuestion="q", **context)
        discussion = DiscussionInitRequest(processedQuestion="q", **context)

        self.assertEqual("K-means 聚类", explanation.topic)
        self.assertEqual(["解释聚类目标"], discussion.objectives)
        self.assertEqual("本轮学习主题：K-means 聚类", explanation.displayQuestion)
        self.assertEqual("本轮学习主题：K-means 聚类", discussion.displayQuestion)
        self.assertEqual(30, explanation.interactionLimit)
        self.assertTrue(discussion.forceCoverageOnFinalTurn)

    def test_learner_state_key_scopes_experiment_history_without_changing_regular_users(self):
        self.assertEqual("student-1", learner_state_key("student-1", None))
        self.assertNotEqual(
            learner_state_key("student-1", "experiment:e1:assignment:a1"),
            learner_state_key("student-1", "experiment:e1:assignment:a2"),
        )

    def test_explanation_long_term_prompt_reads_knowledge_with_scoped_identity(self):
        session = ExplanationSession("round-2", "q", "student-1")
        session.cross_session_enabled = True
        session.learner_state_scope = "experiment:e1:assignment:a1"
        session.user_profile_text = "第一轮记录"
        session.weak_points = "薄弱点"
        session.related_kps = [{"id": "kp-1", "description": "知识点"}]

        with patch("tools.neo4j_service.query_knowledge_points_mastery", return_value=[]) as query:
            session.build_personalized_system_prompt()

        self.assertEqual(
            learner_state_key("student-1", "experiment:e1:assignment:a1"),
            query.call_args.kwargs["user_id"],
        )

    def test_discussion_long_term_prompt_reads_knowledge_with_scoped_identity(self):
        session = DiscussionSystem("round-2", "q", "student-1")
        session.cross_session_enabled = True
        session.learner_state_scope = "experiment:e1:assignment:a1"
        session.user_profile_text = "第一轮记录"
        session.weak_points = "薄弱点"
        session.related_kps = [{"id": "kp-1", "description": "知识点"}]

        with patch("tools.neo4j_service.query_knowledge_points_mastery", return_value=[]) as query:
            session.build_system_prompt_personalized({})

        self.assertEqual(
            learner_state_key("student-1", "experiment:e1:assignment:a1"),
            query.call_args.kwargs["user_id"],
        )

    def test_explanation_session_has_no_local_ttl_helpers(self):
        session = ExplanationSession("explanation", "q", "student")
        self.assertFalse(hasattr(session, "call_ttl_llm"))
        self.assertFalse(hasattr(session, "trigger_ttl"))

    def test_mastery_evaluation_uses_bounded_json_from_normal_api(self):
        with patch("KT.ours.main.call_llm", return_value='{"mastery": 0.75}') as api:
            mastery = evaluate_mastery_with_api("system", "evidence")
        self.assertEqual(0.75, mastery)
        self.assertIn("只输出 JSON", api.call_args.args[0])

        with patch("KT.ours.main.call_llm", return_value='{"mastery": 1.2}'):
            self.assertIsNone(evaluate_mastery_with_api("system", "evidence"))

    def test_direct_followup_returns_complete_session_contract(self):
        session = session_manager.create_session("direct", "immutable assigned question", user_id="student-1")
        session.messages = [{"role": "assistant", "kind": "initial_explanation", "content": "answer"}]
        session.initial_explanation_generated = True

        with patch("server.call_llm_async", new=AsyncMock(return_value="follow-up answer")):
            response = asyncio.run(direct_followup(DirectFollowupRequest(
                session_id=session.session_id,
                message="请再解释一步",
            )))

        data = response["data"]
        self.assertEqual("immutable assigned question", data["question"])
        self.assertTrue(data["initial_explanation_generated"])
        self.assertEqual(3, len(data["messages"]))

    def test_direct_messages_remove_unavailable_image_markup(self):
        image_reply = "聚类流程如下：![K-means流程图](https://example.invalid/kmeans.png)\n先初始化中心，再分配样本。"

        with patch("server.call_llm_async", new=AsyncMock(return_value=image_reply)) as llm:
            response = asyncio.run(init_direct_session(DirectInitRequest(
                question="解释 K-means 聚类",
                user_id="student-1",
            )))

        content = response["data"]["messages"][-1]["content"]
        self.assertNotIn("![", content)
        self.assertNotIn("https://example.invalid", content)
        self.assertIn("K-means流程图", content)
        self.assertIn("不要输出 Markdown 图片", llm.await_args.args[0])

    def test_explanation_snapshot_restores_progress_and_locked_answer_index(self):
        response = asyncio.run(restore_explanation_session(RestoreSessionRequest(
            java_session_id="java-explanation",
            user_id="student-2",
            snapshot={
                "question": "q",
                "current_step": 2,
                "total_steps": 4,
                "current_step_true_answer_index": 3,
                "adaptive_mode": True,
                "cross_session_enabled": True,
                "persist_learner_state": True,
                "learner_state_scope": "experiment:experiment-1:assignment:assignment-1",
                "learner_state_status": "PENDING",
                "history": [{"role": "user", "content": "D"}],
            },
        )))
        session = session_manager.get_session(response["data"]["session_id"])
        self.assertEqual(2, session.current_step)
        self.assertEqual(3, session.current_step_true_answer_index)
        self.assertEqual([{"role": "user", "content": "D"}], session.history)
        self.assertTrue(session.cross_session_enabled)
        self.assertEqual("experiment:experiment-1:assignment:assignment-1", session.learner_state_scope)
        self.assertEqual("PENDING", session.learner_state_status)

    def test_explanation_snapshot_restores_answer_feedback(self):
        answer_result = {
            "is_correct": False,
            "selected_option": "B",
            "correct_option": "A",
            "answer_explanation": "B 混淆了两个概念",
            "awaiting_next": True,
        }
        response = asyncio.run(restore_explanation_session(RestoreSessionRequest(
            java_session_id="java-answer-feedback",
            snapshot={
                "question": "q",
                "current_step": 1,
                "total_steps": 3,
                "current_answer_result": answer_result,
                "answer_results": {"1": answer_result},
            },
        )))

        session = session_manager.get_session(response["data"]["session_id"])
        self.assertEqual(answer_result, session.current_answer_result)
        self.assertEqual({"1": answer_result}, session.answer_results)

    def test_legacy_explanation_snapshot_defaults_to_unanswered(self):
        response = asyncio.run(restore_explanation_session(RestoreSessionRequest(
            java_session_id="java-unanswered-legacy",
            snapshot={"question": "legacy"},
        )))

        session = session_manager.get_session(response["data"]["session_id"])
        self.assertIsNone(session.current_answer_result)
        self.assertEqual({}, session.answer_results)

    def test_structured_knowledge_round_trip_restores_hint(self):
        core_knowledge = [
            {"id": 1, "name": "平方运算", "type": "运算类", "level": "基础层级", "content": "$a^2=a\\times a$"},
            {"id": 2, "name": "完全平方差公式", "type": "公式类", "level": "核心方法层级", "content": "$(a-b)^2=a^2-2ab+b^2$"},
        ]
        hint = {
            "basic_idea": "分别求平方后相加",
            "skill_enlightenment": "观察数字特征并联想完全平方差公式，应该如何代入？",
            "core_mathematical_thought": "凑整与化归",
            "related_method": "完全平方差公式",
        }
        response = asyncio.run(restore_explanation_session(RestoreSessionRequest(
            java_session_id="java-structured",
            user_id="student-3",
            snapshot={
                "question": "98²+2²",
                "knowledge_points": [{"title": item["name"], "description": item["content"]} for item in core_knowledge],
                "core_knowledge": core_knowledge,
                "problem_solving_hint": hint,
            },
        )))

        session = session_manager.get_session(response["data"]["session_id"])
        self.assertEqual(core_knowledge, session.core_knowledge)
        self.assertEqual(hint, session.problem_solving_hint)

    def test_legacy_snapshot_restores_without_hint(self):
        response = asyncio.run(restore_explanation_session(RestoreSessionRequest(
            java_session_id="java-legacy",
            snapshot={
                "question": "legacy",
                "knowledge_points": [{"title": "旧知识点", "description": "旧解析"}],
            },
        )))

        session = session_manager.get_session(response["data"]["session_id"])
        self.assertEqual({"旧知识点": "旧解析"}, session.knowledge_dict)
        self.assertEqual([], session.core_knowledge)
        self.assertIsNone(session.problem_solving_hint)

    def test_basic_and_full_flags_follow_experiment_capabilities(self):
        basic = ExplanationSession("basic", "q", "u")
        basic.personalized_mode = False
        basic.adaptive_mode = True
        basic.cross_session_enabled = False
        basic.persist_learner_state = False

        full_round_one = DiscussionSystem("full-1", "q", "u")
        full_round_one.personalized_mode = True
        full_round_one.adaptive_mode = True
        full_round_one.cross_session_enabled = False
        full_round_one.persist_learner_state = True

        full_later_round = DiscussionSystem("full-2", "q", "u")
        full_later_round.personalized_mode = True
        full_later_round.adaptive_mode = True
        full_later_round.cross_session_enabled = True
        full_later_round.persist_learner_state = True

        self.assertFalse(basic.personalized_mode)
        self.assertTrue(basic.adaptive_mode)
        self.assertFalse(basic.cross_session_enabled)
        self.assertFalse(basic.persist_learner_state)
        self.assertTrue(full_round_one.personalized_mode)
        self.assertTrue(full_round_one.adaptive_mode)
        self.assertFalse(full_round_one.cross_session_enabled)
        self.assertTrue(full_round_one.persist_learner_state)
        self.assertTrue(full_later_round.personalized_mode)
        self.assertTrue(full_later_round.adaptive_mode)
        self.assertTrue(full_later_round.cross_session_enabled)
        self.assertTrue(full_later_round.persist_learner_state)

    def _ready_explanation_session(self, personalized=False, adaptive=False):
        session = session_manager.create_session("explanation", "1 + 1 = ?", user_id="student")
        session.personalized_mode = personalized
        session.adaptive_mode = adaptive
        session.current_step = 1
        session.step_count = 2
        session.current_step_true_answer_index = 0
        session.steps_data = {"步骤1": {"分析与提示": "计算加法", "具体操作": "求 1 + 1"}}
        session.steps_info = [StepNodeData(
            step_number=1,
            analysis="计算加法",
            operation_prefix="求和",
            options=["2", "3"],
            operation_summary_list=[],
        )]
        session.correct_selection_analysis = lambda lesson, correct_answer: "该选项符合本步原理，并可用于判断同类问题。"
        return session

    def test_behavior_processors_accept_missing_data(self):
        explanation = ExplanationSession("explanation", "q", "student")
        discussion = DiscussionSystem("discussion", "q", "student")

        self.assertIsNone(explanation.process_behavior_data(None, "A"))
        self.assertIsNone(discussion.process_behavior_data(None, "feedback"))

    def test_basic_explanation_submits_without_behavior_or_adaptive_annotation(self):
        session = self._ready_explanation_session()

        with patch.object(session, "process_behavior_data") as behavior_analysis:
            response = asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="A")))

        self.assertEqual(200, response["code"])
        self.assertEqual(1, session.current_step)
        self.assertFalse(response["data"]["in_summary"])
        self.assertTrue(response["data"]["is_correct"])
        self.assertTrue(response["data"]["awaiting_next"])
        self.assertEqual("A", response["data"]["correct_option"])
        self.assertTrue(response["data"]["answer_explanation"])
        behavior_analysis.assert_not_called()
        self.assertEqual([], session.annotation_history)

    def test_get_step_uses_a_dedicated_lesson_before_generating_options(self):
        session = session_manager.create_session("explanation", "什么是 PCA？", user_id="student")
        session.current_step = 1
        session.step_count = 2
        session.steps_data = {
            "步骤1": {
                "分析与提示": "确认 PCA 核心目标与数学基础",
                "具体操作": "[输入框]：请用一句话描述 PCA 的核心目标",
            }
        }
        lesson = (
            "PCA 是一种线性降维方法：它把原来多个可能相关的特征重新组合成少数互不相关的主成分。"
            "方差表示数据沿某个方向的分散程度；投影方差越大，说明该方向保留的数据变化越多。"
            "第一主成分就是使投影方差最大的单位方向，也就是协方差矩阵最大特征值对应的特征向量。"
        )

        with patch.object(
            session,
            "generate_step_lesson_async",
            new=AsyncMock(return_value=("PCA、第一主成分与选择依据", lesson)),
            create=True,
        ) as generate_lesson, patch.object(
            session,
            "generate_options_async",
            new=AsyncMock(return_value=["最大化投影方差", "最大化投影均值", "最小化特征数量", "最大化原始均值"]),
        ) as generate_options:
            response = asyncio.run(get_current_step(session.session_id))

        self.assertEqual("PCA、第一主成分与选择依据", response["data"]["operation_prefix"])
        self.assertEqual(lesson, response["data"]["analysis"])
        self.assertNotIn("确认 PCA 核心目标", response["data"]["analysis"])
        generate_lesson.assert_awaited_once_with(
            "确认 PCA 核心目标与数学基础",
            "[输入框]：请用一句话描述 PCA 的核心目标",
            "暂无前序步骤，这是第一个学习阶段。",
            {
                "step_number": 1,
                "title": "",
                "knowledge_points": [],
                "connection": "",
                "assessment_focus": "[输入框]：请用一句话描述 PCA 的核心目标",
            },
        )
        generate_options.assert_awaited_once_with(
            lesson,
            "[输入框]：请用一句话描述 PCA 的核心目标",
            "[输入框]：请用一句话描述 PCA 的核心目标",
            "暂无前序检验记录",
        )

    def test_later_step_lesson_receives_previous_lessons_to_avoid_repetition(self):
        session = ExplanationSession("lesson-context", "什么是 PCA？", "student")
        previous = "### 方差\n方差衡量数据的离散程度。\n\n### 第一主成分\n第一主成分使投影方差最大。"
        valid = "### 散点图\n散点图把样本画成坐标点，以观察分布方向。" + ("尺度会影响图形外观。" * 8) + "\n\n### 等比例坐标\n横纵轴应使用相同比例，避免方向被视觉拉伸。"

        with patch.object(session, "call_llm_async", new=AsyncMock(return_value=f"知识主题：散点图与等比例坐标\n知识讲解：{valid}")) as llm:
            asyncio.run(session.generate_step_lesson_async("绘制坐标系与散点图", "使用直尺模拟手持投影试验", previous))

        prompt = llm.await_args.args[0]
        self.assertIn(previous, prompt)
        self.assertIn("不得重复讲解", prompt)

    def test_step_lesson_retries_short_or_placeholder_content(self):
        session = ExplanationSession("lesson-quality", "什么是 PCA？", "student")
        valid_lesson = (
            "### PCA 与降维\n"
            "PCA 的目标是用更少的维度保留数据中最主要的变化。方差衡量数据围绕均值的分散程度，"
            "协方差衡量两个特征是否共同变化。\n\n"
            "### 第一主成分\n"
            "把中心化后的数据投影到一个单位方向上，投影方差越大，该方向保留的变化信息越多。"
            "第一主成分因此被定义为投影方差最大的方向，计算上等于协方差矩阵最大特征值对应的特征向量。"
            "特征值表示该方向保留的方差大小，不能用投影均值来选择方向。"
        )
        responses = [
            "知识主题：确认基础\n知识讲解：[输入框] 请确认是否了解方差。",
            f"知识主题：PCA、第一主成分与选择依据\n知识讲解：{valid_lesson}",
        ]

        with patch.object(session, "call_llm_async", new=AsyncMock(side_effect=responses)) as llm:
            title, lesson = asyncio.run(session.generate_step_lesson_async("简略提纲", "PCA 核心目标"))

        self.assertEqual(2, llm.await_count)
        self.assertEqual("PCA、第一主成分与选择依据", title)
        self.assertEqual(valid_lesson, lesson)

    def test_step_lesson_requires_two_to_four_knowledge_sections(self):
        session = ExplanationSession("lesson-sections", "什么是 PCA？", "student")
        wall_of_text = (
            "PCA 是一种线性降维方法，它把多个相关特征重新组合成少数主成分。方差表示数据围绕均值"
            "的分散程度，协方差表示两个特征共同变化的趋势。第一主成分是使中心化数据投影方差最大"
            "的单位方向，对应协方差矩阵最大特征值的特征向量。特征值越大，该方向保留的变化越多。"
            "例如二维点云沿斜线伸展时，这条伸展方向通常就是第一主成分。不能用投影均值选择主成分。"
        )

        with self.assertRaisesRegex(ValueError, "知识模块"):
            session.parse_step_lesson(f"知识主题：PCA 基础\n知识讲解：{wall_of_text}")

    def test_step_lesson_accepts_detailed_content_below_hard_limit(self):
        session = ExplanationSession("lesson-length", "什么是 PCA？", "student")
        detailed_lesson = "### 核心概念\n" + ("方差表示数据的离散程度。" * 30) + "\n\n### 实际应用\n" + ("投影用于保留主要变化。" * 30)

        _, parsed = session.parse_step_lesson(f"知识主题：PCA 基础\n知识讲解：{detailed_lesson}")

        self.assertEqual(detailed_lesson, parsed)

    def test_step_lesson_second_attempt_accepts_valid_content_over_hard_limit(self):
        session = ExplanationSession("lesson-length-fallback", "什么是 PCA？", "student")
        overlong_lesson = "### 核心概念\n" + ("方差表示数据的离散程度。" * 50) + "\n\n### 实际应用\n" + ("投影用于保留主要变化。" * 50)
        response = f"知识主题：PCA 基础\n知识讲解：{overlong_lesson}"

        with patch.object(session, "call_llm_async", new=AsyncMock(return_value=response)) as llm:
            _, parsed = asyncio.run(session.generate_step_lesson_async("PCA 原理", "解释 PCA"))

        self.assertEqual(2, llm.await_count)
        self.assertEqual(overlong_lesson, parsed)
        self.assertIn("不超过 600 个非空白字符", llm.await_args.args[0])

    def test_step_lesson_normalizes_standalone_bracketed_latex(self):
        session = ExplanationSession("lesson-math", "如何计算方差？", "student")
        lesson = (
            "### 方差的含义\n"
            "方差衡量一组数据围绕均值的离散程度。每个差值先平方，是为了避免正负偏差相互抵消。\n\n"
            "### 样本方差公式\n"
            "[ s^2 = \\frac{1}{n-1} \\sum_{i=1}^{n}(x_i-\\bar{x})^2 ]\n"
            "其中 $x_i$ 是第 $i$ 个观测值，$\\bar{x}$ 是样本均值，$n-1$ 用于修正样本估计偏差。"
        )

        _, parsed = session.parse_step_lesson(f"知识主题：样本方差\n知识讲解：{lesson}")

        self.assertIn("$$s^2 = \\frac{1}{n-1} \\sum_{i=1}^{n}(x_i-\\bar{x})^2$$", parsed)
        self.assertNotIn("[ s^2", parsed)

    def test_correct_answer_feedback_is_generated_instead_of_copying_the_option(self):
        session = self._ready_explanation_session()
        session.steps_data["步骤1"]["具体操作"] = "[输入框] 操作总结：核对答案"
        session.steps_info[0].analysis = "加法表示把两个数量合并，1 与 1 合并后得到 2。"

        with patch.object(session, "correct_selection_analysis", return_value="两个数量合并时，总数等于各部分数量之和；这一关系也能推广到更多加数。"):
            response = asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="A")))

        explanation = response["data"]["answer_explanation"]
        self.assertEqual("两个数量合并时，总数等于各部分数量之和；这一关系也能推广到更多加数。", explanation)
        self.assertNotEqual("正确答案的依据：2", explanation)
        self.assertNotIn("本步知识回顾", explanation)
        self.assertNotIn("1 与 1 合并后得到 2", explanation)
        self.assertNotIn("[输入框]", explanation)

    def test_feedback_parsers_remove_labels_colons_and_meta_wording(self):
        session = ExplanationSession("feedback-format", "什么是 PCA？", "student")

        parsed = session.parse_incorrect_analysis(
            "错误分析提示：\n\n### 错在哪里\n混淆了极差与方差。\n\n### 正确理解\nPCA 选择投影方差最大的方向。"
        )

        self.assertTrue(parsed.startswith("### 错在哪里"))
        self.assertNotIn("错误分析提示", parsed)
        self.assertNotIn("温和反馈", parsed)
        self.assertFalse(parsed.startswith("："))

    def test_incorrect_analysis_accepts_heading_only_response(self):
        session = ExplanationSession("feedback-heading-only", "什么是 PCA？", "student")
        response = (
            "### 错在哪里\n"
            "把特征值误认为相关性强度。\n\n"
            "### 正确理解\n"
            "特征值表示对应主成分方向上的投影方差。"
        )

        self.assertEqual(response, session.parse_incorrect_analysis(response))

    def test_incorrect_feedback_rejects_meta_wording(self):
        session = ExplanationSession("feedback-meta", "什么是 PCA？", "student")

        with self.assertRaisesRegex(ValueError, "元话语"):
            session.parse_incorrect_analysis(
                "错误分析提示：### 错在哪里\n混淆了方差。\n\n### 正确理解\n方差衡量离散程度。\n\n（温和反馈：继续努力。）"
            )

    def test_correct_feedback_retries_when_it_copies_the_option(self):
        session = ExplanationSession("feedback-copy", "什么是 PCA？", "student")
        correct_option = "第一主成分是使投影方差最大的方向。"

        with patch.object(session, "call_llm", side_effect=[
            f"正确理解：{correct_option}",
            "正确理解：方差刻画数据沿某方向的变化量；选择其最大方向，可以在降维时优先保留最显著的结构。",
        ]) as llm:
            feedback = session.correct_selection_analysis("PCA 通过投影实现降维。", correct_option)

        self.assertEqual(2, llm.call_count)
        self.assertNotIn(correct_option, feedback)
        self.assertIn("降维", feedback)
        self.assertNotIn("操作总结", feedback)

    def test_correct_feedback_uses_safe_fallback_after_two_invalid_model_responses(self):
        session = ExplanationSession("feedback-fallback", "什么是聚类？", "student")

        with patch.object(session, "call_llm", side_effect=[
            "模型没有按格式输出。",
            "仍然格式不正确。",
        ]) as llm:
            feedback = session.correct_selection_analysis(
                "聚类依据样本间相似性形成分组。",
                "把相似样本划为同一组。",
            )

        self.assertEqual(2, llm.call_count)
        self.assertEqual("本步的核心原理已经掌握；在新情境中继续核对任务目标与判断依据是否一致。", feedback)

    def test_wrong_explanation_answer_keeps_question_and_returns_feedback(self):
        session = self._ready_explanation_session()

        with patch.object(session, "incorrect_selection_analysis", return_value="应先理解加法含义"):
            response = asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="B")))

        self.assertEqual(1, session.current_step)
        self.assertEqual(1, len(session.steps_info))
        self.assertEqual("B", session.steps_info[0].user_selected_option)
        self.assertFalse(response["data"]["is_correct"])
        self.assertEqual("A", response["data"]["correct_option"])
        self.assertEqual("应先理解加法含义", response["data"]["answer_explanation"])
        self.assertTrue(response["data"]["awaiting_next"])
        self.assertEqual(1, len(session.wrong_answer_list))

    def test_duplicate_explanation_submission_is_idempotent(self):
        session = self._ready_explanation_session()

        first = asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="A")))
        history_size = len(session.history)
        second = asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="A")))

        self.assertEqual(first["data"], second["data"])
        self.assertEqual(history_size, len(session.history))

    def test_explanation_advances_only_after_explicit_request(self):
        session = self._ready_explanation_session()
        asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="A")))

        response = asyncio.run(server_module.advance_explanation(
            server_module.AdvanceExplanationRequest(session_id=session.session_id)
        ))

        self.assertEqual(2, session.current_step)
        self.assertTrue(session.in_summary)
        self.assertFalse(response["data"]["awaiting_next"])

    def test_full_explanation_missing_behavior_warns_and_continues(self):
        session = self._ready_explanation_session(personalized=True, adaptive=True)

        with patch.object(session, "process_behavior_data") as behavior_analysis, \
                patch("KT.ours.annotate.annotate_kc", return_value=[{"id": "kc-1"}]), \
                self.assertLogs("intellitutor", level="WARNING") as logs:
            response = asyncio.run(submit_answer(StepOption(session_id=session.session_id, option="A")))

        self.assertEqual(200, response["code"])
        self.assertFalse(response["data"]["in_summary"])
        self.assertEqual(1, session.current_step)
        self.assertTrue(response["data"]["awaiting_next"])
        behavior_analysis.assert_not_called()
        self.assertEqual(1, len(session.annotation_history))
        self.assertTrue(any("Behavior data missing or empty" in line for line in logs.output))

    def test_full_explanation_analyzes_valid_behavior(self):
        session = self._ready_explanation_session(personalized=True, adaptive=True)
        behavior_data = {
            "session_meta": {
                "user_id": "student",
                "session_id": session.session_id,
                "current_mode": "explanation_mode",
                "current_step_id": "1",
            },
            "event_stream": [{
                "event_type": "step_submit",
                "timestamp": 1,
                "payload": {"submitted_content": "A", "total_time_spent_ms": 1200},
            }],
        }
        behavior_result = {
            "reasoning_process": "normal",
            "correctness_analysis": "valid",
            "cognitive_load_level": "Low",
            "kt_value": "High",
        }

        with patch.object(session, "process_behavior_data", return_value=behavior_result) as behavior_analysis, \
                patch("KT.ours.annotate.annotate_kc", return_value=[]):
            response = asyncio.run(submit_answer(StepOption(
                session_id=session.session_id,
                option="A",
                behavior_data=behavior_data,
            )))

        self.assertEqual(200, response["code"])
        behavior_analysis.assert_called_once()
        self.assertEqual("Low", session.annotation_history[0]["cognitive_load_level"])

    def test_full_explanation_behavior_failure_does_not_block_answer(self):
        session = self._ready_explanation_session(personalized=True, adaptive=True)
        behavior_data = {"event_stream": [{"event_type": "step_submit", "payload": {}}]}

        with patch.object(session, "process_behavior_data", side_effect=RuntimeError("analysis unavailable")), \
                patch("KT.ours.annotate.annotate_kc", return_value=[]), \
                self.assertLogs("intellitutor", level="ERROR") as logs:
            response = asyncio.run(submit_answer(StepOption(
                session_id=session.session_id,
                option="A",
                behavior_data=behavior_data,
            )))

        self.assertEqual(200, response["code"])
        self.assertEqual(1, len(session.annotation_history))
        self.assertTrue(any("Behavior analysis failed" in line for line in logs.output))

    def _ready_discussion_session(self, personalized):
        session = DiscussionSystem("discussion", "q", "student")
        session.personalized_mode = personalized
        session.adaptive_mode = personalized
        session.discussion_plan = create_initial_plan("目标", "步骤1")
        projection = project_discussion_plan(session.discussion_plan)
        session.current_step = projection.current_step
        session.current_step_number = projection.current_step_number
        session.stack = projection.stack
        session.steps = projection.discussion_tree
        return session

    def test_discussion_adaptive_path_does_not_read_behavior_for_basic(self):
        session = self._ready_discussion_session(personalized=False)
        evaluation = AnswerEvaluation(answer_performance="partial", reason="需要继续")
        decision = PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="需要拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "继续分析"},
        )

        with patch.object(session, "process_behavior_data") as behavior_analysis, \
                patch.object(session, "evaluate_answer", return_value=evaluation), \
                patch.object(session, "plan_next_action", return_value=decision):
            response = session.process_user_feedback_personalized("反馈", None)

        behavior_analysis.assert_not_called()
        self.assertEqual("sub_step", response["status"])
        self.assertEqual(1, len(session.annotation_history))

    def test_full_discussion_missing_behavior_warns_and_continues(self):
        session = self._ready_discussion_session(personalized=True)
        evaluation = AnswerEvaluation(answer_performance="partial", reason="需要继续")
        decision = PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="需要拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "继续分析"},
        )

        with patch.object(session, "process_behavior_data") as behavior_analysis, \
                patch.object(session, "evaluate_answer", return_value=evaluation), \
                patch.object(session, "plan_next_action", return_value=decision), \
                patch("discussion.discussionSession.logger.warning") as warning:
            response = session.process_user_feedback_personalized("反馈", None)

        behavior_analysis.assert_not_called()
        warning.assert_called_once()
        self.assertEqual("sub_step", response["status"])

    def test_discussion_summary_preserves_markdown_for_display(self):
        session = DiscussionSystem("discussion-summary", "q", "student")
        markdown = (
            "总结如下：\n\n## 问题总结\n\n"
            "- **问题回顾**：计算 $\\frac{2}{3}+\\frac{1}{4}$。\n\n"
            "- **解题步骤**：\n  ① 通分\n  ② 相加\n\n"
            "- **答案总结**：结果为 $\\frac{11}{12}$。"
        )

        with patch.object(session, "call_llm", return_value=markdown):
            result = session.summary()

        self.assertTrue(result.startswith("## 问题总结"))
        self.assertIn("① 通分", result)
        self.assertIn("$\\frac{11}{12}$", result)
        self.assertEqual(result, session.summary_text)

    def test_legacy_discussion_summary_prefers_original_markdown_from_history(self):
        session = DiscussionSystem("discussion-legacy-history", "q", "student")
        original = "## 问题总结\n\n- **问题回顾**：完整回顾\n\n- **答案总结**：答案为 2"
        session.summary_text = '{"problem_review":"简略回顾","solution_steps":[],"answer_summary":"答案为2"}'
        session.history = [{"role": "assistant", "content": original}]

        first = session.normalize_summary_text()
        second = session.normalize_summary_text()

        self.assertEqual(original, first)
        self.assertEqual(first, second)

    def test_legacy_discussion_summary_json_has_markdown_fallback(self):
        session = DiscussionSystem("discussion-legacy-json", "q", "student")
        session.summary_text = (
            '{"problem_review":"回顾题目","solution_steps":["先计算","再验证"],'
            '"answer_summary":"答案为2"}'
        )

        result = session.normalize_summary_text()

        self.assertEqual(
            "## 问题总结\n\n- **问题回顾**：回顾题目\n\n- **解题步骤**：\n"
            "  1. 先计算\n  2. 再验证\n\n- **答案总结**：答案为2",
            result,
        )


if __name__ == "__main__":
    unittest.main()
