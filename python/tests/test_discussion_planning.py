import unittest
import asyncio
import sys
import types
from unittest.mock import patch

try:
    import fastapi  # noqa: F401
except ModuleNotFoundError:
    class _FastAPI:
        def get(self, *_args, **_kwargs):
            return lambda fn: fn

        post = get
        put = get
        delete = get
        exception_handler = get

        def add_middleware(self, *_args, **_kwargs):
            return None

    class _HTTPException(Exception):
        def __init__(self, status_code, detail):
            super().__init__(detail)
            self.status_code = status_code
            self.detail = detail

    fastapi_stub = types.ModuleType("fastapi")
    fastapi_stub.FastAPI = _FastAPI
    fastapi_stub.HTTPException = _HTTPException
    fastapi_stub.Request = object
    sys.modules["fastapi"] = fastapi_stub
    responses_stub = types.ModuleType("fastapi.responses")
    responses_stub.JSONResponse = dict
    sys.modules["fastapi.responses"] = responses_stub
    handlers_stub = types.ModuleType("fastapi.exception_handlers")
    async def _http_exception_handler(_request, exc):
        return exc
    handlers_stub.http_exception_handler = _http_exception_handler
    sys.modules["fastapi.exception_handlers"] = handlers_stub
    cors_stub = types.ModuleType("fastapi.middleware.cors")
    cors_stub.CORSMiddleware = object
    sys.modules["fastapi.middleware"] = types.ModuleType("fastapi.middleware")
    sys.modules["fastapi.middleware.cors"] = cors_stub

try:
    import loguru  # noqa: F401
except ModuleNotFoundError:
    class _Logger:
        def __getattr__(self, _name):
            return lambda *_args, **_kwargs: None

    loguru_stub = types.ModuleType("loguru")
    loguru_stub.logger = _Logger()
    sys.modules["loguru"] = loguru_stub

try:
    import tools.llm_api_service  # noqa: F401
except ModuleNotFoundError:
    llm_stub = types.ModuleType("tools.llm_api_service")
    llm_stub.call_llm = lambda *_args, **_kwargs: ""
    llm_stub.call_llm_async = lambda *_args, **_kwargs: ""
    sys.modules["tools.llm_api_service"] = llm_stub

if "uvicorn" not in sys.modules:
    try:
        import uvicorn  # noqa: F401
    except ModuleNotFoundError:
        uvicorn_stub = types.ModuleType("uvicorn")
        uvicorn_stub.run = lambda *_args, **_kwargs: None
        sys.modules["uvicorn"] = uvicorn_stub

from discussion.discussionSession import (
    SAFE_FALLBACK_REASON,
    DiscussionSystem,
    extract_objective_options,
)
from discussion.planning import (
    AnswerEvaluation,
    PlanNode,
    PlanningDecision,
    PlanningError,
    apply_decision,
    create_initial_plan,
    learner_questions_are_near_duplicates,
    migrate_legacy_discussion_plan,
    project_discussion_plan,
    validate_plan,
)
import server as server_module
from server import (
    DiscussionFeedbackRequest,
    DiscussionFeedbackResponse,
    DiscussionInitRequest,
    DiscussionUpdateStateRequest,
    MessageData,
    RestoreSessionRequest,
    StepData,
    get_discussion_session_state,
    init_discussion_session,
    session_manager,
    submit_discussion_feedback,
    restore_discussion_session,
    update_discussion_session_state,
)


class DiscussionPlanningModelTests(unittest.TestCase):
    def test_duplicate_check_question_is_rejected_when_explanations_differ(self):
        current = (
            "步骤2：主成分表示数据变化最剧烈的方向；它是协方差矩阵最大特征值对应的特征向量，"
            "用于捕获数据变化最显著的模式。选取多个主成分时，保留的方差比例由对应特征值之和决定。\n\n"
            "在二维散点图中，若数据点沿某条斜线（如 y = x）密集分布，而垂直于该线的方向上分布很窄，"
            "那么‘方差最大方向’最可能指向："
        )
        next_prompt = (
            "步骤3：投影矩阵由若干正交主成分组成，中心化后的样本映射到新坐标系，"
            "能在更少维度中保留主要变化，同时丢弃较小方差方向的信息。\n\n"
            "在二维散点图中，若数据点沿某条斜线（如 y = x）密集分布，而垂直于该线的方向上分布很窄，"
            "那么‘方差最大方向’最可能指向："
        )

        self.assertTrue(learner_questions_are_near_duplicates(current, next_prompt))

    def test_create_initial_plan_has_one_active_root_at_revision_zero(self):
        plan = create_initial_plan("识别题目目标", "题目要求我们求什么？")

        self.assertEqual(1, plan.schema_version)
        self.assertEqual(0, plan.revision)
        self.assertEqual(1, len(plan.nodes))
        self.assertEqual([plan.nodes[0].id], plan.root_node_ids)
        self.assertEqual(plan.nodes[0].id, plan.active_node_id)
        self.assertEqual("active", plan.nodes[0].status)
        self.assertEqual([], plan.turns)
        self.assertEqual("in_progress", plan.completion.status)

    def test_advance_main_completes_active_node_and_adds_one_root(self):
        plan = create_initial_plan("目标一", "问题一")
        decision = PlanningDecision(
            answer_performance="mastered",
            action="advance_main",
            reason="当前目标已掌握",
            next_node={"kind": "main", "objective": "目标二", "prompt": "问题二"},
        )

        result = apply_decision(plan, decision, "回答", ["explicit_feedback", "answer_performance"])

        self.assertEqual(1, result.revision)
        self.assertEqual(2, len(result.root_node_ids))
        self.assertEqual("completed", result.nodes[0].status)
        self.assertEqual("mastered", result.nodes[0].outcome)
        self.assertEqual("active", result.nodes[1].status)
        self.assertEqual(result.nodes[1].id, result.active_node_id)
        self.assertEqual(1, len(result.turns))
        self.assertEqual(0, plan.revision)
        self.assertEqual(1, len(plan.nodes))

    def test_insert_remediation_adds_one_active_child(self):
        plan = create_initial_plan("目标", "问题")
        parent_id = plan.active_node_id
        decision = PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="需要拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "子问题"},
        )

        result = apply_decision(plan, decision, "部分回答", ["explicit_feedback"])

        parent = next(node for node in result.nodes if node.id == parent_id)
        child = next(node for node in result.nodes if node.id == result.active_node_id)
        self.assertEqual("pending", parent.status)
        self.assertEqual([child.id], parent.children_ids)
        self.assertEqual(parent.id, child.parent_id)
        self.assertEqual("active", child.status)

    def test_continue_current_records_turn_without_new_node(self):
        plan = create_initial_plan("目标", "问题")
        decision = PlanningDecision(
            answer_performance="off_topic",
            action="continue_current",
            reason="回答偏题",
        )

        result = apply_decision(plan, decision, "别的问题", ["explicit_feedback"])

        self.assertEqual(1, result.revision)
        self.assertEqual(1, len(result.nodes))
        self.assertEqual(plan.active_node_id, result.active_node_id)
        self.assertEqual("active", result.nodes[0].status)

    def test_complete_discussion_leaves_no_active_node(self):
        plan = create_initial_plan("目标", "问题")
        decision = PlanningDecision(
            answer_performance="mastered",
            action="complete_discussion",
            reason="问题已解决",
        )

        result = apply_decision(plan, decision, "完整回答", ["explicit_feedback"])

        self.assertIsNone(result.active_node_id)
        self.assertEqual("completed", result.completion.status)
        self.assertEqual("completed", result.nodes[0].status)

    def test_advance_main_closes_active_node_and_pending_ancestors(self):
        plan = create_initial_plan("主目标", "主问题")
        plan = apply_decision(plan, PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "子问题"},
        ), "部分回答", ["explicit_feedback"])

        result = apply_decision(plan, PlanningDecision(
            answer_performance="mastered",
            action="advance_main",
            reason="父子目标均已完成",
            next_node={"kind": "main", "objective": "下一目标", "prompt": "下一问题"},
        ), "完整回答", ["explicit_feedback"])

        old_nodes = [node for node in result.nodes if node.id not in result.root_node_ids[1:]]
        self.assertTrue(all(node.status == "completed" for node in old_nodes))
        self.assertTrue(all(node.outcome == "mastered" for node in old_nodes))

    def test_complete_discussion_closes_pending_ancestors(self):
        plan = create_initial_plan("主目标", "主问题")
        plan = apply_decision(plan, PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "子问题"},
        ), "部分回答", ["explicit_feedback"])

        result = apply_decision(plan, PlanningDecision(
            answer_performance="mastered",
            action="complete_discussion",
            reason="全部完成",
        ), "完整回答", ["explicit_feedback"])

        self.assertTrue(all(node.status == "completed" for node in result.nodes))
        self.assertTrue(all(node.outcome == "mastered" for node in result.nodes))

    def test_rejected_transition_does_not_mutate_input(self):
        plan = create_initial_plan("目标", "问题")
        before = plan.model_dump()
        decision = PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="错误类型",
            next_node={"kind": "main", "objective": "子目标", "prompt": "子问题"},
        )

        with self.assertRaises(PlanningError):
            apply_decision(plan, decision, "回答", ["explicit_feedback"])

        self.assertEqual(before, plan.model_dump())

    def test_validate_rejects_duplicate_ids(self):
        plan = create_initial_plan("目标", "问题")
        plan.nodes.append(plan.nodes[0].model_copy(deep=True))

        with self.assertRaises(PlanningError):
            validate_plan(plan)


class DiscussionProjectionTests(unittest.TestCase):
    def _nested_plan(self):
        plan = create_initial_plan("主目标", "主问题")
        plan = apply_decision(plan, PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="拆小",
            next_node={"kind": "remediation", "objective": "子目标一", "prompt": "子问题一"},
        ), "部分回答", ["explicit_feedback"])
        plan = apply_decision(plan, PlanningDecision(
            answer_performance="partial",
            action="insert_probe",
            reason="确认",
            next_node={"kind": "probe", "objective": "孙目标", "prompt": "孙问题"},
        ), "继续回答", ["explicit_feedback"])
        return plan

    def test_projection_uses_stable_hierarchical_ids_and_active_ancestors(self):
        plan = self._nested_plan()

        projection = project_discussion_plan(plan)

        root = projection.discussion_tree[0]
        child = root["subSteps"][0]
        grandchild = child["subSteps"][0]
        self.assertEqual(1, root["id"])
        self.assertEqual("主问题", root["description"])
        self.assertEqual("1-1", child["id"])
        self.assertEqual("子问题一", child["content"])
        self.assertEqual("1-1-1", grandchild["id"])
        self.assertTrue(root["isExpanded"])
        self.assertTrue(child["isExpanded"])
        self.assertTrue(grandchild["isActive"])
        self.assertEqual("1.1.1", projection.current_step_number)
        self.assertEqual("步骤1.1.1：孙问题", projection.current_step)
        self.assertEqual(["1", "1.1", "1.1.1"], [item["step_number"] for item in projection.stack])

    def test_projection_is_deterministic_and_does_not_renumber_existing_nodes(self):
        plan = self._nested_plan()
        first = project_discussion_plan(plan)

        second = project_discussion_plan(plan)

        self.assertEqual(first, second)
        self.assertEqual("1-1", second.discussion_tree[0]["subSteps"][0]["id"])

    def test_projection_keeps_superseded_node_visible_but_inactive(self):
        plan = create_initial_plan("目标", "问题")
        plan.nodes[0].status = "superseded"
        replacement = PlanNode(
            id="replacement",
            parent_id=None,
            sequence=2,
            kind="main",
            objective="替代目标",
            prompt="替代问题",
            status="active",
            created_from_turn=1,
        )
        plan.nodes.append(replacement)
        plan.root_node_ids.append(replacement.id)
        plan.active_node_id = replacement.id

        projection = project_discussion_plan(plan)

        self.assertEqual(2, len(projection.discussion_tree))
        self.assertFalse(projection.discussion_tree[0]["isActive"])
        self.assertTrue(projection.discussion_tree[1]["isActive"])


