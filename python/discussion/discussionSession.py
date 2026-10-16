from __future__ import annotations
from common.state_storage import learner_state_operation
import re
import json
import math
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import List, Dict, Optional, Union, Literal
from datetime import datetime, timedelta
from loguru import logger
from pydantic import ValidationError

from discussion.planning import (
    AnswerEvaluation,
    DiscussionPlan,
    PlanningContext,
    PlanningDecision,
    PlanningError,
    PlanCompletion,
    TeachingRecovery,
    apply_decision,
    learner_prompt_violation,
    learner_questions_are_near_duplicates,
    project_discussion_plan,
)


def extract_objective_options(text: str) -> tuple[str, list[str]]:
    """Split model-written A/B/C choices from learner-facing question text."""
    options: list[str] = []
    kept_lines: list[str] = []
    option_pattern = re.compile(r"^\s*(?:[-*•]\s*)?[A-DＡ-Ｄ][.．、:：)]\s*(.+?)\s*$", re.IGNORECASE)
    correct_option_pattern = re.compile(r"^\s*正确选项\s*[：:]\s*(.+?)\s*$")
    for line in str(text or "").splitlines():
        if correct_option_pattern.match(line):
            continue
        match = option_pattern.match(line)
        if match:
            option = match.group(1).strip()
            if option and option not in {"不知道", "我不知道"} and option not in options:
                options.append(option)
        else:
            kept_lines.append(line)
    return "\n".join(kept_lines).strip(), options


def extract_objective_question(text: str) -> tuple[str, list[str], str]:
    """Read a generated multiple-choice question and its hidden answer marker."""
    prompt, options = extract_objective_options(text)
    match = re.search(r"^\s*正确选项\s*[：:]\s*(.+?)\s*$", str(text or ""), re.MULTILINE)
    correct_option = match.group(1).strip() if match else ""
    letter_prefixed = re.match(r"^[A-DＡ-Ｄ][.．、:：)]\s*(.+?)\s*$", correct_option, re.IGNORECASE)
    if letter_prefixed and letter_prefixed.group(1).strip() in options:
        correct_option = letter_prefixed.group(1).strip()
    if correct_option not in options:
        raise PlanningError("objective question requires a correct option from its choices")
    return prompt, options, correct_option


_NUMBER_PATTERN = r"[-+]?(?:\d+(?:\.\d+)?|\.\d+)"
_EUCLIDEAN_POINTS_PATTERN = re.compile(
    rf"点\s*[（(]\s*({_NUMBER_PATTERN})\s*[,，]\s*({_NUMBER_PATTERN})\s*[)）]"
    rf"\s*(?:和|与)\s*点\s*[（(]\s*({_NUMBER_PATTERN})\s*[,，]\s*({_NUMBER_PATTERN})\s*[)）]"
)
_EUCLIDEAN_VALUE_PATTERN = re.compile(
    rf"欧氏距离\s*(?:是|为|等于)\s*(√\s*{_NUMBER_PATTERN}|{_NUMBER_PATTERN})"
)
_EUCLIDEAN_LABELED_POINTS_PATTERN = re.compile(
    rf"(?:点\s*)?[A-Za-z]\s*=\s*[（(]\s*({_NUMBER_PATTERN})\s*[,，]\s*({_NUMBER_PATTERN})\s*[)）]"
    rf"\s*(?:和|与|、|及)\s*(?:点\s*)?[A-Za-z]\s*=\s*[（(]\s*({_NUMBER_PATTERN})\s*[,，]\s*({_NUMBER_PATTERN})\s*[)）]"
)
_EUCLIDEAN_NUMERIC_OPTION_PATTERN = re.compile(
    rf"^\s*(√\s*(?:{_NUMBER_PATTERN}|\(\s*{_NUMBER_PATTERN}\s*\))|{_NUMBER_PATTERN})\s*$"
)


def _euclidean_distance_answer_violation(option: str) -> str | None:
    """Reject a selected Euclidean-distance claim whose numeric result is false."""
    if "欧氏距离" not in option:
        return None
    points = _EUCLIDEAN_POINTS_PATTERN.search(option)
    stated_value = _EUCLIDEAN_VALUE_PATTERN.search(option)
    if not points or not stated_value:
        return None
    x1, y1, x2, y2 = (float(value) for value in points.groups())
    expected = math.hypot(x2 - x1, y2 - y1)
    value_text = stated_value.group(1).replace(" ", "")
    actual = math.sqrt(float(value_text[1:])) if value_text.startswith("√") else float(value_text)
    if math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-9):
        return None
    return "欧氏距离计算结果与所标记的正确选项不一致"


def _euclidean_question_answer_violation(question: str, options: list[str], correct_option: str) -> str | None:
    """Check a two-point distance question when every choice is a numeric expression."""
    if "欧氏距离" not in question:
        return None
    points = _EUCLIDEAN_LABELED_POINTS_PATTERN.search(question) or _EUCLIDEAN_POINTS_PATTERN.search(question)
    if not points:
        return None
    values = [_EUCLIDEAN_NUMERIC_OPTION_PATTERN.fullmatch(option) for option in options]
    if not all(values):
        return "欧氏距离选项无法全部可靠解析"
    x1, y1, x2, y2 = (float(value) for value in points.groups())
    expected = math.hypot(x2 - x1, y2 - y1)
    require_radical = "保留根号" in question
    correct = []
    for option, match in zip(options, values):
        expression = match.group(1).replace(" ", "")
        if require_radical and not expression.startswith("√"):
            continue
        radicand = expression[1:].strip("()") if expression.startswith("√") else expression
        if expression.startswith("√") and float(radicand) < 0:
            return "欧氏距离选项包含无效根式"
        actual = math.sqrt(float(radicand)) if expression.startswith("√") else float(expression)
        if math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-9):
            correct.append(option)
    if correct != [correct_option]:
        return "欧氏距离题的正确选项与计算结果不一致或不唯一"
    return ""


class ObjectiveQuestionVerification(BaseModel):
    verdict: Literal["valid", "invalid"]
    correct_options: list[str]
    reason: str = Field(min_length=1)


def _without_unknown_option(options: list[str]) -> list[str]:
    return [option for option in options if option.strip() not in {"不知道", "我不知道"}]


_NON_OBJECTIVE_OPTIONS = {
    "理解了",
    "还不理解",
    "不理解",
    "先回顾关键结论",
    "先看一个具体例子",
    "先拆成更小的问题",
    "先了解它解决什么问题",
    "先看一个直观例子",
}


def _normalize_objective_options(options: list[str]) -> list[str]:
    normalized = [str(option).strip() for option in options]
    normalized = [option for option in normalized if option not in {"不知道", "我不知道"}]
    if (
        len(normalized) < 2
        or any(not option for option in normalized)
        or len(set(normalized)) != len(normalized)
        or any(option in _NON_OBJECTIVE_OPTIONS for option in normalized)
    ):
        raise PlanningError("objective question requires distinct knowledge options")
    return normalized


def _strip_json_fence(response) -> str:
    text = str(response or "").strip()
    fenced = re.match(r"^```(?:json)?\s*(.*?)\s*```", text, re.IGNORECASE | re.DOTALL)
    if fenced:
        return fenced.group(1).strip()
    return text


def _parse_teaching_recovery(response) -> TeachingRecovery:
    text = _strip_json_fence(response)
    value, _ = json.JSONDecoder().raw_decode(text)
    return TeachingRecovery.model_validate(value)


SAFE_FALLBACK_REASON = "规划模型连续返回无效结构，使用安全降级步骤"
RECOVERY_REVISE_REASON = "安全降级节点再次规划失败，原位更新教学提示"
RETRY_NEXT_STEP_OPTION = "重试进入下一步"
END_DISCUSSION_AFTER_FAILURE_OPTION = "结束研讨并进入小后测（剩余目标未完成）"

from discussion.prompt.zh.prompt import ANALYZE_PROMPT, INIT_STEP_PROMPT, HANDLE_USER_FEEDBACK, GENERATE_NEXT_SUBSTEP, JUDGE_END_STATUS, HANDLE_PARENT_STEP_STATUS, SUMMARY_PROMPT, SYSTEM_PROMPT, SYSTEM_PROMPT_PERSONALIZED, INSTRUCTION_PROMPT
from common.knowledge_analysis import (
    KnowledgeAnalysisError,
    build_repair_prompt,
    knowledge_dict_from_core,
    legacy_analysis_markdown,
    legacy_knowledge_dict,
    parse_knowledge_analysis,
    salvage_core_knowledge,
)
from common.learning_path import build_discussion_learning_path
from tools.llm_api_service import call_llm, call_llm_async  


def _serialize_llm_content(content):
    if isinstance(content, str):
        return content
    return json.dumps(content, ensure_ascii=False, default=str)


def _log_llm_prompt(label: str, model_type: str, prompt, system_prompt=None, **context):
    context_text = json.dumps(context, ensure_ascii=False, default=str) if context else "{}"
    if system_prompt:
        logger.debug("[LLM System Prompt] label={} model_type={} context={}\n{}", label, model_type, context_text, _serialize_llm_content(system_prompt))
    logger.debug("[LLM Prompt] label={} model_type={} context={}\n{}", label, model_type, context_text, _serialize_llm_content(prompt))


def _log_llm_response(label: str, model_type: str, response, **context):
    context_text = json.dumps(context, ensure_ascii=False, default=str) if context else "{}"
    logger.debug("[LLM Response] label={} model_type={} context={}\n{}", label, model_type, context_text, _serialize_llm_content(response))

class BaseSession:
    def __init__(self, session_id, question):
        self.session_id = session_id
        self.question = question
        self.created_at = datetime.now()
        self.last_accessed = datetime.now()
        self.session_type = ""

class DiscussionSystem(BaseSession):
    def __init__(self, session_id, question, user_id="default"):
        super().__init__(session_id, question)
        self.session_type = "discussion"
        self.user_id = user_id
        self.stack = []  
        self.history = []  
        self.question = question
        self.knowledge_dict = {}
        self.core_knowledge = []
        self.problem_solving_hint = None
        self.learning_path = []
        self.steps = []
        self.messagesHistory = []
        self.current_step = ""
        self.current_step_number = "1"
        self.current_options = []
        self.current_correct_option = ""
        self.pending_user_question = ""
        self.pending_resume_step = ""
        self.pending_resume_options = []
        self.pending_resume_correct_option = ""
        self.pending_advance_feedback = ""
        self.pending_advance_reason = ""
        self.system_prompt = ""
        self.is_complete = False
        self.personalized_mode = False
        self.adaptive_mode = False
        self.cross_session_enabled = False
        self.persist_learner_state = False
        self.experiment_run_id = None
        self.learner_state_scope = None
        self.learner_state_status = "NOT_APPLICABLE"
        self.learner_state_error = ""
        self.summary_text = ""
        self.images = []
        self.imageCode = ""
        self.annotation_history = []  # KT标注历史记录（每轮交互的对错、知识点、梗概）
        self.conversations = []  # 对话与标注结果存档
        self.user_profile_text = ""  # 用户画像文本
        self.weak_points = ""  # 知识薄弱点文本
        self.related_kps = []  # 相关知识点列表（每个知识点包含id、描述等信息）
        self.knowledge_tracing_history = []  # 知识追踪历史记录
        self.discussion_plan: Optional[DiscussionPlan] = None

    def _active_node_is_safe_fallback(self) -> bool:
        if self.discussion_plan is None or self.discussion_plan.active_node_id is None:
            return False
        active = next(
            node for node in self.discussion_plan.nodes
            if node.id == self.discussion_plan.active_node_id
        )
        return (
            active.created_from_turn > 0
            and len(self.discussion_plan.turns) == active.created_from_turn
            and self.discussion_plan.turns[active.created_from_turn - 1].reason
            == SAFE_FALLBACK_REASON
        )

    def evaluate_answer(self, feedback: str) -> AnswerEvaluation:
        if self.discussion_plan is None or self.discussion_plan.active_node_id is None:
            raise PlanningError("discussion plan has no active node")
        active = next(
            node for node in self.discussion_plan.nodes
            if node.id == self.discussion_plan.active_node_id
        )
        if self.current_correct_option:
            if feedback.strip() == self.current_correct_option:
                return AnswerEvaluation(answer_performance="mastered", reason="回答正确")
            return AnswerEvaluation(
                answer_performance="not_mastered",
                reason="所选选项不是本题正确答案。",
            )
        from discussion.prompt.zh.prompt import EVALUATE_DISCUSSION_ANSWER_PROMPT

        response = self.call_llm(EVALUATE_DISCUSSION_ANSWER_PROMPT.format(
            question=self.question,
            objective=active.objective,
            prompt=active.prompt,
            options=json.dumps(self.current_options, ensure_ascii=False),
            feedback=feedback,
        ), model_type="instruct", if_system_prompt=False)
        try:
            evaluation_text = _strip_json_fence(response)
            evaluation_text = re.sub(r"^```(?:json)?\s*", "", evaluation_text, flags=re.IGNORECASE)
            evaluation_text = re.sub(r"\s*```$", "", evaluation_text)
            if re.match(r'^"answer_performance"\s*:', evaluation_text):
                evaluation_text = "{" + evaluation_text
            elif re.match(r'^answer_performance"\s*:', evaluation_text):
                evaluation_text = '{"' + evaluation_text
            evaluation_value, _ = json.JSONDecoder().raw_decode(evaluation_text)
            evaluation = AnswerEvaluation.model_validate(evaluation_value)
            if evaluation.answer_performance == "partial":
                return AnswerEvaluation(
                    answer_performance="not_mastered",
                    reason=evaluation.reason,
                )
            return evaluation
        except (ValidationError, ValueError, TypeError) as exc:
            raise PlanningError("invalid answer evaluation") from exc

    def _verify_generated_objective(
        self,
        question: str,
        options: list[str],
        correct_option: str,
    ) -> str | None:
        """Return a rejection reason unless a generated objective answer key is verified."""
        numeric_violation = _euclidean_question_answer_violation(question, options, correct_option)
        if numeric_violation is not None:
            return numeric_violation or None
        has_parsable_euclidean_claim = (
            "欧氏距离" in correct_option
            and _EUCLIDEAN_POINTS_PATTERN.search(correct_option)
            and _EUCLIDEAN_VALUE_PATTERN.search(correct_option)
        )
        if has_parsable_euclidean_claim:
            return _euclidean_distance_answer_violation(correct_option)

        from discussion.prompt.zh.prompt import VERIFY_DISCUSSION_QUESTION_PROMPT

        candidate = json.dumps(
            {
                "question": question,
                "options": options,
                "correct_option": correct_option,
            },
            ensure_ascii=False,
        )
        response = self.call_llm(
            VERIFY_DISCUSSION_QUESTION_PROMPT.format(candidate=candidate),
            model_type="large",
            if_system_prompt=False,
        )
        try:
            value, _ = json.JSONDecoder().raw_decode(_strip_json_fence(response))
            verification = ObjectiveQuestionVerification.model_validate(value)
        except (ValidationError, ValueError, TypeError, json.JSONDecodeError):
            return "答案校验器没有返回可靠结论"
        if verification.verdict == "valid" and verification.correct_options == [correct_option]:
            return None
        if verification.verdict == "valid":
            return "答案校验器识别的正确选项与标记答案不一致"
        return f"答案校验未通过：{verification.reason}"

    def build_planning_context(
        self,
        feedback: str,
        evaluation: AnswerEvaluation,
        behavior_data=None,
    ) -> PlanningContext:
        if self.discussion_plan is None or self.discussion_plan.active_node_id is None:
            raise PlanningError("discussion plan has no active node")
        active = next(
            node for node in self.discussion_plan.nodes
            if node.id == self.discussion_plan.active_node_id
        )
        signals_used = ["explicit_feedback", "answer_performance"]
        behavior_summary = None
        normalized_behavior = (
            behavior_data.model_dump()
            if hasattr(behavior_data, "model_dump")
            else behavior_data
        )
        event_stream = (
            normalized_behavior.get("event_stream")
            if isinstance(normalized_behavior, dict)
            else None
        )
        if self.adaptive_mode and event_stream:
            try:
                behavior_summary = self.process_behavior_data(normalized_behavior, feedback)
            except Exception:
                logger.exception(
                    "[Behavior] discussion planning analysis failed; continuing without behavior signal "
                    "session_id={} user_id={} step={}",
                    self.session_id,
                    self.user_id,
                    self.current_step_number,
                )
            if behavior_summary:
                signals_used.append("behavior_data")
        elif self.adaptive_mode:
            logger.warning(
                "[Behavior] discussion data missing or empty; continuing without behavior signal "
                "session_id={} user_id={} step={}",
                self.session_id,
                self.user_id,
                self.current_step_number,
            )

        cross_session_summary = None
        if self.cross_session_enabled:
            profile = self.user_profile_text or self.build_user_profile()
            weak_points = self.weak_points or self.build_weak_points()
            cross_session_summary = {
                "user_profile": profile,
                "weak_points": weak_points,
                "related_knowledge_points": list(self.related_kps[-10:]),
            }
            signals_used.append("cross_session_profile")

        completed_summary = [
            {"objective": node.objective, "outcome": node.outcome}
            for node in self.discussion_plan.nodes
            if node.status == "completed"
        ]
        return PlanningContext(
            question=self.question,
            active_objective=active.objective,
            active_prompt=active.prompt,
            completed_summary=completed_summary,
            explicit_feedback=feedback,
            answer_performance=evaluation.answer_performance,
            answer_reason=evaluation.reason,
            teaching_plan=list(self.learning_path),
            behavior_summary=behavior_summary,
            cross_session_summary=cross_session_summary,
            signals_used=signals_used,
        )

    def plan_next_action(self, context: PlanningContext | dict) -> PlanningDecision:
        from discussion.prompt.zh.prompt import (
            PLAN_DISCUSSION_NEXT_ACTION_PROMPT,
            RECOVER_DISCUSSION_TEACHING_PROMPT,
        )

        parsed_context = PlanningContext.model_validate(context)
        if parsed_context.answer_performance == "needs_support":
            return self._teach_current_objective(parsed_context)
        active = next(
            node for node in self.discussion_plan.nodes
            if node.id == self.discussion_plan.active_node_id
        )
        prompt = PLAN_DISCUSSION_NEXT_ACTION_PROMPT.format(
            context=parsed_context.model_dump_json(),
        )
        schema_repair_attempted = False
        violation = None
        for attempt in range(2):
            effective_prompt = prompt
            if attempt:
                effective_prompt += (
                    "\n上一次输出的学生可见 prompt 不合格。请重新输出完整 JSON："
                    f"{violation}。不得重复该问题。"
                )
            response = self.call_llm(
                effective_prompt,
                model_type="large",
                if_system_prompt=False,
            )
            try:
                decision = PlanningDecision.model_validate_json(response or "")
            except ValidationError as exc:
                repairable_schema_error = bool(exc.errors()) and all(
                    error.get("type") in {"missing", "json_invalid"}
                    for error in exc.errors()
                )
                if not repairable_schema_error and not schema_repair_attempted:
                    raise PlanningError("invalid discussion planning decision") from exc
                schema_repair_attempted = True
                violation = "JSON 无法解析或缺少必填字段，必须严格按完整输出结构返回"
                if attempt == 0:
                    continue
                break
            except (ValueError, TypeError) as exc:
                raise PlanningError("invalid discussion planning decision") from exc
            if decision.answer_performance != parsed_context.answer_performance:
                raise PlanningError("planning decision changed the answer evaluation")
            violation = None
            if (
                parsed_context.answer_performance == "needs_support"
                and decision.action != "insert_remediation"
            ):
                violation = "学习者明确求助后必须创建先解释再提问的补救节点"
            elif (
                parsed_context.answer_performance == "mastered"
                and decision.action not in {"advance_main", "complete_discussion"}
            ):
                violation = "当前客观题已答对，必须推进下一主步骤或完成研讨，不得重复当前题"
            elif (
                active.parent_id is not None
                and decision.action in {"insert_remediation", "insert_probe"}
            ):
                violation = "补救步骤不得继续插入子步骤，应原位复问或在答对后回到主教学流程"
            elif (
                parsed_context.teaching_plan
                and decision.action == "complete_discussion"
                and len(self.discussion_plan.root_node_ids) < len(parsed_context.teaching_plan)
            ):
                violation = "学习指引仍有未完成的主步骤，不得提前结束"
            elif (
                parsed_context.teaching_plan
                and decision.action == "advance_main"
                and len(self.discussion_plan.root_node_ids) >= len(parsed_context.teaching_plan)
            ):
                violation = "学习指引已经完成，不得创建额外主步骤"
            elif (
                decision.action == "advance_main"
                and decision.next_node is not None
                and parsed_context.teaching_plan
                and len(self.discussion_plan.root_node_ids) < len(parsed_context.teaching_plan)
                and decision.next_node.objective.strip()
                != str(parsed_context.teaching_plan[len(self.discussion_plan.root_node_ids)].get("title", "")).strip()
            ):
                expected = parsed_context.teaching_plan[len(self.discussion_plan.root_node_ids)].get("title", "")
                violation = f"下一主步骤必须是学习指引中的“{expected}”，不得跳步"
            elif (
                decision.next_node is not None
                and (
                    decision.next_node.objective.strip()
                    == parsed_context.active_objective.strip()
                    or any(
                        parsed_context.active_objective.strip()[index:index + 3]
                        in decision.next_node.prompt
                        for index in range(
                            max(0, len(parsed_context.active_objective.strip()) - 2)
                        )
                    )
                )
                and learner_questions_are_near_duplicates(
                    parsed_context.active_prompt,
                    decision.next_node.prompt,
                )
            ):
                violation = "新问题只是当前问题的近似改写，必须补充明确缺失点"
            elif (
                decision.action == "advance_main"
                and decision.next_node is not None
                and (
                    re.search(
                        r"(?:是否|有没有|能否).{0,16}(?:理解|清楚|明白)",
                        decision.next_node.prompt,
                    )
                    or any(
                        option.strip().startswith(("理解了", "还不理解", "不理解"))
                        for option in decision.next_node.options
                    )
                )
            ):
                violation = "正常教学步骤必须生成一道不泄露答案的知识选择题，不得改成理解与否确认"
            elif decision.next_node is not None:
                try:
                    _normalize_objective_options(list(decision.next_node.options))
                except PlanningError:
                    violation = "正常教学步骤必须提供至少两个非空、互异的知识题选项"
            if violation is None and decision.next_node is not None:
                violation = self._verify_generated_objective(
                    decision.next_node.prompt,
                    list(decision.next_node.options),
                    decision.next_node.correct_option,
                )
            if violation is None and decision.next_node is not None:
                violation = learner_prompt_violation(
                    decision.next_node.prompt,
                    stage="follow_up",
                    answer_performance=parsed_context.answer_performance,
                )
            if violation is None:
                return decision
        if schema_repair_attempted:
            next_index = len(self.discussion_plan.root_node_ids)
            if parsed_context.answer_performance == "mastered":
                if parsed_context.teaching_plan and next_index >= len(parsed_context.teaching_plan):
                    return PlanningDecision(
                        answer_performance="mastered",
                        action="complete_discussion",
                        reason=SAFE_FALLBACK_REASON,
                    )
                if parsed_context.teaching_plan:
                    target_objective = str(
                        parsed_context.teaching_plan[next_index].get("title", "")
                    ).strip()
                    action = "advance_main"
                else:
                    target_objective = parsed_context.active_objective
                    action = "continue_current"
            else:
                target_objective = parsed_context.active_objective
                action = "continue_current"

            recovery_response = self.call_llm(
                RECOVER_DISCUSSION_TEACHING_PROMPT.format(
                    question=parsed_context.question,
                    objective=target_objective,
                    current_prompt=parsed_context.active_prompt,
                    feedback=parsed_context.explicit_feedback,
                ),
                model_type="large",
                if_system_prompt=False,
            )
            try:
                recovery = _parse_teaching_recovery(recovery_response)
            except (ValidationError, ValueError, TypeError) as exc:
                raise PlanningError("invalid discussion teaching recovery") from exc
            recovery_prompt = (
                f"{recovery.explanation.strip()}\n\n{recovery.check_question.strip()}"
            )
            violation = learner_prompt_violation(
                recovery_prompt,
                stage="follow_up",
                answer_performance=parsed_context.answer_performance,
            )
            if violation is not None:
                raise PlanningError(
                    f"unsafe discussion teaching recovery: {violation}"
                )
            recovery_options = _normalize_objective_options(list(recovery.options))
            answer_key_violation = self._verify_generated_objective(
                recovery_prompt,
                recovery_options,
                recovery.correct_option.strip(),
            )
            if answer_key_violation is not None:
                raise PlanningError(
                    f"unsafe discussion teaching recovery: {answer_key_violation}"
                )
            if action == "advance_main":
                return PlanningDecision(
                    answer_performance="mastered",
                    action="advance_main",
                    reason=SAFE_FALLBACK_REASON,
                    next_node={
                        "kind": "main",
                        "objective": target_objective,
                        "prompt": recovery_prompt,
                        "options": recovery_options,
                        "correct_option": recovery.correct_option.strip(),
                    },
                )
            return PlanningDecision(
                answer_performance=parsed_context.answer_performance,
                action="continue_current",
                reason=RECOVERY_REVISE_REASON,
                recovery_prompt=recovery_prompt,
                recovery_options=recovery_options,
                recovery_correct_option=recovery.correct_option.strip(),
            )
        raise PlanningError(f"unsafe learner-facing discussion prompt: {violation}")

    def _advance_after_mastered(self, context: PlanningContext) -> PlanningDecision:
        from discussion.prompt.zh.prompt import RECOVER_DISCUSSION_TEACHING_PROMPT

        next_index = len(self.discussion_plan.root_node_ids)
        if next_index >= len(context.teaching_plan):
            return PlanningDecision(
                answer_performance="mastered",
                action="complete_discussion",
                reason="既定教学目标已完成",
            )

        target_objective = str(context.teaching_plan[next_index].get("title", "")).strip()
        last_error = None
        for attempt in range(2):
            recovery_prompt_request = RECOVER_DISCUSSION_TEACHING_PROMPT.format(
                question=context.question,
                objective=target_objective,
                current_prompt=context.active_prompt,
                feedback=context.explicit_feedback,
            )
            recovery_prompt_request += (
                "\n上一题已确认的答案："
                f"{context.explicit_feedback}。下一题必须考查当前目标的新内容，"
                "不得重复上一题的考查问题；可以把同一概念作为干扰选项。"
            )
            if attempt:
                recovery_prompt_request += (
                    f"\n上一份输出未通过校验，原因：{last_error}。"
                    "请针对该原因修正，严格围绕当前目标生成新的讲解与检查题，"
                    "不得重复当前提示中的问题或仅做同义改写。"
                )
            response = self.call_llm(
                recovery_prompt_request,
                model_type="large",
                if_system_prompt=False,
            )
            try:
                recovery = _parse_teaching_recovery(response)
                recovery_options = _normalize_objective_options(list(recovery.options))
                recovery_prompt = (
                    f"{recovery.explanation.strip()}\n\n{recovery.check_question.strip()}"
                )
                violation = learner_prompt_violation(
                    recovery_prompt,
                    stage="follow_up",
                    answer_performance="mastered",
                )
                if violation is not None:
                    raise PlanningError(f"unsafe next discussion teaching step: {violation}")
                if learner_questions_are_near_duplicates(
                    context.active_prompt,
                    recovery_prompt,
                    current_objective=context.active_objective,
                    next_objective=target_objective,
                ):
                    raise PlanningError("next discussion question repeats the current question")
                answer_key_violation = self._verify_generated_objective(
                    recovery_prompt,
                    recovery_options,
                    recovery.correct_option.strip(),
                )
                if answer_key_violation is not None:
                    raise PlanningError(answer_key_violation)
            except (ValidationError, ValueError, TypeError, PlanningError) as exc:
                last_error = exc
                logger.warning(
                    "discussion next step rejected session_id={} objective={} attempt={} reason={}",
                    self.session_id, target_objective, attempt + 1, exc,
                )
                continue
            return PlanningDecision(
                answer_performance="mastered",
                action="advance_main",
                reason="当前目标已掌握，进入既定下一主目标",
                next_node={
                    "kind": "main",
                    "objective": target_objective,
                    "prompt": recovery_prompt,
                    "options": recovery_options,
                    "correct_option": recovery.correct_option.strip(),
                },
            )
        raise PlanningError("invalid next discussion teaching step") from last_error

    def _planning_failure_fallback(self, context: PlanningContext) -> PlanningDecision:
        """Do not mutate the teaching tree when the next planning decision is unavailable."""
        return PlanningDecision(
            answer_performance=context.answer_performance,
            action="continue_current",
            reason="暂时无法进入下一步，保留当前题目供稍后重试",
            recovery_options=list(self.current_options),
        )

    def _teach_current_objective(
        self,
        context: PlanningContext,
        *,
        understanding_check: bool = False,
        alternate_explanation: bool = False,
    ) -> PlanningDecision:
        from discussion.prompt.zh.prompt import TEACH_CURRENT_OBJECTIVE_PROMPT

        prior_support_turns = sum(
            1
            for turn in self.discussion_plan.turns
            if turn.node_id == self.discussion_plan.active_node_id
            and turn.answer_performance == "needs_support"
        )
        requests_example = any(
            marker in context.explicit_feedback
            for marker in ("举例", "例子", "实例", "示例")
        )
        allow_example = requests_example or prior_support_turns > 0 or alternate_explanation
        example_rule = (
            "这是学习者第二次仍未理解：必须换一种讲法，使用一个具体例子或日常类比，"
            "不要改写或重复上一轮解释。"
            if alternate_explanation
            else
            "允许最多给一个简短例子，例子后立刻回到当前知识点。"
            if allow_example
            else "不要举例，只用更直接的语言讲清当前知识点。"
        )
        prompt = TEACH_CURRENT_OBJECTIVE_PROMPT.format(
            question=context.question,
            objective=context.active_objective,
            current_prompt=context.active_prompt,
            feedback=context.explicit_feedback,
            teaching_plan=json.dumps(context.teaching_plan, ensure_ascii=False),
            example_rule=example_rule,
        )
        violation = None
        teaching = None
        objective_options = None
        for attempt in range(2):
            effective_prompt = prompt
            if attempt:
                effective_prompt += f"\n上一次学生可见讲解不合格：{violation}。请重新输出完整 JSON。"
            response = self.call_llm(
                effective_prompt,
                model_type="instruct",
                if_system_prompt=False,
            )
            try:
                teaching = _parse_teaching_recovery(response)
            except (ValidationError, ValueError, TypeError, json.JSONDecodeError):
                violation = "模型没有返回合法的教学 JSON"
                continue
            parts = [teaching.explanation.strip()]
            if allow_example and teaching.example and teaching.example.strip():
                parts.append(teaching.example.strip())
            if understanding_check:
                topic = context.active_objective.strip() or re.sub(
                    r"[?？]+$", "", context.active_prompt.strip()
                )
                parts.append(f"以上关于“{topic}”的解释理解了吗？")
            else:
                try:
                    objective_options = _normalize_objective_options(list(teaching.options))
                except PlanningError:
                    violation = "知识检查必须提供非空、互异的客观选项"
                    continue
                parts.append(teaching.check_question.strip())
            learner_prompt = "\n\n".join(parts)
            if not understanding_check:
                violation = self._verify_generated_objective(
                    learner_prompt,
                    objective_options,
                    teaching.correct_option.strip(),
                )
                if violation is not None:
                    continue
            violation = learner_prompt_violation(
                learner_prompt,
                stage="follow_up",
                answer_performance="needs_support",
            )
            if violation is None:
                break
        if teaching is None or violation is not None:
            topic = context.active_objective.strip() or re.sub(
                r"[?？]+$", "", context.active_prompt.strip()
            )
            learner_prompt = (
                "这次暂时没能生成可靠的解释，请稍后再点“还不理解”重试。"
                + (f"\n\n以上关于“{topic}”的解释理解了吗？" if understanding_check else "")
            )
            return PlanningDecision(
                answer_performance="needs_support",
                action="continue_current",
                reason="教学模型连续返回无效结构，保留当前步骤供重试",
                recovery_prompt=learner_prompt,
                recovery_options=["理解了", "还不理解"] if understanding_check else [],
            )
        return PlanningDecision(
            answer_performance="needs_support",
            action="continue_current",
            reason="学习者明确求助，原位直接讲解当前知识点",
            recovery_prompt=learner_prompt,
            recovery_options=(
                ["理解了", "还不理解"]
                if understanding_check
                else objective_options
            ),
            recovery_correct_option=(
                None
                if understanding_check
                else teaching.correct_option.strip()
            ),
        )

    def _add_visible_answer_feedback(
        self,
        decision: PlanningDecision,
        evaluation_reason: str,
    ) -> Optional[str]:
        return {
            "mastered": "回答正确。",
            "not_mastered": f"回答错误：{evaluation_reason.rstrip('。')}。",
            "off_topic": "这次回答没有对应当前问题。",
        }.get(decision.answer_performance)

    def _answer_user_question(self, user_question: str, behavior_data=None) -> dict:
        projection = project_discussion_plan(self.discussion_plan)
        alternate_explanation = bool(self.pending_user_question)
        if not alternate_explanation:
            self.pending_resume_step = projection.current_step
            self.pending_resume_options = _without_unknown_option(list(self.current_options))
            self.pending_resume_correct_option = self.current_correct_option
        self.pending_user_question = user_question
        evaluation = AnswerEvaluation(
            answer_performance="needs_support",
            reason="学习者主动提出问题",
        )
        context = self.build_planning_context(user_question, evaluation, behavior_data)
        decision = self._teach_current_objective(
            context,
            understanding_check=True,
            alternate_explanation=alternate_explanation,
        )
        self.current_options = ["理解了", "还不理解"]
        self.current_correct_option = ""
        step = f"步骤{projection.current_step_number}：{decision.recovery_prompt}"
        self.history.append({"role": "user", "content": user_question})
        self.history.append({"role": "assistant", "content": step})
        return {"status": "sub_step", "step": step}

    def _resume_after_user_question(self) -> dict:
        projection = project_discussion_plan(self.discussion_plan)
        step = self.pending_resume_step or projection.current_step
        self.current_options = _without_unknown_option(list(self.pending_resume_options))
        self.current_correct_option = self.pending_resume_correct_option
        self.pending_user_question = ""
        self.pending_resume_step = ""
        self.pending_resume_options = []
        self.pending_resume_correct_option = ""
        self.history.append({"role": "user", "content": "理解了"})
        self.history.append({"role": "assistant", "content": step})
        return {"status": "sub_step", "step": step}

    def process_feedback(
        self,
        user_feedback: str,
        behavior_data=None,
        *,
        submission_type: str = "option",
    ) -> dict:
        if self.is_complete:
            raise PlanningError("discussion is already complete")
        if self.discussion_plan is None:
            raise PlanningError("discussion plan is not initialized")

        if submission_type not in {"option", "free_text"}:
            raise PlanningError("unsupported discussion submission type")
        retrying_advance = (
            submission_type == "option"
            and user_feedback.strip() == RETRY_NEXT_STEP_OPTION
            and bool(self.pending_advance_feedback)
        )
        if (
            submission_type == "option"
            and user_feedback.strip() == END_DISCUSSION_AFTER_FAILURE_OPTION
            and self.pending_advance_feedback
        ):
            plan = self.discussion_plan.model_copy(deep=True)
            node_map = {node.id: node for node in plan.nodes}
            cursor = node_map.get(plan.active_node_id)
            completed_at_turn = max(1, len(plan.turns))
            while cursor is not None:
                if cursor.status in {"active", "pending"}:
                    cursor.status = "completed"
                    cursor.outcome = "mastered"
                    cursor.completed_at_turn = completed_at_turn
                cursor = node_map.get(cursor.parent_id) if cursor.parent_id else None
            plan.active_node_id = None
            plan.completion = PlanCompletion(
                status="completed",
                reason="system_generation_failure; remaining objectives not presented or graded",
            )
            plan.revision += 1
            self.discussion_plan = plan
            projection = project_discussion_plan(plan)
            self.steps = projection.discussion_tree
            self.current_step = ""
            self.current_step_number = ""
            self.stack = []
            self.current_options = []
            self.current_correct_option = ""
            self.pending_advance_feedback = ""
            self.pending_advance_reason = ""
            self.is_complete = True
            remaining_objectives = [
                str(step.get("title", "")).strip()
                for step in self.learning_path[len(plan.root_node_ids):]
                if str(step.get("title", "")).strip()
            ]
            not_presented = "、".join(remaining_objectives) or "后续学习目标"
            summary = (
                "## 问题总结\n\n"
                "因系统连续无法生成并校验下一学习步骤，本轮研讨已提前结束。"
                "已提交的作答记录保留；以下目标未呈现、未判分："
                f"{not_presented}。请继续进入小后测。"
            )
            self.summary_text = summary
            self.history.append({"role": "assistant", "content": summary})
            return {
                "status": "completed",
                "feedback": "下一学习步骤连续生成失败，已按你的选择结束本轮；未展示的目标不计分。",
                "summary": summary,
            }
        if self.pending_user_question and submission_type == "option" and user_feedback.strip() == "理解了":
            return self._resume_after_user_question()
        if self.pending_user_question and submission_type == "option" and user_feedback.strip() == "还不理解":
            return self._answer_user_question(self.pending_user_question, behavior_data)
        if submission_type == "free_text":
            return self._answer_user_question(user_feedback, behavior_data)

        strategy_recovery = (
            submission_type == "option"
            and self._active_node_is_safe_fallback()
        )
        evaluation = (
            AnswerEvaluation(
                answer_performance="mastered",
                reason=self.pending_advance_reason or "回答正确",
            )
            if retrying_advance
            else
            AnswerEvaluation(
                answer_performance="needs_support",
                reason="旧版安全降级选项仅用于选择讲解方式，不参与知识判分",
            )
            if strategy_recovery
            else self.evaluate_answer(user_feedback)
        )
        answered_feedback = self.pending_advance_feedback if retrying_advance else user_feedback
        context = self.build_planning_context(answered_feedback, evaluation, behavior_data)
        next_step_failed = False
        try:
            if strategy_recovery:
                decision = self._teach_current_objective(context)
            elif evaluation.answer_performance == "mastered" and getattr(context, "teaching_plan", []):
                decision = self._advance_after_mastered(context)
            else:
                decision = self.plan_next_action(context)
        except PlanningError as exc:
            logger.warning("discussion planning fallback: {}", exc)
            decision = self._planning_failure_fallback(context)
            next_step_failed = (
                evaluation.answer_performance == "mastered"
                and bool(getattr(context, "teaching_plan", []))
            )
        visible_feedback = (
            None
            if strategy_recovery
            else "回答正确。下一学习内容暂未准备好，请重试。"
            if next_step_failed
            else self._add_visible_answer_feedback(decision, evaluation.reason)
        )
        if next_step_failed and retrying_advance:
            self.current_options = [
                RETRY_NEXT_STEP_OPTION,
                END_DISCUSSION_AFTER_FAILURE_OPTION,
            ]
            self.current_correct_option = ""
            return {"status": "retry_pending", "feedback": visible_feedback}
        next_plan = apply_decision(
            self.discussion_plan,
            decision,
            user_feedback,
            list(context.signals_used),
        )
        projection = project_discussion_plan(next_plan)

        self.discussion_plan = next_plan
        self.steps = projection.discussion_tree
        self.current_step = projection.current_step
        self.current_step_number = projection.current_step_number
        self.stack = projection.stack
        if next_step_failed:
            self.pending_advance_feedback = answered_feedback
            self.pending_advance_reason = evaluation.reason
            self.current_options = [
                RETRY_NEXT_STEP_OPTION,
                END_DISCUSSION_AFTER_FAILURE_OPTION,
            ]
            self.current_correct_option = ""
        elif decision.next_node is not None:
            self.current_options = _without_unknown_option(list(decision.next_node.options))
            self.current_correct_option = decision.next_node.correct_option.strip()
        else:
            self.current_options = _without_unknown_option(list(decision.recovery_options))
            self.current_correct_option = (
                decision.recovery_correct_option.strip()
                if decision.recovery_correct_option
                else ""
            )
        if decision.action in {"advance_main", "complete_discussion"}:
            self.pending_advance_feedback = ""
            self.pending_advance_reason = ""

        round_record = {
            "round": len(self.annotation_history) + 1,
            "step": next_plan.turns[-1].node_id,
            "user_feedback": user_feedback,
            "answer_performance": decision.answer_performance,
            "correctness": {
                "mastered": "true",
                "partial": "false",
                "not_mastered": "false",
            }.get(decision.answer_performance, "na"),
            "knowledge_points": [],
            "planning_action": decision.action,
            "planning_reason": decision.reason,
            "planning_revision": next_plan.revision,
            "signals_used": list(context.signals_used),
        }
        self.annotation_history.append(round_record)
        self.conversations.append({
            "round": round_record["round"],
            "dialogue": {"step": round_record["step"], "user_feedback": user_feedback},
            "annotation": round_record,
        })
        self.history.append({"role": "user", "content": user_feedback})

        if decision.action == "complete_discussion":
            self.is_complete = True
            self.current_options = []
            self.current_correct_option = ""
            try:
                summary = self.summary()
            except Exception:
                logger.exception("discussion summary generation failed")
                summary = "## 问题总结\n\n研讨已完成，但总结生成失败。"
            self.summary_text = summary
            return {
                "status": "completed",
                "feedback": visible_feedback,
                "summary": summary,
            }

        if visible_feedback:
            self.history.append({"role": "assistant", "content": visible_feedback})
        if next_step_failed:
            return {"status": "retry_pending", "feedback": visible_feedback}
        self.history.append({"role": "assistant", "content": projection.current_step})
        status = "main_step" if decision.action == "advance_main" else "sub_step"
        return {
            "status": status,
            "feedback": visible_feedback,
            "step": projection.current_step,
        }

    def call_llm(self, prompt, model_type="instruct", if_system_prompt=False):
        """LLM调用，委托给 llm_api_service，并记录完整 prompt/response。"""
        actual_model_type = model_type
        context = {
            "session_id": self.session_id,
            "user_id": self.user_id,
            "if_system_prompt": if_system_prompt,
            "requested_model_type": model_type,
            "has_system_prompt": bool(self.system_prompt),
        }
        _log_llm_prompt("discussion.call_llm", actual_model_type, prompt, system_prompt=self.system_prompt if if_system_prompt else None, **context)
        if if_system_prompt:
            response = call_llm(prompt, model_type=model_type, system_prompt=self.system_prompt)
        else:
            response = call_llm(prompt, model_type=model_type)
        _log_llm_response("discussion.call_llm", actual_model_type, response, **context)
        return response
    
    def build_system_prompt(self):
        """构建不包含更新的system prompt"""
        from discussion.prompt.zh.prompt import SYSTEM_PROMPT

        self.system_prompt = SYSTEM_PROMPT

    def mastery_to_text(self, mastery):
        try:
            mastery = float(mastery)
        except (TypeError, ValueError):
            mastery = 0.5

        if mastery < 0.3:
            return "基础薄弱"
        if mastery < 0.7:
            return "中等掌握"
        return "熟练掌握"
    
    def build_system_prompt_personalized(self, behavior_result):
        """构建结合认知负荷、KT标注和用户画像的个性化系统提示"""
        from discussion.prompt.zh.prompt import SYSTEM_PROMPT_PERSONALIZED_UPDATED
        # 构建用户画像文本
        user_profile_text = self.user_profile_text if self.user_profile_text else self.build_user_profile()
        weak_points = self.weak_points if self.weak_points else self.build_weak_points()
        cognitive_load = behavior_result.get("cognitive_load_level", "未知") if behavior_result else "未知"
        correctness_analysis = behavior_result.get("correctness_analysis", "") if behavior_result else ""

        user_profile_parts = [
            f"## 用户画像:\n{user_profile_text}",
            f"## 薄弱知识点:\n{weak_points}",
            f"## 当前认知负荷水平: {cognitive_load}",
            f"## 作答行为分析: {correctness_analysis}",
        ]

        # 添加最近交互标注摘要
        if self.annotation_history:
            user_profile_parts.append("\n## 最近交互表现:")
            for record in self.annotation_history[-5:]:
                correctness_label = {"true": "正确", "false": "错误", "na": "不适用"}.get(
                    record.get("correctness", "na"), "未知"
                )
                summary_brief = record.get("summary", "")[:80]
                user_profile_parts.append(
                    f"  - 第{record['round']}轮: {correctness_label} | "
                    f"认知负荷: {record.get('cognitive_load_level', '未知')} | "
                    f"梗概: {summary_brief}"
                )

        user_profile = "\n".join(user_profile_parts)

        # 构建知识图谱文本（从标注历史中汇总知识点掌握情况）
        from common.learner_state import learner_state_key
        from tools.neo4j_service import query_knowledge_points_mastery

        knowledge_graph_parts = []
        if self.related_kps:
            kp_ids = [kp["id"] for kp in self.related_kps]
            mastery_list = query_knowledge_points_mastery(
                kp_ids,
                user_id=learner_state_key(self.user_id, self.learner_state_scope),
            )
            mastery_map = {kp["id"]: kp for kp in mastery_list}

            for kp in self.related_kps:
                kp_id = kp["id"]
                kp_desc = kp.get("description", "")
                kp_mastery_info = mastery_map.get(kp_id)
                mastery = kp_mastery_info["mastery"] if kp_mastery_info else 0.5
                mastery_text = self.mastery_to_text(mastery)
                knowledge_graph_parts.append(
                    f"- {kp_desc} (ID: {kp_id}): 掌握程度：{mastery_text}"
                )

        knowledge_graph = "\n".join(knowledge_graph_parts) if knowledge_graph_parts else "暂无知识点追踪数据"

        # 使用个性化模板构建系统提示
        self.system_prompt = SYSTEM_PROMPT_PERSONALIZED_UPDATED.format(
            user_profile=user_profile,
            knowledge_graph=knowledge_graph,
        )

        return self.system_prompt

    def analyze_question(self):
        prompt = ANALYZE_PROMPT.format(question=self.question)
        llm_response = self.call_llm(prompt, if_system_prompt=False)
        try:
            self.parse_knowledge_point(llm_response)
        except KnowledgeAnalysisError as first_error:
            logger.warning("知识点结构校验失败，尝试纠正: {}", first_error)
            repair_prompt = build_repair_prompt(self.question, llm_response, first_error)
            repaired_response = ""
            try:
                repaired_response = self.call_llm(repair_prompt, if_system_prompt=False)
                self.parse_knowledge_point(repaired_response)
            except Exception as final_error:
                logger.warning("知识点纠正仍失败，降级为可用知识点: {}", final_error)
                self.core_knowledge = (
                    salvage_core_knowledge(repaired_response)
                    or salvage_core_knowledge(llm_response)
                )
                self.problem_solving_hint = None
                self.knowledge_dict = knowledge_dict_from_core(self.core_knowledge)
                if not self.knowledge_dict:
                    self.knowledge_dict = (
                        legacy_knowledge_dict(repaired_response)
                        or legacy_knowledge_dict(llm_response)
                    )

        self.learning_path = build_discussion_learning_path(self.core_knowledge)
        return legacy_analysis_markdown(self.core_knowledge) or llm_response
    
    def parse_knowledge_point(self, content):
        analysis = parse_knowledge_analysis(content)
        self.core_knowledge = analysis.core_knowledge
        self.problem_solving_hint = analysis.problem_solving_hint
        self.knowledge_dict = knowledge_dict_from_core(self.core_knowledge)
        return self.knowledge_dict

    def generate_initial_step(self, question):
        """生成初始步骤；跨会话信息仅以结构化上下文注入普通 LLM。"""
        prompt = INIT_STEP_PROMPT.format(question=question)
        if self.learning_path:
            prompt += (
                "\n# 已拟定教学规划\n"
                + json.dumps(self.learning_path, ensure_ascii=False)
                + "\n第一轮只诊断学习者对规划第1步的掌握程度，不要求其自行讲解尚未学习的内容。"
            )
        if not self.cross_session_enabled:
            self.build_system_prompt()
        target = "## 初始解题步骤"
        violation = None
        for attempt in range(2):
            effective_prompt = prompt
            if attempt:
                effective_prompt += (
                    "\n上一次初始步骤不合格。请重新生成完整初始步骤："
                    f"{violation}。必须先用低门槛问题了解学习者的掌握程度。"
                )
            llm_response = self.call_llm(effective_prompt, if_system_prompt=True)
            if not llm_response or not str(llm_response).strip():
                violation = "模型没有返回初始步骤"
                continue
            result = llm_response.split(target, 1)[-1].strip()
            violation = learner_prompt_violation(result, stage="initial")
            if violation is None:
                try:
                    result, options, correct_option = extract_objective_question(result)
                except PlanningError as exc:
                    violation = str(exc)
                    continue
                violation = self._verify_generated_objective(
                    result,
                    options,
                    correct_option,
                )
                if violation is not None:
                    continue
                if len(options) >= 2:
                    self.current_options = options
                    self.current_correct_option = correct_option
                    return result
                violation = "未提供至少两个可点击的客观选项"
        raise PlanningError(f"unsafe initial discussion prompt: {violation}")
    
    def get_parent_step_number(self, current_number):
        """获取父步骤编号"""
        if "." in current_number:
            parts = current_number.split(".")
            return ".".join(parts[:-1])  
        else:
            return current_number 

    def get_next_sub_step_number(self, parent_stack):
        """获取下一个子步骤编号，传入父步骤栈元素"""
        # 获取或初始化子步骤计数器
        if "sub_step_counter" not in parent_stack:
            parent_stack["sub_step_counter"] = 1
        else:
            parent_stack["sub_step_counter"] += 1
        return f"{parent_stack['step_number']}.{parent_stack['sub_step_counter']}"

    def get_next_main_step_number(self, current_main_number):
        """获取下一个主步骤编号"""
        if "." in current_main_number:
            parts = current_main_number.split(".")
            return str(int(parts[0]) + 1)
        else:
            return str(int(current_main_number) + 1)

    def judge_status(self, current_step, user_feedback):
        """当前步骤状态判断"""
        from discussion.prompt.zh.prompt import HANDLE_USER_FEEDBACK_UPDATED
        prompt = HANDLE_USER_FEEDBACK_UPDATED.format(
            question=self.question, 
            current_step=current_step,  
            user_feedback=user_feedback
        )
        llm_response = self.call_llm(prompt, model_type="large", if_system_prompt=False)

        if not llm_response or not llm_response.strip():
            raise ValueError("LLM返回了空响应，无法判断步骤状态。")
        # 去除可能的 markdown 代码块包裹
        cleaned = llm_response.strip()
        if cleaned.startswith("```"):
            lines = cleaned.splitlines()
            cleaned = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
        try:
            result = json.loads(cleaned)
            status = result.get("status", "")
        except json.JSONDecodeError:
            # LLM 返回的 JSON 含 LaTeX 反斜杠转义，回退到正则提取 status
            m = re.search(r'"status"\s*:\s*"([^"]+)"', cleaned)
            status = m.group(1) if m else ""
        
        if status == "完成":
            return "完成"
        elif status == "未完成":
            return "未完成"
        else:
            raise ValueError("LLM返回的状态不符合预期：应为 '完成' 或 '未完成'。")
        
    def judge_parents_status(self, current_step, sub_history):
        """父步骤状态判断"""
        from discussion.prompt.zh.prompt import HANDLE_PARENT_STEP_STATUS_UPDATED
        prompt = HANDLE_PARENT_STEP_STATUS_UPDATED.format(
            question=self.question, 
            current_step=current_step,  
            sub_history=sub_history
        )
        llm_response = self.call_llm(prompt, model_type="large", if_system_prompt=False)

        if not llm_response or not llm_response.strip():
            raise ValueError("LLM返回了空响应，无法判断父步骤状态。")
        # 去除可能的 markdown 代码块包裹
        cleaned = llm_response.strip()
        if cleaned.startswith("```"):
            lines = cleaned.splitlines()
            cleaned = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
        try:
            result = json.loads(cleaned)
            status = result.get("status", "")
        except json.JSONDecodeError:
            # LLM 返回的 JSON 含 LaTeX 反斜杠转义，回退到正则提取 status
            m = re.search(r'"status"\s*:\s*"([^"]+)"', cleaned)
            status = m.group(1) if m else ""

        if status == "完成":
            return "完成"
        elif status == "未完成":
            return "未完成"
        else:
            raise ValueError("LLM返回的状态不符合预期：应为 '完成' 或 '未完成'。")

    def judge_end_status(self):
        """判断问题是否完全解决"""
        from discussion.prompt.zh.prompt import JUDGE_END_STATUS_UPDATED
        prompt = JUDGE_END_STATUS_UPDATED.format(
            question=self.question, 
            history=self.history
        )
        llm_response = self.call_llm(prompt, model_type="large", if_system_prompt=False)
        
        if llm_response == "问题已解决":
            return True
        elif llm_response == "问题未解决":
            return False
        else:
            raise ValueError("LLM返回的结束状态不符合预期：应为 '问题已解决' 或 '问题未解决'。")

    def generate_next_step(self, current_step, feedback, step_number, history, type):
        """生成下一步骤，根据type判断是主步骤还是子步骤，个性化模式使用TTL微调模型"""
        from discussion.prompt.zh.prompt import GENERATE_NEXT_STEP_UPDATED, GENERATE_NEXT_STEP, GENERATE_NEXT_SUBSTEP_UPDATED
        if type == "main":
            if self.cross_session_enabled:
                prompt = GENERATE_NEXT_STEP_UPDATED.format(
                    question=self.question,
                    current_step=current_step,  
                    accomplished_step=feedback,
                    next_step_number=step_number,
                    history=history
                )
                llm_response = self.call_llm(prompt, if_system_prompt=True)
            else:
                prompt = GENERATE_NEXT_STEP.format(
                    question=self.question,
                    current_step=current_step,  
                    accomplished_step=feedback,
                    next_step_number=step_number,
                    history=history
                )
                llm_response = self.call_llm(prompt, if_system_prompt=True)
            target = "## 下一步解题步骤"
            result = llm_response.split(target, 1)[-1].strip()
        else:
            if self.cross_session_enabled:
                prompt = GENERATE_NEXT_SUBSTEP_UPDATED.format(
                    question=self.question,
                    current_step=current_step,  
                    user_feedback=feedback,
                    next_step_number=step_number,
                    history=history
                )
                llm_response = self.call_llm(prompt, if_system_prompt=True)
            else:
                prompt = GENERATE_NEXT_SUBSTEP.format(
                    question=self.question,
                    current_step=current_step,  
                    user_feedback=feedback,
                    next_step_number=step_number,
                    history=history
                )
                llm_response = self.call_llm(prompt, if_system_prompt=True)
            target = "## 下一步解题子步骤"
            result = llm_response.split(target, 1)[-1].strip()
        return result

    def merge_sub_history(self, stack_element):
        return stack_element["sub_history"]

    def summary(self):
        """生成最终总结"""
        prompt = SUMMARY_PROMPT.format(
            question=self.question, 
            history=self.history
        )
        llm_response = self.call_llm(prompt, if_system_prompt=False)
        
        if not llm_response:
            self.summary_text = "## 问题总结\n\n总结生成失败，请检查 API 调用。"
            return self.summary_text
        
        self.history.append({"role": "assistant", "content": llm_response})
        self.summary_text = self._as_summary_markdown(llm_response)
        return self.summary_text

    @staticmethod
    def _as_summary_markdown(content):
        """Return a complete, display-ready Markdown summary."""
        text = str(content or "").strip()
        if not text:
            return ""
        target = "## 问题总结"
        if target in text:
            return text[text.index(target):].strip()
        return f"{target}\n\n{text}"

    def normalize_summary_text(self):
        """Upgrade legacy JSON summaries to Markdown without losing old sessions."""
        current = str(self.summary_text or "").strip()
        if not current:
            return ""
        if "## 问题总结" in current:
            self.summary_text = self._as_summary_markdown(current)
            return self.summary_text

        legacy = None
        try:
            decoded = json.loads(current)
            if isinstance(decoded, dict):
                legacy = decoded
        except (TypeError, ValueError, json.JSONDecodeError):
            pass

        if legacy is not None:
            for message in reversed(self.history or []):
                if not isinstance(message, dict) or message.get("role") != "assistant":
                    continue
                content = str(message.get("content") or "")
                if "## 问题总结" in content:
                    self.summary_text = self._as_summary_markdown(content)
                    return self.summary_text

            lines = ["## 问题总结"]
            problem_review = str(legacy.get("problem_review") or "").strip()
            solution_steps = legacy.get("solution_steps") or []
            if not isinstance(solution_steps, list):
                solution_steps = [solution_steps]
            answer_summary = str(legacy.get("answer_summary") or "").strip()
            if problem_review:
                lines.extend(["", f"- **问题回顾**：{problem_review}"])
            if solution_steps:
                lines.extend(["", "- **解题步骤**："])
                lines.extend(f"  {index}. {step}" for index, step in enumerate(solution_steps, 1))
            if answer_summary:
                lines.extend(["", f"- **答案总结**：{answer_summary}"])
            self.summary_text = "\n".join(lines)
            return self.summary_text

        self.summary_text = self._as_summary_markdown(current)
        return self.summary_text
    
    def merge_sub_history_as_str(self, sub_history):
        """将子历史转换为字符串表示"""
        return json.dumps(sub_history, ensure_ascii=False, indent=4)
    
    def process_user_feedback(self, user_feedback):
        """Compatibility wrapper for callers that do not submit behavior data."""
        return self.process_feedback(user_feedback)

    # def process_behavior_data(self, behavior_data, user_feedback):
    #     """处理行为数据：将事件流转化为语义标签，调用LLM分析认知负荷和KT价值"""
    #     from discussion.prompt.zh.prompt import ANALYZE_BEHAVIOR_PROMPT
    #     # 兼容 Pydantic 模型与普通字典两种输入形式
    #     if hasattr(behavior_data, "model_dump"):
    #         behavior_data = behavior_data.model_dump()
    #     event_stream = behavior_data.get("event_stream", [])

    #     # 按事件类型归集
    #     events_by_type = {}
    #     for event in event_stream:
    #         etype = event.get("event_type", "")
    #         events_by_type.setdefault(etype, []).append(event)

    #     labels = []

    #     # ---- 提取关键指标 ----
    #     # 窗口失焦最大时长
    #     max_time_away = 0
    #     for fe in events_by_type.get("window_focus", []):
    #         max_time_away = max(max_time_away, fe.get("payload", {}).get("time_away_ms", 0))

    #     has_paste = bool(events_by_type.get("clipboard_action"))
    #     has_rage_click = bool(events_by_type.get("rage_click"))
    #     has_messy_move = bool(events_by_type.get("mouse_messy_move"))
    #     has_scroll_thrashing = bool(events_by_type.get("scroll_thrashing"))
    #     has_blur = bool(events_by_type.get("window_blur"))

    #     # 鼠标悬停最大时长
    #     max_hover_ms = 0
    #     for he in events_by_type.get("mouse_hover", []):
    #         max_hover_ms = max(max_hover_ms, he.get("payload", {}).get("duration_ms", 0))

    #     # 鼠标停止最大时长
    #     max_stop_ms = 0
    #     for ms in events_by_type.get("mouse_stop", []):
    #         max_stop_ms = max(max_stop_ms, ms.get("payload", {}).get("duration_ms", 0))

    #     # 打字指标
    #     typing_events = events_by_type.get("typing_metrics_snapshot", [])
    #     first_keystroke_ms = None
    #     total_keystrokes = 0
    #     has_semantic_deletion = False
    #     max_deleted_chunk = 0
    #     total_pauses = 0
    #     for te in typing_events:
    #         p = te.get("payload", {})
    #         fk = p.get("time_to_first_keystroke_ms")
    #         if fk is not None:
    #             first_keystroke_ms = fk if first_keystroke_ms is None else min(first_keystroke_ms, fk)
    #         total_keystrokes += p.get("total_keystrokes", 0)
    #         if p.get("semantic_deletion"):
    #             has_semantic_deletion = True
    #             max_deleted_chunk = max(max_deleted_chunk, p.get("deleted_chunk_length", 0))
    #         total_pauses += p.get("pause_count_over_2s", 0)
    #     avg_pauses = total_pauses / len(typing_events) if typing_events else 0

    #     has_negative = any(events_by_type.get(t) for t in ("rage_click", "mouse_messy_move", "scroll_thrashing"))

    #     # 非打字/提交相关的交互事件
    #     interaction_types = {"window_blur", "window_focus", "clipboard_action", "scroll_thrashing",
    #                          "rage_click", "mouse_hover", "mouse_stop", "mouse_messy_move"}
    #     has_interaction = any(events_by_type.get(t) for t in interaction_types)

    #     #  window_blur 超过 30秒 且伴随 clipboard_action
    #     scene1_cheating = max_time_away >= 30000 and has_paste
    #     if scene1_cheating:
    #         labels.append("存在较长时间离开页面并伴随剪贴板操作，可能发生了外部信息参考或跨窗口编辑行为。")

    #     # 出现 rage_click 或 mouse_messy_move
    #     if has_rage_click or has_messy_move:
    #         labels.append("存在交互异常行为，可能表现出情绪挫败或操作焦躁。")

    #     # 出现 scroll_thrashing 或 mouse_hover 超过 30秒
    #     if has_scroll_thrashing or max_hover_ms >= 30000:
    #         labels.append("可能存在阅读停滞、信息回看频繁或定位困难。")

    #     # 深度思考 / 高内在负荷: 无负面情绪事件，无场外求助，time_to_first_keystroke > 30秒 或 mouse_stop 较长且最终有输入
    #     long_first_key = first_keystroke_ms is not None and first_keystroke_ms > 30000
    #     long_stop_with_input = max_stop_ms >= 10000 and total_keystrokes > 0
    #     if not has_negative and not scene1_cheating and (long_first_key or long_stop_with_input):
    #         labels.append("可能经历了长时间的深度思考或离线推导演算。")

    #     # 逻辑重建 / 自我纠偏: semantic_deletion == true 且 deleted_chunk_length 较大
    #     if has_semantic_deletion and max_deleted_chunk >= 5:
    #         labels.append("在作答过程中发生了大段删除，可能进行了逻辑推翻与自我纠错。")

    #     # 流畅输出 / 掌握度高: 响应快，pause_count_over_2s 平均极少，无切屏
    #     fast_response = first_keystroke_ms is not None and first_keystroke_ms < 30000
    #     few_pauses = avg_pauses <= 3
    #     if fast_response and few_pauses and not has_blur:
    #         labels.append("作答流畅，可能体现自信度高，认知过程无明显阻碍。")

    #     # 分心 / 注意力转移: mouse_stop 时间过长且无任何操作
    #     if max_stop_ms >= 15000 and total_keystrokes == 0:
    #         labels.append("停止时间过长且无任何操作，可能经历了较长时间的分心或注意力转移。")

    #     # time_to_first_keystroke < 5秒，除了键盘输入外无其他事件
    #     if first_keystroke_ms is not None and first_keystroke_ms < 5000 and not has_interaction:
    #         labels.append("快速直接作答，可能存在恶意输入。")

    #     # ---- 组装 markdown 短文 ----
    #     if labels:
    #         analysis_text = "## 行为数据语义分析\n\n"
    #         for i, label in enumerate(labels, 1):
    #             analysis_text += f"{i}. {label}\n"
    #     else:
    #         analysis_text = "## 行为数据语义分析\n\n未检测到显著的行为特征。"

    #     # ---- 获取当前步骤 ----
    #     current_step = self.stack[-1]["step"] if self.stack else self.current_step

    #     # ---- 组装 prompt 并调用 LLM ----
    #     prompt = ANALYZE_BEHAVIOR_PROMPT.format(
    #         question=self.question,
    #         current_step=current_step,
    #         user_feedback=user_feedback,
    #         behavior_data=json.dumps(event_stream, ensure_ascii=False),
    #         analysis=analysis_text
    #     )

    #     llm_response = call_llm(prompt, model_type="large")

    #     if llm_response:
    #         try:
    #             result = json.loads(llm_response)
    #             return {
    #                 "reasoning_process": result.get("reasoning_process", ""),
    #                 "correctness_analysis": result.get("correctness_analysis", ""),
    #                 "cognitive_load_level": result.get("cognitive_load_level", ""),
    #                 "kt_value": result.get("kt_value", "")
    #             }
    #         except json.JSONDecodeError:
    #             return None
    #     return None

    def process_behavior_data(self, behavior_data, user_feedback):
        """处理行为数据：将事件流转化为语义标签，调用LLM分析认知负荷和KT价值"""
        # 兼容 Pydantic 模型与普通字典两种输入形式
        if hasattr(behavior_data, "model_dump"):
            behavior_data = behavior_data.model_dump()
        if not isinstance(behavior_data, dict):
            return None
        event_stream = behavior_data.get("event_stream") or []
        if not event_stream:
            return None

        # 按事件类型归集
        events_by_type = {}
        for event in event_stream:
            etype = event.get("event_type", "")
            events_by_type.setdefault(etype, []).append(event)

        labels = []

        # ---- 提取关键指标 ----
        # 窗口失焦最大时长（取所有 window_focus 事件中最大的 time_away_ms）
        max_time_away = 0
        for fe in events_by_type.get("window_focus", []):
            max_time_away = max(max_time_away, fe.get("payload", {}).get("time_away_ms", 0))

        has_paste = bool(events_by_type.get("clipboard_action"))
        has_blur = bool(events_by_type.get("window_blur"))

        # 打字指标（新 prompt 中 typing_metrics_snapshot 仅含 time_to_first_keystroke_ms）
        typing_events = events_by_type.get("typing_metrics_snapshot", [])
        first_keystroke_ms = None
        for te in typing_events:
            p = te.get("payload", {})
            fk = p.get("time_to_first_keystroke_ms")
            if fk is not None:
                first_keystroke_ms = fk if first_keystroke_ms is None else min(first_keystroke_ms, fk)

        # 总耗时（从 step_submit 事件中提取）
        total_time_spent_ms = 0
        submit_events = events_by_type.get("step_submit", [])
        if submit_events:
            total_time_spent_ms = submit_events[-1].get("payload", {}).get("total_time_spent_ms", 0)

        # 切屏行为（多次 window_blur 视为频繁借助外部帮助）
        blur_count = len(events_by_type.get("window_blur", []))

        # 非打字/提交相关的交互事件
        interaction_types = {"window_blur", "window_focus", "clipboard_action"}
        has_interaction = any(events_by_type.get(t) for t in interaction_types)

        # window_blur 超过 30 秒且伴随 clipboard_action（疑似外部求助）
        scene1_cheating = max_time_away >= 30000 and has_paste
        if scene1_cheating:
            labels.append("存在较长时间离开页面并伴随剪贴板操作，可能发生了外部信息参考或跨窗口编辑行为。")

        # 深度思考 / 高认知负荷：无外部求助，time_to_first_keystroke > 30 秒
        long_first_key = first_keystroke_ms is not None and first_keystroke_ms > 30000
        if not scene1_cheating and long_first_key:
            labels.append("首次击键时间长，可能经历了长时间的深度思考或离线推导演算。")

        # 频繁切屏（blur_count >= 3）
        if blur_count >= 3:
            labels.append(f"共检测到 {blur_count} 次离开页面事件，可能存在分心或频繁借助外部帮助。")

        # 流畅作答 / 掌握度高：快速首次击键，无切屏
        fast_response = first_keystroke_ms is not None and first_keystroke_ms < 10000
        if fast_response and not has_blur:
            labels.append("作答流畅，可能体现自信度高，认知过程无明显阻碍。")

        # 极速提交（总耗时 < 5 秒且无切屏等交互），可能为恶意/随意输入
        if total_time_spent_ms > 0 and total_time_spent_ms < 5000 and not has_interaction:
            labels.append("提交时间极短且无其他行为事件，可能存在随意作答。")

        # 组装 markdown 短文
        if labels:
            analysis_text = "## 行为数据语义分析\n\n"
            for i, label in enumerate(labels, 1):
                analysis_text += f"{i}. {label}\n"
        else:
            analysis_text = "## 行为数据语义分析\n\n未检测到显著的行为特征。"

        cognitive_load = "高" if long_first_key or blur_count >= 3 else "低" if fast_response and not has_blur else "中"
        kt_value = "低" if scene1_cheating else "高" if fast_response and not has_blur else "中"
        return {
            "reasoning_process": analysis_text,
            "correctness_analysis": "行为信号仅用于调节讲解节奏，不据此判断回答正误。",
            "cognitive_load_level": cognitive_load,
            "kt_value": kt_value,
        }
    
    def process_user_feedback_personalized(self, user_feedback, behavior_data):
        """Compatibility wrapper for callers that submit optional behavior data."""
        return self.process_feedback(user_feedback, behavior_data)


    def build_user_profile(self) -> str:
        """从 data/user/{user_id}/user_profile.json 读取用户画像并返回文本"""
        import os
        from common.learner_state import learner_state_key
        profile_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__) )),
            "data", "user", learner_state_key(self.user_id, self.learner_state_scope), "user_profile.json"
        )
        if os.path.exists(profile_path):
            with open(profile_path, "r", encoding="utf-8") as f:
                user_profile = json.load(f)
        else:
            return "暂无用户画像数据"

        parts = []
        if user_profile.get("common_mistakes"):
            parts.append("常见错误:")
            for mistake in user_profile["common_mistakes"]:
                parts.append(f"  - {mistake}")
        if user_profile.get("study_habits"):
            parts.append("学习习惯:")
            for habit in user_profile["study_habits"]:
                parts.append(f"  - {habit}")    

        return "\n".join(parts) if parts else "暂无用户画像数据"

    def build_weak_points(self) -> str:
        """根据问题获取相关知识点，查询neo4j掌握度，筛选薄弱点并返回分析文本"""
        from KT.ours.annotate import get_question_knowledge_points
        from common.learner_state import learner_state_key
        from tools.neo4j_service import query_knowledge_points_mastery

        # 调用 get_question_knowledge_points 获取与问题相关的知识点
        related_kps = get_question_knowledge_points(self.question)
        self.related_kps = related_kps if related_kps else []
        if not related_kps:
            return "暂无已识别的薄弱知识点"

        # 查询 neo4j 中每个知识点的掌握度
        kp_ids = [kp["id"] for kp in related_kps]
        mastery_list = query_knowledge_points_mastery(
            kp_ids,
            user_id=learner_state_key(self.user_id, self.learner_state_scope),
        )
        mastery_map = {kp["id"]: kp for kp in mastery_list}

        # 筛选掌握程度非熟练的薄弱知识点，提取 exercise_records 中的 analysis
        weak_parts = []
        for kp in related_kps:
            kp_id = kp["id"]
            kp_desc = kp.get("description", "")
            kp_mastery_info = mastery_map.get(kp_id)
            mastery = kp_mastery_info["mastery"] if kp_mastery_info else 0.5
            mastery_text = self.mastery_to_text(mastery)

            if mastery < 0.7:
                weak_parts.append(f"- {kp_desc}：掌握程度：{mastery_text}")
                # 提取 exercise_records 中的 analysis（只取最近4条）
                historical_analysis = []
                if kp_mastery_info:
                    recent_records = kp_mastery_info.get("exercise_records", [])[-4:]
                    for record in recent_records:
                        analysis = record.get("analysis", "")
                        if analysis:
                            historical_analysis.append(f"  练习分析: {analysis}")
                if historical_analysis:
                    weak_parts.append(" 最近的相关练习分析:")
                    weak_parts.extend(historical_analysis)

        return "\n".join(weak_parts) if weak_parts else "暂无已识别的薄弱知识点"

    @learner_state_operation
    def knowledge_tracing(self):
        from KT.ours.main import knowledge_tracing, retrospective_annotate, update_user_profile
        from common.learner_state import learner_state_key
        from tools.record_interaction import record_interaction
        try:
            self.learner_state_status = "PENDING"
            self.learner_state_error = ""
            if not self.annotation_history:
                annotation_history = retrospective_annotate(self.history, self.question)
            else:
                annotation_history = self.annotation_history
            state_user_id = learner_state_key(self.user_id, self.learner_state_scope)
            kt_updates = knowledge_tracing(annotation_history, self.question, user_id=state_user_id)
            if kt_updates:
                self.knowledge_tracing_history.append({
                    "timestamp": datetime.now().isoformat(),
                    "question": self.question,
                    "annotation_rounds": len(annotation_history),
                    "knowledge_points": kt_updates,
                })
            update_user_profile(state_user_id, annotation_history)
            record_interaction(self)
            self.learner_state_status = "READY"
            return True
        except Exception as e:
            self.learner_state_status = "FAILED"
            self.learner_state_error = str(e)[:1000]
            logger.exception("[KT] knowledge_tracing error")
            return False

    def generate_teacher_explanation(self, user_profile: str, weak_points: str, question: str) -> str:
        """
        调用 Teacher Agent 生成高质量定制化讲解，
        """
        from tools.llm_api_service import call_llm

        prompt = INSTRUCTION_PROMPT.format(
            profile_str=user_profile,
            weak_points_str=weak_points,
            question=question,
        )

        _log_llm_prompt("discussion.generate_teacher_explanation", "large", prompt, session_id=self.session_id, user_id=self.user_id)
        response = call_llm(prompt, model_type="large")
        _log_llm_response("discussion.generate_teacher_explanation", "large", response, session_id=self.session_id, user_id=self.user_id)
        return response if response else ""