class DiscussionLegacyMigrationTests(unittest.TestCase):
    def _tree(self):
        return [
            {
                "id": 1,
                "description": "主问题",
                "isActive": False,
                "isExpanded": True,
                "isMain": True,
                "subSteps": [
                    {
                        "id": "1-1",
                        "content": "子问题",
                        "isActive": True,
                        "isExpanded": False,
                        "isMain": False,
                        "subSteps": [],
                    }
                ],
            }
        ]

    def test_migration_prefers_valid_current_step_number(self):
        tree = self._tree()
        tree[0]["isActive"] = True
        snapshot = {"discussion_tree": tree, "current_step_number": "1.1"}

        plan = migrate_legacy_discussion_plan(snapshot)
        projection = project_discussion_plan(plan)

        self.assertEqual("1.1", projection.current_step_number)
        self.assertEqual(0, plan.revision)

    def test_migration_falls_back_to_unique_tree_active_node(self):
        snapshot = {"discussion_tree": self._tree(), "current_step_number": "9"}

        plan = migrate_legacy_discussion_plan(snapshot)

        self.assertEqual("1.1", project_discussion_plan(plan).current_step_number)

    def test_migration_falls_back_to_unique_stack_top(self):
        tree = self._tree()
        tree[0]["subSteps"][0]["isActive"] = False
        snapshot = {
            "discussion_tree": tree,
            "current_step_number": "9",
            "stack": [{"step": "步骤1.1：子问题", "step_number": "1.1"}],
        }

        plan = migrate_legacy_discussion_plan(snapshot)

        self.assertEqual("1.1", project_discussion_plan(plan).current_step_number)

    def test_migration_marks_non_active_nodes_completed_with_skipped_outcome(self):
        plan = migrate_legacy_discussion_plan({
            "discussion_tree": self._tree(),
            "current_step_number": "1.1",
        })

        root = next(node for node in plan.nodes if node.parent_id is None)
        self.assertEqual("completed", root.status)
        self.assertEqual("skipped", root.outcome)

    def test_migration_rejects_ambiguous_active_nodes(self):
        tree = self._tree()
        tree.append({
            "id": 2,
            "description": "另一个问题",
            "isActive": True,
            "isExpanded": False,
            "isMain": True,
            "subSteps": [],
        })

        with self.assertRaises(PlanningError):
            migrate_legacy_discussion_plan({"discussion_tree": tree, "current_step_number": "9"})

    def test_migration_rejects_missing_active_node(self):
        tree = self._tree()
        tree[0]["subSteps"][0]["isActive"] = False

        with self.assertRaises(PlanningError):
            migrate_legacy_discussion_plan({"discussion_tree": tree, "current_step_number": "9"})

    def test_validate_rejects_broken_reference(self):
        plan = create_initial_plan("目标", "问题")
        plan.nodes[0].children_ids = ["missing"]

        with self.assertRaises(PlanningError):
            validate_plan(plan)


class DiscussionDecisionTests(unittest.TestCase):
    def _session(self):
        session = DiscussionSystem("session", "题目", "student")
        session.discussion_plan = create_initial_plan("识别目标", "题目要求什么？")
        return session

    def test_evaluate_answer_parses_strict_json(self):
        session = self._session()

        with patch.object(session, "call_llm", return_value='{"answer_performance":"partial","reason":"少了依据"}'):
            evaluation = session.evaluate_answer("我的回答")

        self.assertEqual("not_mastered", evaluation.answer_performance)
        self.assertEqual("少了依据", evaluation.reason)

    def test_evaluate_answer_treats_partial_as_incorrect(self):
        session = self._session()

        with patch.object(session, "call_llm", return_value='{"answer_performance":"partial","reason":"缺少关键作用"}'):
            evaluation = session.evaluate_answer("我的回答")

        self.assertEqual("not_mastered", evaluation.answer_performance)
        self.assertEqual("缺少关键作用", evaluation.reason)

    def test_plan_allows_the_same_question_template_for_a_new_objective(self):
        session = self._session()
        session.learning_path = [
            {"step_number": 1, "title": "识别目标"},
            {"step_number": 2, "title": "比较方法"},
        ]
        context = {
            "question": session.question,
            "active_objective": "识别目标",
            "active_prompt": "以下哪一项最准确地描述了当前目标？",
            "explicit_feedback": "选项一",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": session.learning_path,
        }
        decision_json = (
            '{"answer_performance":"mastered","action":"advance_main",'
            '"reason":"进入下一目标",'
            '"next_node":{"kind":"main","objective":"比较方法",'
            '"prompt":"以下哪一项最准确地描述了当前目标？",'
            '"options":["选项一","选项二"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[decision_json, decision_json]):
            decision = session.plan_next_action(context)

        self.assertEqual("advance_main", decision.action)
        self.assertEqual("比较方法", decision.next_node.objective)

    def test_mastered_choice_retries_when_planner_keeps_the_current_question(self):
        session = self._session()
        session.learning_path = [
            {"step_number": 1, "title": "识别目标"},
            {"step_number": 2, "title": "比较方法"},
        ]
        context = {
            "question": session.question,
            "active_objective": "识别目标",
            "active_prompt": "题目要求什么？",
            "explicit_feedback": "选项一",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": session.learning_path,
        }
        repeated = (
            '{"answer_performance":"mastered","action":"continue_current",'
            '"reason":"继续当前问题"}'
        )
        advance = (
            '{"answer_performance":"mastered","action":"advance_main",'
            '"reason":"进入下一目标",'
            '"next_node":{"kind":"main","objective":"比较方法",'
            '"prompt":"比较方法时应优先关注什么？",'
            '"options":["任务目标","随机猜测"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[repeated, advance]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("advance_main", decision.action)
        self.assertEqual(2, api.call_count)

    def test_remediation_does_not_create_a_second_remediation_child(self):
        session = self._session()
        session.discussion_plan = apply_decision(
            session.discussion_plan,
            PlanningDecision(
                answer_performance="not_mastered",
                action="insert_remediation",
                reason="补足误解",
                next_node={"kind": "remediation", "objective": "补足概念", "prompt": "子问题"},
            ),
            "错误答案",
            ["answer_performance"],
        )
        context = {
            "question": session.question,
            "active_objective": "补足概念",
            "active_prompt": "子问题",
            "explicit_feedback": "仍不确定",
            "answer_performance": "not_mastered",
            "answer_reason": "需要再解释",
        }
        nested = (
            '{"answer_performance":"not_mastered","action":"insert_remediation",'
            '"reason":"继续拆分",'
            '"next_node":{"kind":"remediation","objective":"更小目标",'
            '"prompt":"更小的问题","options":["选项一","选项二"]}}'
        )
        keep_current = (
            '{"answer_performance":"not_mastered","action":"continue_current",'
            '"reason":"保留本次唯一补救题"}'
        )

        with patch.object(session, "call_llm", side_effect=[nested, keep_current]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("continue_current", decision.action)
        self.assertEqual(2, api.call_count)

    def test_process_feedback_falls_back_after_rejected_duplicate_plan(self):
        session = self._session()
        session.current_options = ["选项一", "选项二"]
        repeated_plan_json = (
            '{"answer_performance":"not_mastered","action":"insert_remediation",'
            '"reason":"补充说明",'
            '"next_node":{"kind":"remediation","objective":"识别目标",'
            '"prompt":"题目要求什么？",'
            '"options":["选项一","选项二"]}}'
        )

        with patch.object(
            session,
            "evaluate_answer",
            return_value=AnswerEvaluation(
                answer_performance="not_mastered",
                reason="需要补充说明",
            ),
        ), patch.object(
            session,
            "call_llm",
            side_effect=[repeated_plan_json, repeated_plan_json],
        ):
            result = session.process_feedback("选项一", submission_type="option")

        self.assertEqual("sub_step", result["status"])
        self.assertEqual(["选项一", "选项二"], session.current_options)
        self.assertEqual("识别目标", session.discussion_plan.nodes[0].objective)
        self.assertEqual(1, len(session.discussion_plan.nodes))
        self.assertIn("回答错误", result["feedback"])

    def test_safe_fallback_strategy_mode_is_consumed_after_one_choice(self):
        session = self._session()
        session.discussion_plan = apply_decision(
            session.discussion_plan,
            PlanningDecision(
                answer_performance="not_mastered",
                action="insert_probe",
                reason=SAFE_FALLBACK_REASON,
                next_node={
                    "kind": "probe",
                    "objective": "识别目标",
                    "prompt": "请选择讲解方式。",
                    "options": ["换一种说明", "举例说明"],
                },
            ),
            "选项一",
            ["answer_performance"],
        )
        self.assertTrue(session._active_node_is_safe_fallback())

        session.discussion_plan = apply_decision(
            session.discussion_plan,
            PlanningDecision(
                answer_performance="needs_support",
                action="continue_current",
                reason="已按选择讲解",
                recovery_prompt="请回答这道客观题。",
                recovery_options=["选项一", "选项二"],
            ),
            "换一种说明",
            ["answer_performance"],
        )

        self.assertFalse(session._active_node_is_safe_fallback())

    def test_objective_options_do_not_include_unknown(self):
        prompt, options = extract_objective_options(
            "问题是什么？\nA. 选项一\nB. 选项二\nC. 不知道"
        )

        self.assertEqual("问题是什么？", prompt)
        self.assertEqual(["选项一", "选项二"], options)

    def test_call_llm_respects_requested_large_model_without_system_prompt(self):
        session = self._session()

        with patch("discussion.discussionSession.call_llm", return_value="响应") as api:
            response = session.call_llm("提示", model_type="large", if_system_prompt=False)

        self.assertEqual("响应", response)
        api.assert_called_once_with("提示", model_type="large")

    def test_discussion_session_has_no_local_ttl_helpers(self):
        session = self._session()
        self.assertFalse(hasattr(session, "call_ttl_llm"))
        self.assertFalse(hasattr(session, "trigger_ttl"))

    def test_needs_support_teaches_in_place_without_creating_a_child(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.learning_path = [{
            "step_number": 1,
            "title": "理解降维目标",
            "knowledge_points": ["降维"],
            "connection": "先理解目标，再学习如何选择主成分。",
            "assessment_focus": "能说明降维要解决的问题",
        }]
        session.discussion_plan = create_initial_plan("理解降维目标", "你以前接触过降维吗？")
        context = {
            "question": session.question,
            "active_objective": "理解降维目标",
            "active_prompt": "你以前接触过降维吗？",
            "explicit_feedback": "完全没学过",
            "answer_performance": "needs_support",
            "answer_reason": "学习者明确表示没有基础",
            "teaching_plan": session.learning_path,
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        teaching = (
            '{"explanation":"PCA先把重复的信息压缩成少数关键方向。",'
            '"check_question":"PCA压缩数据时主要保留什么？",'
            '"options":["关键变化方向","所有重复信息","随机删除样本"]}'
        )

        with patch.object(session, "call_llm", return_value=teaching) as llm:
            decision = session.plan_next_action(context)
        updated = apply_decision(
            session.discussion_plan,
            decision,
            "完全没学过",
            context["signals_used"],
        )

        self.assertEqual("continue_current", decision.action)
        self.assertEqual(1, len(updated.nodes))
        self.assertEqual(session.discussion_plan.active_node_id, updated.active_node_id)
        self.assertIn("PCA先把重复的信息压缩", updated.nodes[0].prompt)
        self.assertNotIn("例如", updated.nodes[0].prompt)
        self.assertIn("不要举例", llm.call_args.args[0])

    def test_repeated_needs_support_allows_one_short_example_in_place(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解降维目标", "PCA会压缩重复信息。清楚吗？")
        session.discussion_plan = apply_decision(
            session.discussion_plan,
            PlanningDecision(
                answer_performance="needs_support",
                action="continue_current",
                reason="直接解释当前知识点",
                recovery_prompt="PCA会压缩重复信息。清楚吗？",
            ),
            "不懂",
            ["explicit_feedback", "answer_performance"],
        )
        context = {
            "question": session.question,
            "active_objective": "理解降维目标",
            "active_prompt": "PCA会压缩重复信息。清楚吗？",
            "explicit_feedback": "还是不理解，请举个例子",
            "answer_performance": "needs_support",
            "answer_reason": "再次请求帮助",
            "teaching_plan": [],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        teaching = (
            '{"explanation":"PCA保留最能代表差异的方向。",'
            '"example":"整理体检指标时，可把多个高度相关指标归纳成一个综合方向。",'
            '"check_question":"这个例子中的综合方向主要保留什么？",'
            '"options":["指标的共同变化","每项指标的名称","随机噪声"]}'
        )

        with patch.object(session, "call_llm", return_value=teaching) as llm:
            decision = session.plan_next_action(context)
        updated = apply_decision(
            session.discussion_plan,
            decision,
            context["explicit_feedback"],
            context["signals_used"],
        )

        self.assertEqual("continue_current", decision.action)
        self.assertEqual(1, len(updated.nodes))
        self.assertIn("体检指标", updated.nodes[0].prompt)
        self.assertIn("最多给一个简短例子", llm.call_args.args[0])

    def test_user_question_gets_a_direct_answer_then_understanding_choices(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解方差", "方差表示什么？")
        original_plan = session.discussion_plan.model_dump()
        session.current_options = ["数据分散程度", "数据平均值", "不知道"]
        teaching = (
            '{"explanation":"方差表示数据围绕平均值的分散程度。",'
            '"check_question":"这部分现在清楚了吗？",'
            '"options":["理解了","还不理解"]}'
        )

        with patch.object(session, "evaluate_answer", side_effect=AssertionError("不应把提问当作答题")), patch.object(
            session, "call_llm", return_value=teaching
        ):
            result = session.process_feedback("方差是什么意思", submission_type="free_text")

        self.assertEqual("sub_step", result["status"])
        self.assertIn("方差表示数据", result["step"])
        self.assertIn("以上关于“理解方差”的解释理解了吗？", result["step"])
        self.assertEqual(["理解了", "还不理解"], session.current_options)
        self.assertEqual(original_plan, session.discussion_plan.model_dump())

        with patch.object(session, "evaluate_answer", side_effect=AssertionError("理解确认不应评估原教学目标")):
            resumed = session.process_feedback("理解了", submission_type="option")

        self.assertEqual("步骤1：方差表示什么？", resumed["step"])
        self.assertEqual(["数据分散程度", "数据平均值"], session.current_options)
        self.assertEqual(original_plan, session.discussion_plan.model_dump())

    def test_free_text_is_treated_as_a_question_without_phrase_matching(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解变量", "解释变量是什么？")
        teaching = (
            '{"explanation":"解释变量是模型中的输入特征。",'
            '"check_question":"这部分清楚了吗？",'
            '"options":["理解了","还不理解"]}'
        )

        with patch.object(session, "evaluate_answer", side_effect=AssertionError("输入框内容不应进入判分")), patch.object(
            session, "call_llm", return_value=teaching
        ):
            result = session.process_feedback(
                "解释变量是模型中的输入特征",
                submission_type="free_text",
            )

        self.assertIn("解释变量是模型中的输入特征", result["step"])
        self.assertIn("解释理解了吗？", result["step"])
        self.assertEqual(["理解了", "还不理解"], session.current_options)

    def test_repeated_not_understood_keeps_the_original_user_question(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解方差", "方差表示什么？")
        teaching = (
            '{"explanation":"方差表示数据围绕平均值的分散程度。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}'
        )

        with patch.object(session, "call_llm", return_value=teaching):
            session.process_feedback("方差是什么意思", submission_type="free_text")
            first_retry = session.process_feedback("还不理解", submission_type="option")
            second_retry = session.process_feedback("还不理解", submission_type="option")

        self.assertEqual("方差是什么意思", session.pending_user_question)
        self.assertIn("以上关于“理解方差”的解释理解了吗？", first_retry["step"])
        self.assertIn("以上关于“理解方差”的解释理解了吗？", second_retry["step"])
        self.assertNotIn("我还不理解", first_retry["step"])
        self.assertNotIn("我还不理解", second_retry["step"])

    def test_not_understood_requests_an_alternate_explanation(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解方差", "方差表示什么？")
        first_teaching = (
            '{"explanation":"方差表示数据围绕平均值的分散程度。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}'
        )
        alternate_teaching = (
            '{"explanation":"把每个人的分数和班级平均分比较，差得越分散，方差越大。",'
            '"example":"例如 60、70、80 比 69、70、71 更分散，所以前者方差更大。",'
            '"check_question":"这个例子清楚了吗？","options":["理解了","还不理解"]}'
        )

        with patch.object(session, "call_llm", side_effect=[first_teaching, alternate_teaching]) as llm:
            session.process_feedback("方差是什么意思", submission_type="free_text")
            retried = session.process_feedback("还不理解", submission_type="option")

        self.assertIn("60、70、80", retried["step"])
        self.assertIn("必须换一种讲法", llm.call_args_list[1].args[0])

    def test_direct_teaching_retries_an_empty_model_response(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解方差", "方差表示什么？")
        teaching = (
            '{"explanation":"方差表示数据围绕平均值的分散程度。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}'
        )

        with patch.object(session, "call_llm", side_effect=[None, teaching]) as llm:
            result = session.process_feedback("方差是什么意思", submission_type="free_text")

        self.assertIn("方差表示数据", result["step"])
        self.assertEqual(2, llm.call_count)

    def test_direct_teaching_accepts_one_json_object_before_trailing_text(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解方差", "方差表示什么？")
        teaching = (
            '{"explanation":"方差表示数据围绕平均值的分散程度。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}'
            '\n补充说明'
        )

        with patch.object(session, "call_llm", return_value=teaching) as llm:
            result = session.process_feedback("方差是什么意思", submission_type="free_text")

        self.assertIn("方差表示数据", result["step"])
        self.assertEqual(1, llm.call_count)

    def test_direct_teaching_accepts_json_in_a_markdown_fence(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解PCA的用途", "PCA主要用于什么？")
        teaching = (
            '```json\n{"explanation":"PCA用于在尽量保留主要信息的同时减少数据特征。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}\n```'
        )

        with patch.object(session, "call_llm", return_value=teaching) as llm:
            result = session.process_feedback("不知道", submission_type="free_text")

        self.assertIn("减少数据特征", result["step"])
        self.assertIn("以上关于“理解PCA的用途”的解释理解了吗？", result["step"])
        self.assertNotIn("关于“不知道”", result["step"])
        self.assertEqual(1, llm.call_count)

    def test_direct_teaching_returns_retryable_message_when_model_stays_invalid(self):
        session = DiscussionSystem("session", "什么是PCA", "student")
        session.discussion_plan = create_initial_plan("理解方差", "方差表示什么？")

        with patch.object(session, "call_llm", return_value=None):
            result = session.process_feedback("方差是什么意思", submission_type="free_text")

        self.assertIn("暂时没能生成可靠的解释", result["step"])
        self.assertEqual(["理解了", "还不理解"], session.current_options)

    def test_planner_fallback_automatically_generates_a_knowledge_question(self):
        session = self._session()
        context = {
            "question": "什么是PCA",
            "active_objective": "协方差矩阵计算",
            "active_prompt": "协方差矩阵在PCA中有什么作用？",
            "completed_summary": [],
            "explicit_feedback": "反映特征之间的共同变化",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"title": "协方差矩阵计算", "step_number": 1},
                {"title": "主成分选择", "step_number": 2},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        recovered = (
            '{"explanation":"主成分按特征值从大到小选择。",'
            '"check_question":"选择主成分时通常优先保留哪一类方向？",'
            '"options":["特征值较大的方向","特征值最小的方向","随机方向"]}'
        )

        with patch.object(session, "call_llm", side_effect=["{", "{", recovered]):
            decision = session.plan_next_action(context)

        self.assertEqual("advance_main", decision.action)
        self.assertEqual("主成分选择", decision.next_node.objective)
        self.assertIn("选择主成分时通常优先保留哪一类方向", decision.next_node.prompt)
        self.assertEqual(
            ["特征值较大的方向", "特征值最小的方向", "随机方向"],
            decision.next_node.options,
        )

    def test_planner_fallback_rejects_recovery_without_objective_options(self):
        session = self._session()
        context = {
            "question": "什么是PCA",
            "active_objective": "协方差矩阵计算",
            "active_prompt": "协方差矩阵在PCA中有什么作用？",
            "completed_summary": [],
            "explicit_feedback": "反映共同变化",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"title": "协方差矩阵计算", "step_number": 1},
                {"title": "主成分选择", "step_number": 2},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        subjective = (
            '{"explanation":"主成分代表重要方向。",'
            '"check_question":"主成分可能代表什么共同特点？"}'
        )

        with patch.object(session, "call_llm", side_effect=["{", "{", subjective]):
            with self.assertRaises(PlanningError):
                session.plan_next_action(context)

    def test_planner_fallback_rejects_invalid_objective_options(self):
        context = {
            "question": "什么是PCA",
            "active_objective": "主成分选择",
            "active_prompt": "主成分如何选择？",
            "completed_summary": [],
            "explicit_feedback": "按特征值排序",
            "answer_performance": "partial",
            "answer_reason": "方向正确",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        invalid_recoveries = (
            '{"explanation":"主成分保留重要方向。","check_question":"应保留什么？","options":["",""]}',
            '{"explanation":"主成分保留重要方向。","check_question":"应保留什么？","options":["重要方向","重要方向"]}',
            '{"explanation":"主成分保留重要方向。","check_question":"应保留什么？","options":["理解了","还不理解"]}',
            '{"explanation":"主成分保留重要方向。","check_question":"应保留什么？","options":["先看一个具体例子","先回顾关键结论"]}',
        )

        for recovery in invalid_recoveries:
            session = self._session()
            with self.subTest(recovery=recovery), patch.object(
                session, "call_llm", side_effect=["{", "{", recovery]
            ):
                with self.assertRaises(PlanningError):
                    session.plan_next_action(context)

    def test_mastered_fallback_with_empty_learning_path_keeps_teaching(self):
        session = self._session()
        context = {
            "question": "什么是PCA",
            "active_objective": "主成分选择",
            "active_prompt": "主成分如何选择？",
            "completed_summary": [],
            "explicit_feedback": "按特征值排序",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        recovery = (
            '{"explanation":"主成分优先保留特征值较大的方向。",'
            '"check_question":"哪个方向通常优先保留？",'
            '"options":["特征值较大的方向","特征值较小的方向","随机方向"]}'
        )

        with patch.object(session, "call_llm", side_effect=["{", "{", recovery]):
            decision = session.plan_next_action(context)

        self.assertEqual("continue_current", decision.action)
        self.assertIn("哪个方向通常优先保留", decision.recovery_prompt)
        self.assertEqual(["特征值较大的方向", "特征值较小的方向", "随机方向"], decision.recovery_options)

    def test_evaluate_answer_rejects_invalid_output(self):
        session = self._session()

        for output in ("", "不是 JSON", '{"answer_performance":"unknown","reason":"x"}'):
            with self.subTest(output=output), patch.object(session, "call_llm", return_value=output):
                with self.assertRaises(PlanningError):
                    session.evaluate_answer("回答")

    def test_evaluate_answer_accepts_json_in_a_markdown_fence(self):
        session = self._session()
        response = (
            '```json\n{"answer_performance":"mastered",'
            '"reason":"回答正确，对角线元素表示各特征自身的方差。"}\n```'
        )

        with patch.object(session, "call_llm", return_value=response):
            evaluation = session.evaluate_answer("各特征自身的方差大小")

        self.assertEqual("mastered", evaluation.answer_performance)
        self.assertIn("回答正确", evaluation.reason)

    def test_evaluate_answer_accepts_a_valid_json_object_before_trailing_text(self):
        session = self._session()
        response = (
            '{"answer_performance":"mastered",'
            '"reason":"正确选择了当前选项。"}后续说明'
        )

        with patch.object(session, "call_llm", return_value=response):
            evaluation = session.evaluate_answer("正确选项")

        self.assertEqual("mastered", evaluation.answer_performance)
        self.assertEqual("正确选择了当前选项。", evaluation.reason)

    def test_evaluate_answer_repairs_fenced_json_missing_opening_brace(self):
        session = self._session()
        response = (
            '```answer_performance":"mastered",'
            '"reason":"正确选择了当前选项。"}'
        )

        with patch.object(session, "call_llm", return_value=response):
            evaluation = session.evaluate_answer("正确选项")

        self.assertEqual("mastered", evaluation.answer_performance)
        self.assertEqual("正确选择了当前选项。", evaluation.reason)

    def test_plan_next_action_accepts_valid_decision(self):
        session = self._session()
        context = {
            "question": "题目",
            "active_objective": "识别目标",
            "active_prompt": "题目要求什么？",
            "completed_summary": [],
            "explicit_feedback": "还差一点",
            "answer_performance": "partial",
            "answer_reason": "少了依据",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        output = (
            '{"answer_performance":"partial","action":"insert_remediation",'
            '"reason":"拆小目标","next_node":{"kind":"remediation",'
            '"objective":"补充依据","prompt":"依据是什么？",'
            '"options":["依据一","依据二","依据三"]}}'
        )

        with patch.object(session, "call_llm", return_value=output):
            decision = session.plan_next_action(context)

        self.assertEqual("insert_remediation", decision.action)
        self.assertEqual("补充依据", decision.next_node.objective)

    def test_plan_next_action_repairs_missing_required_fields(self):
        session = self._session()
        context = {
            "question": "机器学习中的 PCA 是什么",
            "active_objective": "了解降维",
            "active_prompt": "你听说过降维吗？",
            "completed_summary": [],
            "explicit_feedback": "听说过",
            "answer_performance": "partial",
            "answer_reason": "尚未给出具体理解",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        incomplete = (
            '{"action":"insert_probe","next_node":{"kind":"probe",'
            '"objective":"验证理解"},"reason":"继续确认"}'
        )
        repaired = (
            '{"answer_performance":"partial","action":"insert_probe",'
            '"reason":"继续确认","next_node":{"kind":"probe",'
            '"objective":"验证理解","prompt":"如果只保留两个指标，你会优先保留能反映哪类变化的信息？",'
            '"options":["主要变化","随机噪声","重复信息"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[incomplete, repaired]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("insert_probe", decision.action)
        self.assertEqual("验证理解", decision.next_node.objective)
        self.assertEqual(2, api.call_count)

    def test_plan_next_action_uses_safe_fallback_after_repeated_missing_fields(self):
        session = self._session()
        context = {
            "question": "机器学习中的 PCA 是什么",
            "active_objective": "了解降维",
            "active_prompt": "你听说过降维吗？",
            "completed_summary": [],
            "explicit_feedback": "听说过",
            "answer_performance": "partial",
            "answer_reason": "尚未给出具体理解",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        incomplete = (
            '{"action":"insert_probe","next_node":{"kind":"probe",'
            '"objective":"验证理解"},"reason":"继续确认"}'
        )

        recovery = (
            '{"explanation":"降维会保留数据中的主要变化。",'
            '"check_question":"降维时应优先保留哪类信息？",'
            '"options":["主要变化","随机噪声","重复信息"]}'
        )

        with patch.object(session, "call_llm", side_effect=[incomplete, incomplete, recovery]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("partial", decision.answer_performance)
        self.assertEqual("continue_current", decision.action)
        self.assertIn("降维时应优先保留哪类信息", decision.recovery_prompt)
        self.assertNotIn("认知负荷", decision.recovery_prompt)
        self.assertEqual(["主要变化", "随机噪声", "重复信息"], decision.recovery_options)
        self.assertEqual(3, api.call_count)

    def test_plan_next_action_uses_safe_fallback_after_repeated_invalid_json(self):
        session = self._session()
        context = {
            "question": "机器学习中的 PCA 是什么",
            "active_objective": "了解降维",
            "active_prompt": "你接触过数据降维吗？",
            "completed_summary": [],
            "explicit_feedback": "接触过",
            "answer_performance": "partial",
            "answer_reason": "尚未给出具体理解",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        invalid = '```json\n{\n  "answer_performance": "partial",\n  }\n}\n```'

        recovery = (
            '{"explanation":"降维会保留数据中的主要变化。",'
            '"check_question":"降维时应优先保留哪类信息？",'
            '"options":["主要变化","随机噪声","重复信息"]}'
        )

        with patch.object(session, "call_llm", side_effect=[invalid, invalid, recovery]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("partial", decision.answer_performance)
        self.assertEqual("continue_current", decision.action)
        self.assertIn("降维时应优先保留哪类信息", decision.recovery_prompt)
        self.assertEqual(["主要变化", "随机噪声", "重复信息"], decision.recovery_options)
        self.assertEqual(3, api.call_count)

    def test_plan_next_action_rejects_state_fields_and_invalid_shapes(self):
        session = self._session()
        context = {
            "question": "题目",
            "active_objective": "识别目标",
            "active_prompt": "题目要求什么？",
            "completed_summary": [],
            "explicit_feedback": "还差一点",
            "answer_performance": "partial",
            "answer_reason": "少了依据",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        outputs = (
            '{"answer_performance":"partial","action":"insert_remediation","reason":"x"}',
            '{"answer_performance":"off_topic","action":"continue_current","reason":"x",'
            '"next_node":{"kind":"probe","objective":"x","prompt":"x"}}',
            '{"answer_performance":"partial","action":"insert_remediation","reason":"x",'
            '"revision":9,"next_node":{"kind":"remediation","objective":"x","prompt":"x"}}',
        )

        for output in outputs:
            with self.subTest(output=output), patch.object(session, "call_llm", return_value=output):
                with self.assertRaises(PlanningError):
                    session.plan_next_action(context)

    def test_plan_next_action_uses_normal_llm_with_cross_session_history(self):
        context = {
            "question": "题目",
            "active_objective": "识别目标",
            "active_prompt": "题目要求什么？",
            "completed_summary": [],
            "explicit_feedback": "回答",
            "answer_performance": "off_topic",
            "answer_reason": "偏题",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        output = '{"answer_performance":"off_topic","action":"continue_current","reason":"回到目标"}'

        full = self._session()
        full.cross_session_enabled = True
        with patch.object(full, "call_llm", return_value=output) as api:
            full.plan_next_action(context)
        api.assert_called_once()

    def test_initial_step_retries_high_floor_knowledge_request(self):
        session = self._session()
        outputs = [
            "## 初始解题步骤\n步骤1：请列出理解 PCA 所需的三个关键术语。",
            "## 初始解题步骤\n步骤1：你目前对 PCA 的熟悉程度是哪一种？\nA. 完全没学过\nB. 听说过\nC. 能解释基本用途",
        ]

        with patch.object(session, "build_system_prompt"), patch.object(
            session, "call_llm", side_effect=outputs
        ) as api:
            step = session.generate_initial_step("给我讲一下机器学习中的 PCA")

        self.assertEqual(
            "步骤1：你目前对 PCA 的熟悉程度是哪一种？",
            step,
        )
        self.assertEqual(2, api.call_count)

    def test_initial_step_retries_when_it_omits_clickable_choices(self):
        session = self._session()
        outputs = [
            "## 初始解题步骤\n步骤1：一个数据集有 100 个样本，每个样本有 5 个测量值，维度通常指什么？",
            "## 初始解题步骤\n步骤1：一个数据集有 100 个样本，每个样本有 5 个测量值，维度通常指什么？\nA. 样本数量\nB. 每个样本的特征数量\nC. 测量的总次数",
        ]

        with patch.object(session, "build_system_prompt"), patch.object(
            session, "call_llm", side_effect=outputs
        ) as api:
            step = session.generate_initial_step("解释数据维度")

        self.assertIn("维度通常指什么", step)
        self.assertEqual(["样本数量", "每个样本的特征数量", "测量的总次数"], session.current_options)
        self.assertEqual(2, api.call_count)

    def test_initial_step_retries_an_empty_model_response(self):
        session = self._session()
        valid_step = (
            "## 初始解题步骤\n步骤1：PCA主要用于什么？\n"
            "A. 降低特征维度\nB. 增加样本数\nC. 预测标签"
        )

        with patch.object(session, "build_system_prompt"), patch.object(
            session, "call_llm", side_effect=[None, valid_step]
        ) as api:
            step = session.generate_initial_step("什么是PCA")

        self.assertEqual("步骤1：PCA主要用于什么？", step)
        self.assertEqual(["降低特征维度", "增加样本数", "预测标签"], session.current_options)
        self.assertEqual(2, api.call_count)

    def test_needs_support_retries_formula_demand_and_teaches_first(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "理解协方差",
            "active_prompt": "协方差在 PCA 中有什么作用？",
            "completed_summary": [],
            "explicit_feedback": "协方差是什么？我完全不懂",
            "answer_performance": "needs_support",
            "answer_reason": "学习者明确表示不懂",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        teaching = (
            '{"explanation":"协方差描述两个变量是否倾向于一起变化，正值通常表示同向变化，负值通常表示反向变化。",'
            '"check_question":"正协方差通常表示两个变量怎样变化？",'
            '"options":["倾向同向变化","倾向反向变化","彼此完全相同"]}'
        )

        with patch.object(session, "call_llm", return_value=teaching) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("continue_current", decision.action)
        self.assertIn("协方差描述两个变量", decision.recovery_prompt)
        self.assertNotIn("定义式", decision.recovery_prompt)
        self.assertEqual(1, api.call_count)

    def test_needs_support_cannot_repeat_the_same_question(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "理解协方差",
            "active_prompt": "协方差在 PCA 中有什么作用？",
            "completed_summary": [],
            "explicit_feedback": "协方差是什么？",
            "answer_performance": "needs_support",
            "answer_reason": "学习者明确请求解释",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        teaching = (
            '{"explanation":"协方差用来概括两个变量共同变化的方向。",'
            '"check_question":"协方差主要描述什么？",'
            '"options":["两个变量的共同变化","单个变量的名称","样本的排列顺序"]}'
        )

        with patch.object(session, "call_llm", return_value=teaching) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("continue_current", decision.action)
        self.assertNotIn(context["active_prompt"], decision.recovery_prompt)
        self.assertEqual(1, api.call_count)

    def test_direct_teaching_retries_visible_teaching_plan_commentary(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "理解数据标准化",
            "active_prompt": "数据标准化的主要目的是什么？",
            "completed_summary": [],
            "explicit_feedback": "为什么要标准化？",
            "answer_performance": "needs_support",
            "answer_reason": "学习者主动提问",
            "teaching_plan": [{"step_number": 1, "title": "数据标准化"}],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        invalid = (
            '{"explanation":"在教学规划中，标准化用于消除量纲差异。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}'
        )
        valid = (
            '{"explanation":"标准化用于消除不同特征的量纲和数值范围差异。",'
            '"check_question":"理解了吗？","options":["理解了","还不理解"]}'
        )

        with patch.object(session, "call_llm", side_effect=[invalid, valid]) as api:
            decision = session.plan_next_action(context)

        self.assertNotIn("教学规划", decision.recovery_prompt)
        self.assertEqual(2, api.call_count)

    def test_student_prompt_retries_internal_planning_commentary(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "理解方差",
            "active_prompt": "方差表示什么？",
            "completed_summary": [],
            "explicit_feedback": "表示数据分散程度",
            "answer_performance": "partial",
            "answer_reason": "已有直观认识",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        invalid = (
            '{"answer_performance":"partial","action":"insert_probe","reason":"继续确认",'
            '"next_node":{"kind":"probe","objective":"应用方差",'
            '"prompt":"哪个方向保留的信息更多？（根据学习者认知负荷采用最小提示原则）",'
            '"options":["方差较大方向","方差较小方向","随机方向"]}}'
        )
        valid = (
            '{"answer_performance":"partial","action":"insert_probe","reason":"继续确认",'
            '"next_node":{"kind":"probe","objective":"应用方差",'
            '"prompt":"两个投影方向中，哪个方向的数据更分散、保留的变化更多？",'
            '"options":["方差较大方向","方差较小方向","随机方向"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[invalid, valid]) as api:
            decision = session.plan_next_action(context)

        self.assertNotIn("认知负荷", decision.next_node.prompt)
        self.assertEqual(2, api.call_count)

    def test_student_prompt_retries_visible_teaching_plan_commentary(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "理解数据标准化",
            "active_prompt": "数据标准化的主要目的是什么？",
            "completed_summary": [],
            "explicit_feedback": "消除量纲差异",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"step_number": 1, "title": "数据标准化"},
                {"step_number": 2, "title": "协方差矩阵计算"},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        invalid = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"协方差矩阵计算",'
            '"prompt":"在教学规划中，这一步位于标准化之后。协方差矩阵有什么作用？",'
            '"options":["描述特征关系","统一量纲","删除样本","不知道"]}}'
        )
        valid = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"协方差矩阵计算",'
            '"prompt":"协方差矩阵描述特征之间的共同变化。它的主要作用是什么？",'
            '"options":["描述特征关系","统一量纲","删除样本","不知道"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[invalid, valid]) as api:
            decision = session.plan_next_action(context)

        self.assertNotIn("教学规划", decision.next_node.prompt)
        self.assertEqual(2, api.call_count)

    def test_planner_retries_a_near_duplicate_follow_up_question(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "理解协方差矩阵",
            "active_prompt": "协方差矩阵在 PCA 中的主要作用是什么？",
            "completed_summary": [],
            "explicit_feedback": "描述特征间的线性关系和方差分布",
            "answer_performance": "partial",
            "answer_reason": "方向正确但表述不完整",
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        duplicate = (
            '{"answer_performance":"partial","action":"insert_probe","reason":"继续确认",'
            '"next_node":{"kind":"probe","objective":"理解协方差矩阵",'
            '"prompt":"协方差矩阵在 PCA 中的关键作用之一是什么？",'
            '"options":["构造主成分方向","统一量纲","删除异常值","不知道"]}}'
        )
        distinct = (
            '{"answer_performance":"partial","action":"insert_probe","reason":"补足缺失点",'
            '"next_node":{"kind":"probe","objective":"理解协方差矩阵",'
            '"prompt":"协方差矩阵的非对角元素描述什么？",'
            '"options":["两个特征共同变化","单个特征均值","样本数量","不知道"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[duplicate, distinct]) as api:
            decision = session.plan_next_action(context)

        self.assertIn("非对角元素", decision.next_node.prompt)
        self.assertEqual(2, api.call_count)

    def test_advance_main_retries_a_step_that_skips_the_learning_guide(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "协方差矩阵计算",
            "active_prompt": "协方差矩阵如何反映特征关系？",
            "completed_summary": [],
            "explicit_feedback": "对角线是方差，非对角线是协方差",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"step_number": 1, "title": "协方差矩阵计算"},
                {"step_number": 2, "title": "特征值与特征向量分析"},
                {"step_number": 3, "title": "主成分选择与投影"},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        skipped = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"主成分选择与投影",'
            '"prompt":"应如何选择主成分数量？",'
            '"options":["累计解释方差比","随机选择","保留全部","不知道"]}}'
        )
        expected = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"特征值与特征向量分析",'
            '"prompt":"特征值表示对应方向上的方差大小。较大的特征值说明什么？",'
            '"options":["保留更多变化","均值更大","样本更多","不知道"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[skipped, expected]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("特征值与特征向量分析", decision.next_node.objective)
        self.assertEqual(2, api.call_count)

    def test_advance_main_retries_an_understanding_check_disguised_as_a_knowledge_question(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "方差与协方差矩阵",
            "active_prompt": "协方差矩阵的对角线元素表示什么？",
            "completed_summary": [],
            "explicit_feedback": "各特征自身的方差大小",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"step_number": 1, "title": "方差与协方差矩阵"},
                {"step_number": 2, "title": "特征值与特征向量"},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        invalid = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"特征值与特征向量",'
            '"prompt":"特征值越大表示该方向方差越大。是否理解特征值与特征向量在PCA中的意义？",'
            '"options":["理解了，特征值大说明该方向方差大",'
            '"认为特征向量长度决定重要性","认为所有方向应同等对待"]}}'
        )
        valid = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"特征值与特征向量",'
            '"prompt":"特征值可以用来衡量一个方向的信息量。在PCA中，哪个方向应优先保留？",'
            '"options":["特征值较大的方向","特征值较小的方向","任意方向"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[invalid, valid]) as api:
            decision = session.plan_next_action(context)

        self.assertIn("哪个方向应优先保留", decision.next_node.prompt)
        self.assertEqual(["特征值较大的方向", "特征值较小的方向", "任意方向"], decision.next_node.options)
        self.assertEqual(2, api.call_count)

    def test_planner_retries_premature_completion_before_learning_guide_ends(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "协方差矩阵计算",
            "active_prompt": "协方差矩阵如何反映特征关系？",
            "completed_summary": [],
            "explicit_feedback": "对角线是方差，非对角线是协方差",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"step_number": 1, "title": "协方差矩阵计算"},
                {"step_number": 2, "title": "特征值与特征向量分析"},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        premature = '{"answer_performance":"mastered","action":"complete_discussion","reason":"完成"}'
        expected = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"特征值与特征向量分析",'
            '"prompt":"较大的特征值说明什么？",'
            '"options":["保留更多变化","均值更大","样本更多","不知道"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[premature, expected]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("advance_main", decision.action)
        self.assertEqual(2, api.call_count)

    def test_planner_retries_extra_main_step_after_learning_guide_ends(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "协方差矩阵计算",
            "active_prompt": "协方差矩阵如何反映特征关系？",
            "completed_summary": [],
            "explicit_feedback": "对角线是方差，非对角线是协方差",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [{"step_number": 1, "title": "协方差矩阵计算"}],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        extra = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"额外步骤",'
            '"prompt":"再回答一道题？","options":["A","B","C","不知道"]}}'
        )
        complete = '{"answer_performance":"mastered","action":"complete_discussion","reason":"规划已完成"}'

        with patch.object(session, "call_llm", side_effect=[extra, complete]) as api:
            decision = session.plan_next_action(context)

        self.assertEqual("complete_discussion", decision.action)
        self.assertEqual(2, api.call_count)

    def test_advance_main_retries_a_near_duplicate_question(self):
        session = self._session()
        context = {
            "question": "给我讲一下机器学习中的 PCA",
            "active_objective": "协方差矩阵计算",
            "active_prompt": "协方差矩阵在 PCA 中的主要作用是什么？",
            "completed_summary": [],
            "explicit_feedback": "用于寻找主成分方向",
            "answer_performance": "mastered",
            "answer_reason": "回答正确",
            "teaching_plan": [
                {"step_number": 1, "title": "协方差矩阵计算"},
                {"step_number": 2, "title": "特征值与特征向量分析"},
            ],
            "signals_used": ["explicit_feedback", "answer_performance"],
        }
        duplicate = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"特征值与特征向量分析",'
            '"prompt":"协方差矩阵在 PCA 中的关键作用之一是什么？",'
            '"options":["寻找方向","统一量纲","删除样本","不知道"]}}'
        )
        distinct = (
            '{"answer_performance":"mastered","action":"advance_main","reason":"继续",'
            '"next_node":{"kind":"main","objective":"特征值与特征向量分析",'
            '"prompt":"较大的特征值说明对应方向具有什么特点？",'
            '"options":["方差更大","均值更大","样本更多","不知道"]}}'
        )

        with patch.object(session, "call_llm", side_effect=[duplicate, distinct]) as api:
            decision = session.plan_next_action(context)

        self.assertIn("特征值", decision.next_node.prompt)
        self.assertEqual(2, api.call_count)


class DiscussionPlanningContextTests(unittest.TestCase):
    def _session(self, adaptive=False, cross_session=False):
        session = DiscussionSystem("session", "题目", "student")
        session.discussion_plan = create_initial_plan("识别目标", "题目要求什么？")
        session.adaptive_mode = adaptive
        session.cross_session_enabled = cross_session
        session.user_profile_text = "缓存画像"
        session.weak_points = "缓存薄弱点"
        session.related_kps = [{"id": "kp-1", "description": "知识点"}]
        return session

    def test_context_always_contains_explicit_feedback_and_answer_performance(self):
        session = self._session()
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "少了依据"})()

        context = session.build_planning_context("我的回答", evaluation, None)

        self.assertEqual("我的回答", context.explicit_feedback)
        self.assertEqual("partial", context.answer_performance)
        self.assertEqual(["explicit_feedback", "answer_performance"], context.signals_used)

    def test_adaptive_false_never_reads_supplied_behavior_data(self):
        session = self._session(adaptive=False)
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "少了依据"})()
        behavior = {"event_stream": [{"event_type": "step_submit", "payload": {}}]}

        with patch.object(session, "process_behavior_data") as analyze:
            context = session.build_planning_context("回答", evaluation, behavior)

        analyze.assert_not_called()
        self.assertIsNone(context.behavior_summary)
        self.assertNotIn("behavior_data", context.signals_used)

    def test_adaptive_true_includes_successful_behavior_summary(self):
        session = self._session(adaptive=True)
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "少了依据"})()
        behavior = {"event_stream": [{"event_type": "step_submit", "payload": {}}]}

        with patch.object(session, "process_behavior_data", return_value={"cognitive_load_level": "高"}):
            context = session.build_planning_context("回答", evaluation, behavior)

        self.assertEqual({"cognitive_load_level": "高"}, context.behavior_summary)
        self.assertIn("behavior_data", context.signals_used)

    def test_behavior_analysis_is_local_and_does_not_block_on_an_llm(self):
        session = self._session(adaptive=True)
        behavior = {
            "event_stream": [{
                "event_type": "typing_metrics_snapshot",
                "payload": {"time_to_first_keystroke_ms": 45000},
            }]
        }

        with patch("discussion.discussionSession.call_llm", side_effect=AssertionError("不应调用模型")):
            summary = session.process_behavior_data(behavior, "回答")

        self.assertEqual("高", summary["cognitive_load_level"])
        self.assertIn("首次击键时间长", summary["reasoning_process"])

    def test_behavior_failure_is_omitted(self):
        session = self._session(adaptive=True)
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "少了依据"})()
        behavior = {"event_stream": [{"event_type": "step_submit", "payload": {}}]}

        with patch.object(session, "process_behavior_data", side_effect=RuntimeError("failed")):
            context = session.build_planning_context("回答", evaluation, behavior)

        self.assertIsNone(context.behavior_summary)
        self.assertNotIn("behavior_data", context.signals_used)

    def test_cross_session_false_ignores_cached_history(self):
        session = self._session(cross_session=False)
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "少了依据"})()

        with patch.object(session, "build_user_profile") as profile, patch.object(session, "build_weak_points") as weak:
            context = session.build_planning_context("回答", evaluation, None)

        profile.assert_not_called()
        weak.assert_not_called()
        self.assertIsNone(context.cross_session_summary)
        self.assertNotIn("cross_session_profile", context.signals_used)

    def test_cross_session_true_includes_bounded_summary(self):
        session = self._session(cross_session=True)
        session.user_profile_text = ""
        session.weak_points = ""
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "少了依据"})()

        with patch.object(session, "build_user_profile", return_value="画像") as profile, patch.object(
            session, "build_weak_points", return_value="薄弱点"
        ) as weak:
            context = session.build_planning_context("回答", evaluation, None)

        profile.assert_called_once()
        weak.assert_called_once()
        self.assertEqual("画像", context.cross_session_summary["user_profile"])
        self.assertEqual("薄弱点", context.cross_session_summary["weak_points"])
        self.assertIn("cross_session_profile", context.signals_used)


class DiscussionFeedbackPipelineTests(unittest.TestCase):
    def test_feedback_response_keeps_objective_choice_correctness(self):
        response = DiscussionFeedbackResponse.model_validate({
            "status": "main_step",
            "correctness": "true",
        })

        self.assertEqual("true", response.model_dump()["correctness"])

    def _session(self):
        session = DiscussionSystem("session", "题目", "student")
        session.discussion_plan = create_initial_plan("识别目标", "题目要求什么？")
        projection = project_discussion_plan(session.discussion_plan)
        session.steps = projection.discussion_tree
        session.current_step = projection.current_step
        session.current_step_number = projection.current_step_number
        session.stack = projection.stack
        return session

    def _run(self, session, decision):
        evaluation = type("Evaluation", (), {
            "answer_performance": decision.answer_performance,
            "reason": decision.reason,
        })()
        context = type("Context", (), {
            "signals_used": ["explicit_feedback", "answer_performance"],
        })()
        with patch.object(session, "evaluate_answer", return_value=evaluation), patch.object(
            session, "build_planning_context", return_value=context
        ), patch.object(session, "plan_next_action", return_value=decision):
            return session.process_feedback("我的回答")

    def test_continue_current_keeps_active_node_and_returns_sub_step(self):
        session = self._session()
        node_id = session.discussion_plan.active_node_id

        response = self._run(session, PlanningDecision(
            answer_performance="off_topic",
            action="continue_current",
            reason="需要回到目标",
        ))

        self.assertEqual("sub_step", response["status"])
        self.assertEqual(node_id, session.discussion_plan.active_node_id)
        self.assertEqual(1, session.discussion_plan.revision)

    def test_insert_remediation_returns_sub_step_and_updates_projection(self):
        session = self._session()

        response = self._run(session, PlanningDecision(
            answer_performance="not_mastered",
            action="insert_remediation",
            reason="需要拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "先回答子问题"},
        ))

        self.assertEqual("sub_step", response["status"])
        self.assertEqual("1.1", session.current_step_number)
        self.assertEqual("回答错误：需要拆小。", response["feedback"])
        self.assertEqual("步骤1.1：先回答子问题", response["step"])

    def test_advance_main_returns_main_step(self):
        session = self._session()

        response = self._run(session, PlanningDecision(
            answer_performance="mastered",
            action="advance_main",
            reason="当前目标完成",
            next_node={"kind": "main", "objective": "下一目标", "prompt": "继续回答下一问题"},
        ))

        self.assertEqual("main_step", response["status"])
        self.assertEqual("2", session.current_step_number)

    def test_advance_main_keeps_knowledge_choices_for_normal_teaching(self):
        session = self._session()
        decision = PlanningDecision(
            answer_performance="mastered",
            action="advance_main",
            reason="继续下一个知识点",
            next_node={
                "kind": "main",
                "objective": "特征值与特征向量",
                "prompt": (
                    "特征值表示对应方向上的方差大小。"
                    "是否理解特征值与特征向量在PCA中的意义？"
                ),
                "options": [
                    "理解了，特征值大说明该方向方差大",
                    "认为特征向量长度决定重要性",
                    "认为所有方向应同等对待",
                ],
            },
        )

        response = self._run(session, decision)

        self.assertEqual("main_step", response["status"])
        self.assertEqual(
            [
                "理解了，特征值大说明该方向方差大",
                "认为特征向量长度决定重要性",
                "认为所有方向应同等对待",
            ],
            session.current_options,
        )

    def test_visible_step_states_whether_the_answer_was_correct(self):
        session = self._session()

        response = self._run(session, PlanningDecision(
            answer_performance="mastered",
            action="advance_main",
            reason="当前目标完成",
            next_node={"kind": "main", "objective": "下一目标", "prompt": "继续回答下一问题"},
        ))

        self.assertEqual("回答正确。", response["feedback"])
        self.assertNotIn("回答正确", response["step"])
        self.assertIn("继续回答下一问题", response["step"])

    def test_incorrect_step_explains_why_without_partial_label(self):
        session = self._session()

        response = self._run(session, PlanningDecision(
            answer_performance="not_mastered",
            action="insert_remediation",
            reason="只说明了计算动作，没有说明协方差矩阵用于描述特征关系",
            next_node={"kind": "remediation", "objective": "补足作用", "prompt": "协方差矩阵用于描述特征之间的共同变化。"},
        ))

        self.assertIn("回答错误", response["feedback"])
        self.assertIn("没有说明协方差矩阵用于描述特征关系", response["feedback"])
        self.assertNotIn("回答错误", response["step"])
        self.assertNotIn("部分正确", response["step"])

    def test_planning_failure_after_a_correct_remediation_hides_internal_failure(self):
        session = self._session()
        session.discussion_plan = apply_decision(
            session.discussion_plan,
            PlanningDecision(
                answer_performance="not_mastered",
                action="insert_remediation",
                reason="补足误解",
                next_node={"kind": "remediation", "objective": "补足概念", "prompt": "子问题"},
            ),
            "错误答案",
            ["answer_performance"],
        )
        session.current_options = ["正确答案", "错误答案"]
        active_id = session.discussion_plan.active_node_id

        with patch.object(
            session,
            "evaluate_answer",
            return_value=AnswerEvaluation(answer_performance="mastered", reason="回答正确"),
        ), patch.object(session, "plan_next_action", side_effect=PlanningError("model unavailable")):
            result = session.process_feedback("正确答案", submission_type="option")

        self.assertEqual(active_id, session.discussion_plan.active_node_id)
        self.assertEqual(2, len(session.discussion_plan.nodes))
        self.assertEqual(["正确答案", "错误答案"], session.current_options)
        self.assertEqual("回答正确。", result["feedback"])

    def test_correct_answer_uses_the_fixed_next_learning_goal(self):
        session = self._session()
        session.learning_path = [
            {"step_number": 1, "title": "识别目标"},
            {"step_number": 2, "title": "比较方法"},
        ]
        recovery = (
            '{"explanation":"比较方法时先看各自解决的任务。",'
            '"check_question":"比较方法时应先关注什么？",'
            '"options":["任务目标","随机猜测","界面颜色"]}'
        )

        with patch.object(
            session,
            "evaluate_answer",
            return_value=AnswerEvaluation(answer_performance="mastered", reason="回答正确"),
        ), patch.object(session, "call_llm", return_value=recovery) as call_llm:
            result = session.process_feedback("正确答案", submission_type="option")

        self.assertEqual("main_step", result["status"])
        self.assertEqual("2", session.current_step_number)
        self.assertEqual("比较方法", session.discussion_plan.nodes[-1].objective)
        self.assertEqual(1, call_llm.call_count)

    def test_next_learning_goal_retries_when_generated_question_repeats_the_previous_one(self):
        session = self._session()
        session.learning_path = [
            {"step_number": 1, "title": "识别目标"},
            {"step_number": 2, "title": "比较方法"},
        ]
        repeated = (
            '{"explanation":"换一种说法回顾当前内容。",'
            '"check_question":"题目要求什么？",'
            '"options":["目标","方法","结果"]}'
        )
        next_goal = (
            '{"explanation":"比较方法时先看各自解决的任务。",'
            '"check_question":"比较两种方法时应先关注什么？",'
            '"options":["任务目标","界面颜色","随机顺序"]}'
        )

        with patch.object(
            session,
            "evaluate_answer",
            return_value=AnswerEvaluation(answer_performance="mastered", reason="回答正确"),
        ), patch.object(session, "call_llm", side_effect=[repeated, next_goal]) as call_llm:
            result = session.process_feedback("正确答案", submission_type="option")

        self.assertEqual("main_step", result["status"])
        self.assertEqual("比较方法", session.discussion_plan.nodes[-1].objective)
        self.assertIn("比较两种方法时应先关注什么", result["step"])
        self.assertEqual(2, call_llm.call_count)
        self.assertIn("不得重复当前提示中的问题", call_llm.call_args_list[1].args[0])

    def test_repeated_next_questions_are_not_added_to_the_discussion_plan(self):
        session = self._session()
        session.learning_path = [
            {"step_number": 1, "title": "识别目标"},
            {"step_number": 2, "title": "比较方法"},
        ]
        repeated = (
            '{"explanation":"换一种说法回顾当前内容。",'
            '"check_question":"题目要求什么？",'
            '"options":["目标","方法","结果"]}'
        )

        with patch.object(
            session,
            "evaluate_answer",
            return_value=AnswerEvaluation(answer_performance="mastered", reason="回答正确"),
        ), patch.object(session, "call_llm", side_effect=[repeated, repeated]):
            result = session.process_feedback("正确答案", submission_type="option")

        self.assertIn("下一学习内容暂未准备好", result["feedback"])
        self.assertEqual(["重试进入下一步"], session.current_options)
        self.assertEqual(1, len(session.discussion_plan.root_node_ids))

    def test_next_step_failure_can_retry_without_regrading_the_answer(self):
        session = self._session()
        session.learning_path = [
            {"step_number": 1, "title": "识别目标"},
            {"step_number": 2, "title": "比较方法"},
        ]
        recovery = (
            '{"explanation":"比较方法时先看任务目标。",'
            '"check_question":"比较方法时应先关注什么？",'
            '"options":["任务目标","随机猜测","界面颜色"]}'
        )

        with patch.object(
            session,
            "evaluate_answer",
            return_value=AnswerEvaluation(answer_performance="mastered", reason="回答正确"),
        ) as evaluate, patch.object(session, "call_llm", side_effect=[None, None, recovery]):
            failed = session.process_feedback("正确答案", submission_type="option")
            self.assertEqual(["重试进入下一步"], session.current_options)
            retried = session.process_feedback("重试进入下一步", submission_type="option")

        self.assertIn("下一学习内容暂未准备好", failed["feedback"])
        self.assertEqual("main_step", retried["status"])
        self.assertEqual("2", session.current_step_number)
        self.assertEqual(1, evaluate.call_count)

    def test_user_question_uses_a_matching_understanding_check(self):
        session = self._session()
        teaching = (
            '{"explanation":"行表示一个特征，列也表示一个特征。",'
            '"check_question":"协方差矩阵中对角线上的值表示什么？",'
            '"options":["方差","均值","样本数","不知道"]}'
        )

        with patch.object(session, "call_llm", return_value=teaching):
            response = session.process_feedback(
                "协方差矩阵的行列分别是什么？",
                submission_type="free_text",
            )

        self.assertIn("以上关于“识别目标”的解释理解了吗？", response["step"])
        self.assertNotIn("对角线上的值表示什么", response["step"])
        self.assertEqual(["理解了", "还不理解"], session.current_options)

    def test_complete_discussion_generates_summary_and_no_active_step(self):
        session = self._session()

        with patch.object(session, "summary", return_value="## 问题总结\n\n完成"):
            response = self._run(session, PlanningDecision(
                answer_performance="mastered",
                action="complete_discussion",
                reason="问题已解决",
            ))

        self.assertEqual("completed", response["status"])
        self.assertEqual("回答正确。", response["feedback"])
        self.assertEqual("## 问题总结\n\n完成", response["summary"])
        self.assertTrue(session.is_complete)
        self.assertIsNone(session.discussion_plan.active_node_id)

    def test_summary_failure_keeps_completed_transition(self):
        session = self._session()

        with patch.object(session, "summary", side_effect=RuntimeError("summary unavailable")):
            response = self._run(session, PlanningDecision(
                answer_performance="mastered",
                action="complete_discussion",
                reason="问题已解决",
            ))

        self.assertEqual("completed", response["status"])
        self.assertIn("总结生成失败", response["summary"])
        self.assertTrue(session.is_complete)
        self.assertIsNone(session.discussion_plan.active_node_id)

    def test_repeated_schema_failure_revises_current_node_without_growing_depth(self):
        session = self._session()
        evaluation = type("Evaluation", (), {
            "answer_performance": "partial",
            "reason": "尚未形成具体理解",
        })()
        invalid = '```json\n{\n  "answer_performance": "partial",\n  }\n}\n```'
        recovery = (
            '{"explanation":"降维是用更少的指标保留数据中最重要的变化。",'
            '"check_question":"减少重复指标主要保留什么？",'
            '"options":["重要变化","随机噪声","重复信息"]}'
        )

        with patch.object(session, "evaluate_answer", return_value=evaluation), patch.object(
            session, "call_llm", side_effect=[invalid, invalid, recovery, invalid, invalid, recovery]
        ):
            session.process_feedback("接触过")
            active_node_id = session.discussion_plan.active_node_id
            node_count = len(session.discussion_plan.nodes)
            session.process_feedback("不了解")

        self.assertEqual(active_node_id, session.discussion_plan.active_node_id)
        self.assertEqual(node_count, len(session.discussion_plan.nodes))
        self.assertIn("降维是用更少的指标", session.current_step)
        self.assertIn("减少重复指标主要保留什么", session.current_step)
        self.assertEqual(["重要变化", "随机噪声", "重复信息"], session.current_options)

    def test_legacy_strategy_choice_is_recovered_without_answer_grading(self):
        session = self._session()
        session.discussion_plan = apply_decision(
            session.discussion_plan,
            PlanningDecision(
                answer_performance="partial",
                action="insert_probe",
                reason=SAFE_FALLBACK_REASON,
                next_node={
                    "kind": "probe",
                    "objective": "主成分选择",
                    "prompt": "你想先从哪个角度继续？",
                    "options": ["先回顾关键结论", "先看一个具体例子", "先拆成更小的问题"],
                },
            ),
            "已有初步理解",
            ["explicit_feedback"],
        )
        session.current_options = ["先回顾关键结论", "先看一个具体例子", "先拆成更小的问题"]
        recovery = (
            '{"explanation":"主成分会优先保留特征值较大的方向。",'
            '"example":"多个成绩指标可以归纳出共同变化方向。",'
            '"check_question":"选择主成分时通常优先保留哪一类方向？",'
            '"options":["特征值较大的方向","特征值较小的方向","随机方向"]}'
        )

        with patch.object(
            session,
            "evaluate_answer",
            side_effect=AssertionError("策略选项不应进入知识判分"),
        ), patch.object(session, "call_llm", return_value=recovery):
            result = session.process_feedback("先看一个具体例子", submission_type="option")

        self.assertEqual("sub_step", result["status"])
        self.assertIsNone(result.get("feedback"))
        self.assertIn("选择主成分时通常优先保留哪一类方向", result["step"])
        self.assertEqual(["特征值较大的方向", "特征值较小的方向", "随机方向"], session.current_options)

    def test_failed_projection_leaves_all_runtime_state_unchanged(self):
        session = self._session()
        before = {
            "plan": session.discussion_plan.model_dump(),
            "steps": list(session.steps),
            "current_step": session.current_step,
            "current_step_number": session.current_step_number,
        }
        decision = PlanningDecision(
            answer_performance="partial",
            action="insert_remediation",
            reason="需要拆小",
            next_node={"kind": "remediation", "objective": "子目标", "prompt": "子问题"},
        )
        evaluation = type("Evaluation", (), {"answer_performance": "partial", "reason": "需要拆小"})()
        context = type("Context", (), {"signals_used": ["explicit_feedback"]})()

        with patch.object(session, "evaluate_answer", return_value=evaluation), patch.object(
            session, "build_planning_context", return_value=context
        ), patch.object(session, "plan_next_action", return_value=decision), patch(
            "discussion.discussionSession.project_discussion_plan", side_effect=PlanningError("projection failed")
        ):
            with self.assertRaises(PlanningError):
                session.process_feedback("回答")

        self.assertEqual(before["plan"], session.discussion_plan.model_dump())
        self.assertEqual(before["steps"], session.steps)
        self.assertEqual(before["current_step"], session.current_step)
        self.assertEqual(before["current_step_number"], session.current_step_number)

    def test_completed_session_rejects_feedback_without_change(self):
        session = self._session()
        completed = apply_decision(session.discussion_plan, PlanningDecision(
            answer_performance="mastered",
            action="complete_discussion",
            reason="完成",
        ), "回答", ["explicit_feedback"])
        session.discussion_plan = completed
        session.is_complete = True
        before = completed.model_dump()

        with self.assertRaises(PlanningError):
            session.process_feedback("再次回答")

        self.assertEqual(before, session.discussion_plan.model_dump())


class DiscussionPlanningApiTests(unittest.TestCase):
    def tearDown(self):
        session_manager.sessions.clear()
        session_manager.step_generation_locks.clear()

    def _init(self, personalized=False):
        request = DiscussionInitRequest(
            processedQuestion="测试题目",
            personalizedMode=personalized,
            adaptiveMode=False,
            crossSessionEnabled=False,
            persistLearnerState=False,
            userId="student",
        )
        with patch.object(DiscussionSystem, "analyze_question", return_value="分析"), patch.object(
            DiscussionSystem, "generate_initial_step", return_value="步骤1：先识别目标"
        ):
            return asyncio.run(init_discussion_session(request))

    def test_init_creates_authoritative_plan_for_basic_and_personalized_sessions(self):
        for personalized in (False, True):
            with self.subTest(personalized=personalized):
                response = self._init(personalized)
                session = session_manager.get_session(response["data"]["session_id"])

                self.assertIsNotNone(session.discussion_plan)
                self.assertEqual(0, session.discussion_plan.revision)
                projection = project_discussion_plan(session.discussion_plan)
                self.assertEqual(projection.discussion_tree, response["data"]["discussion_tree"])
                self.assertEqual(projection.current_step, session.current_step)
                session_manager.delete_session(session.session_id)

    def test_init_returns_a_student_safe_display_question_while_retaining_model_context(self):
        request = DiscussionInitRequest(
            processedQuestion="内部前测概览：P1:正确。请据此调整讲解细致程度。",
            displayQuestion="本轮学习主题：聚类任务目标",
            personalizedMode=False,
            adaptiveMode=True,
            crossSessionEnabled=False,
            persistLearnerState=False,
            userId="student",
        )
        with patch.object(DiscussionSystem, "analyze_question", return_value="分析"), patch.object(
            DiscussionSystem, "generate_initial_step", return_value="步骤1：先识别目标"
        ):
            response = asyncio.run(init_discussion_session(request))

        session = session_manager.get_session(response["data"]["session_id"])
        state = asyncio.run(get_discussion_session_state(session.session_id))
        self.assertEqual("本轮学习主题：聚类任务目标", state["data"]["question"])
        self.assertEqual("内部前测概览：P1:正确。请据此调整讲解细致程度。", session.question)

    def test_init_exposes_connected_teaching_plan_and_binds_first_objective(self):
        analysis = (
            '{"core_knowledge":['
            '{"id":1,"name":"降维目标","type":"概念类","level":"基础层级",'
            '"content":"用更少特征保留主要信息"},'
            '{"id":2,"name":"主成分选择","type":"方法类","level":"核心方法层级",'
            '"content":"按投影方差选择关键方向"}],'
            '"problem_solving_hint":{"basic_idea":"先目标后方法",'
            '"skill_enlightenment":"先理解降维再选择方向",'
            '"core_mathematical_thought":"比较投影后的信息保留",'
            '"related_method":"主成分选择"}}'
        )
        initial = (
            "## 初始解题步骤\n步骤1：你以前接触过降维吗？"
            "\nA. 完全没学过\nB. 听说过但不了解\nC. 了解基本用途"
        )
        request = DiscussionInitRequest(
            processedQuestion="什么是 PCA",
            personalizedMode=False,
            adaptiveMode=False,
            crossSessionEnabled=False,
            persistLearnerState=False,
            userId="student",
        )

        with patch.object(DiscussionSystem, "call_llm", side_effect=[analysis, initial]):
            response = asyncio.run(init_discussion_session(request))
        session = session_manager.get_session(response["data"]["session_id"])

        self.assertEqual(2, len(response["data"]["learning_path"]))
        self.assertEqual("降维目标", response["data"]["learning_path"][0]["title"])
        self.assertIn("主成分选择", response["data"]["learning_path"][0]["connection"])
        active = next(node for node in session.discussion_plan.nodes if node.status == "active")
        self.assertEqual("降维目标", active.objective)

    def test_init_exposes_objective_choices_separately_from_the_question(self):
        analysis = (
            '{"core_knowledge":['
            '{"id":1,"name":"加法含义","type":"概念类","level":"基础层级",'
            '"content":"把数量合并"},'
            '{"id":2,"name":"加法运算","type":"方法类","level":"核心方法层级",'
            '"content":"求合并后的总数"}],'
            '"problem_solving_hint":{"basic_idea":"理解合并",'
            '"skill_enlightenment":"再进行计算",'
            '"core_mathematical_thought":"数量合并",'
            '"related_method":"加法运算"}}'
        )
        initial = "## 初始解题步骤\n步骤1：1+1等于多少？\n- A. 1\n- B. 2\n- C. 3\n- D. 不知道"
        request = DiscussionInitRequest(
            processedQuestion="学习加法",
            personalizedMode=False,
            adaptiveMode=False,
            crossSessionEnabled=False,
            persistLearnerState=False,
            userId="student",
        )

        with patch.object(DiscussionSystem, "call_llm", side_effect=[analysis, initial]):
            response = asyncio.run(init_discussion_session(request))

        self.assertEqual(["1", "2", "3"], response["data"]["current_options"])
        self.assertNotIn("A. 1", response["data"]["initial_step"])

    def test_session_state_contains_serialized_plan(self):
        response = self._init()
        session_id = response["data"]["session_id"]

        state = asyncio.run(get_discussion_session_state(session_id))

        self.assertEqual(1, state["data"]["discussion_plan"]["schema_version"])
        self.assertEqual(0, state["data"]["discussion_plan"]["revision"])

    def test_feedback_calls_unified_pipeline_without_legacy_dispatch(self):
        response = self._init()
        session = session_manager.get_session(response["data"]["session_id"])
        session.current_options = ["回答"]
        expected = {"status": "sub_step", "step": session.current_step}

        with patch.object(session, "process_feedback", return_value=expected) as unified, patch.object(
            session, "process_user_feedback", side_effect=AssertionError("legacy basic path used")
        ), patch.object(
            session, "process_user_feedback_personalized", side_effect=AssertionError("legacy personalized path used")
        ):
            result = asyncio.run(submit_discussion_feedback(DiscussionFeedbackRequest(
                session_id=session.session_id,
                feedback="回答",
                submission_type="option",
            )))

        unified.assert_called_once_with(
            "回答",
            None,
            submission_type="option",
        )
        self.assertEqual(session.steps, result["data"]["discussion_tree"])

    def test_feedback_rejects_option_not_offered_by_the_server(self):
        response = self._init()
        session = session_manager.get_session(response["data"]["session_id"])
        session.current_options = ["选项一", "选项二"]

        with self.assertRaises(server_module.HTTPException) as raised:
            asyncio.run(submit_discussion_feedback(DiscussionFeedbackRequest(
                session_id=session.session_id,
                feedback="伪造的选项",
                submission_type="option",
            )))

        self.assertEqual(400, raised.exception.status_code)

    def test_failed_feedback_does_not_leave_a_duplicate_user_message(self):
        response = self._init()
        session = session_manager.get_session(response["data"]["session_id"])
        before = list(session.messages_history)

        with patch.object(session, "process_feedback", side_effect=PlanningError("model failed")):
            with self.assertRaises(PlanningError):
                asyncio.run(submit_discussion_feedback(DiscussionFeedbackRequest(
                    session_id=session.session_id,
                    feedback="还不理解",
                    submission_type="free_text",
                )))

        self.assertEqual(before, session.messages_history)

    def test_feedback_and_next_step_are_recorded_as_separate_messages(self):
        response = self._init()
        session = session_manager.get_session(response["data"]["session_id"])
        expected = {
            "status": "main_step",
            "feedback": "回答正确。",
            "step": "步骤2：继续回答下一问题",
        }

        with patch.object(session, "process_feedback", return_value=expected):
            result = asyncio.run(submit_discussion_feedback(DiscussionFeedbackRequest(
                session_id=session.session_id,
                feedback="完整回答",
            )))

        self.assertEqual("回答正确。", result["data"]["feedback"])
        self.assertEqual(
            ["回答正确。", "步骤2：继续回答下一问题"],
            [message["content"] for message in result["data"]["messages_history"][-2:]],
        )

    def test_ui_update_cannot_overwrite_server_planning_fields(self):
        response = self._init()
        session = session_manager.get_session(response["data"]["session_id"])
        before = session.discussion_plan.model_dump()

        result = asyncio.run(update_discussion_session_state(DiscussionUpdateStateRequest(
            session_id=session.session_id,
            question=session.question,
            current_step="步骤99：伪造步骤",
            discussion_tree=[StepData(
                id=99,
                description="伪造步骤",
                isActive=True,
                isExpanded=True,
                isMain=True,
            )],
            messages_history=[MessageData(content="保留消息", type="system", time="2026-09-30T00:00:00")],
            knowledge_points=[],
            is_complete=False,
            summary_text="",
        )))

        projection = project_discussion_plan(session.discussion_plan)
        self.assertEqual(before, session.discussion_plan.model_dump())
        self.assertEqual(projection.current_step, session.current_step)
        self.assertEqual(projection.discussion_tree, session.steps)
        self.assertEqual(before, result["data"]["discussion_plan"])

    def test_completion_writes_learner_state_only_when_persist_is_enabled(self):
        for persist in (False, True):
            with self.subTest(persist=persist):
                response = self._init()
                session = session_manager.get_session(response["data"]["session_id"])
                session.persist_learner_state = persist
                completed = {
                    "status": "completed",
                    "summary": "## 问题总结\n\n完成",
                }

                with patch.object(session, "process_feedback", return_value=completed), patch.object(
                    session, "knowledge_tracing"
                ) as write_state:
                    asyncio.run(submit_discussion_feedback(DiscussionFeedbackRequest(
                        session_id=session.session_id,
                        feedback="完整回答",
                    )))

                self.assertEqual(1 if persist else 0, write_state.call_count)
                session_manager.delete_session(session.session_id)


class DiscussionPlanningRestoreTests(unittest.TestCase):
    def tearDown(self):
        session_manager.sessions.clear()
        session_manager.step_generation_locks.clear()

    def _base_snapshot(self):
        return {
            "question": "测试题目",
            "personalized_mode": False,
            "adaptive_mode": False,
            "cross_session_enabled": False,
            "persist_learner_state": False,
            "current_step": "步骤99：冲突步骤",
            "current_step_number": "99",
            "discussion_tree": [{
                "id": 99,
                "description": "冲突步骤",
                "isActive": True,
                "isExpanded": True,
                "isMain": True,
                "subSteps": [],
            }],
            "messages_history": [],
            "knowledge_points": [],
            "is_complete": False,
            "summary_text": "",
        }

    def test_restore_prefers_valid_plan_over_conflicting_legacy_fields(self):
        plan = create_initial_plan("真实目标", "真实问题")
        snapshot = self._base_snapshot()
        snapshot["discussion_plan"] = plan.model_dump()

        response = asyncio.run(restore_discussion_session(RestoreSessionRequest(
            java_session_id="java-session",
            user_id="student",
            snapshot=snapshot,
        )))
        session = session_manager.get_session(response["data"]["session_id"])

        self.assertEqual(plan.model_dump(), session.discussion_plan.model_dump())
        self.assertEqual("步骤1：真实问题", session.current_step)
        self.assertEqual(1, session.steps[0]["id"])

    def test_restore_preserves_pending_user_question_resume_state(self):
        plan = create_initial_plan("真实目标", "原教学问题？")
        snapshot = self._base_snapshot()
        snapshot.update({
            "discussion_plan": plan.model_dump(),
            "pending_user_question": "术语是什么意思",
            "pending_resume_step": "步骤1：原教学问题？",
            "pending_resume_options": ["答案一", "答案二", "不知道"],
            "current_options": ["理解了", "还不理解"],
        })

        response = asyncio.run(restore_discussion_session(RestoreSessionRequest(
            java_session_id="java-session",
            user_id="student",
            snapshot=snapshot,
        )))
        session = session_manager.get_session(response["data"]["session_id"])

        self.assertEqual("术语是什么意思", session.pending_user_question)
        self.assertEqual("步骤1：原教学问题？", session.pending_resume_step)
        self.assertEqual(["答案一", "答案二"], session.pending_resume_options)
        state = asyncio.run(get_discussion_session_state(session.session_id))
        self.assertNotIn("不知道", state["data"]["current_options"])

        restored_plan = session.discussion_plan.model_dump()
        resumed = session.process_feedback("理解了", submission_type="option")

        self.assertEqual("步骤1：原教学问题？", resumed["step"])
        self.assertEqual(["答案一", "答案二"], session.current_options)
        self.assertEqual(restored_plan, session.discussion_plan.model_dump())

    def test_restore_infers_active_node_for_older_plan_without_active_node_id(self):
        plan = create_initial_plan("旧版目标", "旧版问题")
        saved_plan = plan.model_dump()
        saved_plan.pop("active_node_id")
        snapshot = self._base_snapshot()
        snapshot["discussion_plan"] = saved_plan

        response = asyncio.run(restore_discussion_session(RestoreSessionRequest(
            java_session_id="java-session",
            user_id="student",
            snapshot=snapshot,
        )))
        session = session_manager.get_session(response["data"]["session_id"])

        self.assertEqual(plan.nodes[0].id, session.discussion_plan.active_node_id)
        self.assertEqual("步骤1：旧版问题", session.current_step)

    def test_restore_rejects_unsupported_plan_schema(self):
        snapshot = self._base_snapshot()
        snapshot["discussion_plan"] = {
            "schema_version": 2,
            "revision": 0,
            "active_node_id": None,
            "root_node_ids": [],
            "nodes": [],
            "turns": [],
            "completion": {"status": "completed", "reason": "x"},
        }

        with self.assertRaises(server_module.HTTPException) as raised:
            asyncio.run(restore_discussion_session(RestoreSessionRequest(
                java_session_id="java-session",
                user_id="student",
                snapshot=snapshot,
            )))

        self.assertEqual(400, raised.exception.status_code)

    def test_restore_migrates_legacy_tree_and_exposes_plan(self):
        snapshot = self._base_snapshot()
        snapshot["current_step_number"] = "1"
        snapshot["current_step"] = "步骤1：旧问题"
        snapshot["discussion_tree"] = [{
            "id": 1,
            "description": "旧问题",
            "isActive": True,
            "isExpanded": True,
            "isMain": True,
            "subSteps": [],
        }]

        response = asyncio.run(restore_discussion_session(RestoreSessionRequest(
            java_session_id="java-session",
            user_id="student",
            snapshot=snapshot,
        )))
        session = session_manager.get_session(response["data"]["session_id"])
        state = asyncio.run(get_discussion_session_state(session.session_id))

        self.assertEqual(0, session.discussion_plan.revision)
        self.assertEqual("步骤1：旧问题", session.current_step)
        self.assertEqual(1, state["data"]["discussion_plan"]["schema_version"])

    def test_validate_rejects_cycle(self):
        plan = create_initial_plan("目标", "问题")
        child = PlanNode(
            id="child",
            parent_id=plan.nodes[0].id,
            sequence=1,
            kind="remediation",
            objective="子目标",
            prompt="子问题",
            status="pending",
            children_ids=[plan.nodes[0].id],
            created_from_turn=1,
        )
        plan.nodes[0].parent_id = child.id
        plan.nodes[0].children_ids = [child.id]
        plan.nodes.append(child)

        with self.assertRaises(PlanningError):
            validate_plan(plan)

    def test_validate_rejects_multiple_active_nodes(self):
        plan = create_initial_plan("目标", "问题")
        plan.nodes.append(PlanNode(
            id="second",
            parent_id=None,
            sequence=2,
            kind="main",
            objective="目标二",
            prompt="问题二",
            status="active",
            created_from_turn=0,
        ))
        plan.root_node_ids.append("second")

        with self.assertRaises(PlanningError):
            validate_plan(plan)

    def test_validate_rejects_non_contiguous_sibling_sequence(self):
        plan = create_initial_plan("目标", "问题")
        plan.nodes.append(PlanNode(
            id="second",
            parent_id=None,
            sequence=3,
            kind="main",
            objective="目标二",
            prompt="问题二",
            status="pending",
            created_from_turn=0,
        ))
        plan.root_node_ids.append("second")

        with self.assertRaises(PlanningError):
            validate_plan(plan)

    def test_validate_rejects_completed_node_without_outcome(self):
        plan = create_initial_plan("目标", "问题")
        plan.nodes[0].status = "completed"
        plan.nodes[0].completed_at_turn = 1
        plan.active_node_id = None
        plan.completion.status = "completed"

        with self.assertRaises(PlanningError):
            validate_plan(plan)


if __name__ == "__main__":
    unittest.main()
