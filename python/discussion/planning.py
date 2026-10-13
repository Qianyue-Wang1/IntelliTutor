from __future__ import annotations

import re
from difflib import SequenceMatcher
from typing import Literal, Optional
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator


NodeKind = Literal["main", "remediation", "probe"]
NodeStatus = Literal["pending", "active", "completed", "superseded"]
NodeOutcome = Literal["mastered", "partial", "not_mastered", "skipped"]
AnswerPerformance = Literal["mastered", "partial", "not_mastered", "needs_support", "off_topic"]
PlanningAction = Literal[
    "continue_current",
    "insert_remediation",
    "insert_probe",
    "advance_main",
    "complete_discussion",
]


class PlanningError(ValueError):
    pass


_INTERNAL_COMMENTARY_MARKERS = (
    "教学规划",
    "根据画像",
    "学习者画像",
    "认知负荷",
    "最小提示原则",
    "思维特质",
    "知识网络",
    "待改进点",
    "规划理由",
)


def learner_questions_are_near_duplicates(current_prompt: str, next_prompt: str) -> bool:
    """Return whether two learner-facing questions are effectively rewordings."""
    def last_question(text: str) -> str:
        lines = [line.strip() for line in str(text or "").splitlines() if line.strip()]
        candidate = lines[-1] if lines else str(text or "")
        matches = re.findall(r"[^。！？!?\n]*[？?]", candidate)
        candidate = matches[-1] if matches else candidate
        return re.sub(r"[\W_]+", "", candidate, flags=re.UNICODE).lower()

    current = last_question(current_prompt)
    following = last_question(next_prompt)
    if not current or not following:
        return False
    return SequenceMatcher(None, current, following).ratio() >= 0.8


def learner_prompt_violation(
    prompt: str,
    *,
    stage: Literal["initial", "follow_up"],
    answer_performance: Optional[AnswerPerformance] = None,
) -> Optional[str]:
    text = str(prompt or "").strip()
    if any(marker in text for marker in _INTERNAL_COMMENTARY_MARKERS):
        return "学生提示包含内部教学规划或画像说明"

    high_floor_patterns = (
        r"(?:列出|罗列).{0,20}(?:关键)?(?:术语|概念)",
        r"(?:写出|给出|列出).{0,20}(?:数学表达式|定义式|公式)",
    )
    if stage == "initial" and any(re.search(pattern, text) for pattern in high_floor_patterns):
        return "初始步骤没有先诊断学习者的掌握程度"
    if answer_performance == "needs_support" and any(
        re.search(pattern, text) for pattern in high_floor_patterns
    ):
        return "学习者明确求助后仍被要求回忆术语或书写公式"
    return None


class PlanNode(BaseModel):
    id: str
    parent_id: Optional[str] = None
    sequence: int = Field(ge=1)
    kind: NodeKind
    objective: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    status: NodeStatus
    outcome: Optional[NodeOutcome] = None
    children_ids: list[str] = Field(default_factory=list)
    created_from_turn: int = Field(ge=0)
    completed_at_turn: Optional[int] = Field(default=None, ge=1)


class PlanTurn(BaseModel):
    turn: int = Field(ge=1)
    node_id: str
    explicit_feedback: str
    answer_performance: AnswerPerformance
    decision: PlanningAction
    reason: str
    signals_used: list[str] = Field(default_factory=list)


class PlanCompletion(BaseModel):
    status: Literal["in_progress", "completed"] = "in_progress"
    reason: Optional[str] = None


class DiscussionPlan(BaseModel):
    schema_version: Literal[1] = 1
    revision: int = Field(default=0, ge=0)
    active_node_id: Optional[str]
    root_node_ids: list[str]
    nodes: list[PlanNode]
    turns: list[PlanTurn] = Field(default_factory=list)
    completion: PlanCompletion = Field(default_factory=PlanCompletion)


class DiscussionProjection(BaseModel):
    discussion_tree: list[dict]
    current_step: str
    current_step_number: str
    stack: list[dict]


class NextPlanNode(BaseModel):
    model_config = ConfigDict(extra="forbid")

    kind: NodeKind
    objective: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    options: list[str] = Field(default_factory=list)


class PlanningDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer_performance: AnswerPerformance
    action: PlanningAction
    reason: str = Field(min_length=1)
    next_node: Optional[NextPlanNode] = None
    recovery_prompt: Optional[str] = Field(default=None, min_length=1, exclude=True)
    recovery_options: list[str] = Field(default_factory=list, exclude=True)

    @model_validator(mode="after")
    def validate_next_node(self):
        needs_node = self.action in {"insert_remediation", "insert_probe", "advance_main"}
        if needs_node and self.next_node is None:
            raise ValueError(f"{self.action} requires next_node")
        if not needs_node and self.next_node is not None:
            raise ValueError(f"{self.action} does not accept next_node")
        if self.recovery_prompt is not None and self.action != "continue_current":
            raise ValueError("recovery_prompt requires continue_current")
        if self.action in {"advance_main", "complete_discussion"} and self.answer_performance != "mastered":
            raise ValueError(f"{self.action} requires mastered answer performance")
        return self


class AnswerEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer_performance: AnswerPerformance
    reason: str = Field(min_length=1)


class TeachingRecovery(BaseModel):
    model_config = ConfigDict(extra="forbid")

    explanation: str = Field(min_length=1)
    example: Optional[str] = None
    check_question: str = Field(min_length=1)
    options: list[str] = Field(min_length=2)


class PlanningContext(BaseModel):
    question: str
    active_objective: str
    active_prompt: str
    completed_summary: list[dict] = Field(default_factory=list)
    explicit_feedback: str
    answer_performance: AnswerPerformance
    answer_reason: str
    teaching_plan: list[dict] = Field(default_factory=list)
    behavior_summary: Optional[dict] = None
    cross_session_summary: Optional[dict] = None
    signals_used: list[str] = Field(default_factory=list)


def _new_node_id() -> str:
    return f"n_{uuid4().hex}"


def create_initial_plan(objective: str, prompt: str) -> DiscussionPlan:
    node = PlanNode(
        id=_new_node_id(),
        sequence=1,
        kind="main",
        objective=objective.strip(),
        prompt=prompt.strip(),
        status="active",
        created_from_turn=0,
    )
    plan = DiscussionPlan(
        active_node_id=node.id,
        root_node_ids=[node.id],
        nodes=[node],
    )
    validate_plan(plan)
    return plan


def validate_plan(plan: DiscussionPlan) -> None:
    if plan.schema_version != 1:
        raise PlanningError(f"unsupported discussion plan schema: {plan.schema_version}")

    node_ids = [node.id for node in plan.nodes]
    if len(node_ids) != len(set(node_ids)):
        raise PlanningError("discussion plan contains duplicate node ids")
    node_map = {node.id: node for node in plan.nodes}

    if len(plan.root_node_ids) != len(set(plan.root_node_ids)):
        raise PlanningError("discussion plan contains duplicate root ids")
    if any(node_id not in node_map for node_id in plan.root_node_ids):
        raise PlanningError("discussion plan contains a missing root node")
    actual_roots = [node.id for node in plan.nodes if node.parent_id is None]
    if set(actual_roots) != set(plan.root_node_ids):
        raise PlanningError("root node references are inconsistent")

    for node in plan.nodes:
        if node.parent_id is not None:
            parent = node_map.get(node.parent_id)
            if parent is None or node.id not in parent.children_ids:
                raise PlanningError("parent and child references are inconsistent")
        if len(node.children_ids) != len(set(node.children_ids)):
            raise PlanningError("node contains duplicate child references")
        for child_id in node.children_ids:
            child = node_map.get(child_id)
            if child is None or child.parent_id != node.id:
                raise PlanningError("parent and child references are inconsistent")

    siblings: dict[Optional[str], list[PlanNode]] = {}
    for node in plan.nodes:
        siblings.setdefault(node.parent_id, []).append(node)
    for group in siblings.values():
        sequences = sorted(node.sequence for node in group)
        if sequences != list(range(1, len(group) + 1)):
            raise PlanningError("sibling sequences must be contiguous")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node_id: str) -> None:
        if node_id in visiting:
            raise PlanningError("discussion plan contains a cycle")
        if node_id in visited:
            return
        visiting.add(node_id)
        for child_id in node_map[node_id].children_ids:
            visit(child_id)
        visiting.remove(node_id)
        visited.add(node_id)

    for root_id in plan.root_node_ids:
        visit(root_id)
    if visited != set(node_ids):
        raise PlanningError("discussion plan contains unreachable nodes")

    active_nodes = [node for node in plan.nodes if node.status == "active"]
    if plan.completion.status == "in_progress":
        if len(active_nodes) != 1 or plan.active_node_id != active_nodes[0].id:
            raise PlanningError("in-progress plan must have exactly one active node")
    elif active_nodes or plan.active_node_id is not None:
        raise PlanningError("completed plan cannot have an active node")

    for node in plan.nodes:
        if node.status == "completed":
            if node.outcome is None or node.completed_at_turn is None:
                raise PlanningError("completed nodes require outcome and completion turn")
        elif node.outcome is not None or node.completed_at_turn is not None:
            raise PlanningError("unfinished nodes cannot have completion metadata")

    if [turn.turn for turn in plan.turns] != list(range(1, len(plan.turns) + 1)):
        raise PlanningError("turn numbers must be contiguous")
    if any(turn.node_id not in node_map for turn in plan.turns):
        raise PlanningError("turn references a missing node")


def apply_decision(
    plan: DiscussionPlan,
    decision: PlanningDecision,
    explicit_feedback: str,
    signals_used: list[str],
) -> DiscussionPlan:
    validate_plan(plan)
    if plan.completion.status == "completed" or plan.active_node_id is None:
        raise PlanningError("discussion is already complete")

    result = plan.model_copy(deep=True)
    node_map = {node.id: node for node in result.nodes}
    active = node_map[result.active_node_id]
    turn_number = len(result.turns) + 1
    def complete_active_branch() -> None:
        cursor = active
        while cursor is not None:
            if cursor.status in {"active", "pending"}:
                cursor.status = "completed"
                cursor.outcome = "mastered"
                cursor.completed_at_turn = turn_number
            cursor = node_map.get(cursor.parent_id) if cursor.parent_id else None

    if decision.action == "continue_current":
        if decision.recovery_prompt is not None:
            active.prompt = decision.recovery_prompt
    elif decision.action in {"insert_remediation", "insert_probe"}:
        expected_kind = "remediation" if decision.action == "insert_remediation" else "probe"
        if decision.next_node.kind != expected_kind:
            raise PlanningError(f"{decision.action} requires a {expected_kind} node")
        active.status = "pending"
        child = PlanNode(
            id=_new_node_id(),
            parent_id=active.id,
            sequence=len(active.children_ids) + 1,
            kind=decision.next_node.kind,
            objective=decision.next_node.objective,
            prompt=decision.next_node.prompt,
            status="active",
            created_from_turn=turn_number,
        )
        active.children_ids.append(child.id)
        result.nodes.append(child)
        result.active_node_id = child.id
    elif decision.action == "advance_main":
        if decision.next_node.kind != "main":
            raise PlanningError("advance_main requires a main node")
        complete_active_branch()
        next_node = PlanNode(
            id=_new_node_id(),
            parent_id=None,
            sequence=len(result.root_node_ids) + 1,
            kind="main",
            objective=decision.next_node.objective,
            prompt=decision.next_node.prompt,
            status="active",
            created_from_turn=turn_number,
        )
        result.nodes.append(next_node)
        result.root_node_ids.append(next_node.id)
        result.active_node_id = next_node.id
    elif decision.action == "complete_discussion":
        complete_active_branch()
        result.active_node_id = None
        result.completion = PlanCompletion(status="completed", reason=decision.reason)
    else:
        raise PlanningError(f"unsupported planning action: {decision.action}")

    result.turns.append(PlanTurn(
        turn=turn_number,
        node_id=active.id,
        explicit_feedback=explicit_feedback,
        answer_performance=decision.answer_performance,
        decision=decision.action,
        reason=decision.reason,
        signals_used=list(signals_used),
    ))
    result.revision += 1
    validate_plan(result)
    return result


def project_discussion_plan(plan: DiscussionPlan) -> DiscussionProjection:
    validate_plan(plan)
    node_map = {node.id: node for node in plan.nodes}
    number_by_id: dict[str, str] = {}

    def project_node(node_id: str, number: str, root: bool) -> dict:
        node = node_map[node_id]
        number_by_id[node.id] = number
        children = sorted(
            (node_map[child_id] for child_id in node.children_ids),
            key=lambda child: child.sequence,
        )
        projected_children = [
            project_node(child.id, f"{number}.{index}", False)
            for index, child in enumerate(children, 1)
        ]
        active = node.id == plan.active_node_id
        has_active_descendant = any(_contains_active(child.id) for child in children)
        base = {
            "id": int(number) if root else number.replace(".", "-"),
            "isActive": active,
            "isExpanded": bool(projected_children) or has_active_descendant,
            "isMain": root and node.kind == "main",
            "subSteps": projected_children,
        }
        if root:
            base.update({"title": "", "description": node.prompt, "content": ""})
        else:
            base.update({"title": "", "description": "", "content": node.prompt})
        return base

    def _contains_active(node_id: str) -> bool:
        node = node_map[node_id]
        if node.id == plan.active_node_id:
            return True
        return any(_contains_active(child_id) for child_id in node.children_ids)

    roots = sorted((node_map[node_id] for node_id in plan.root_node_ids), key=lambda node: node.sequence)
    tree = [project_node(node.id, str(index), True) for index, node in enumerate(roots, 1)]

    if plan.active_node_id is None:
        return DiscussionProjection(
            discussion_tree=tree,
            current_step="",
            current_step_number="",
            stack=[],
        )

    active = node_map[plan.active_node_id]
    current_number = number_by_id[active.id]
    ancestors = []
    cursor = active
    while cursor is not None:
        ancestors.append(cursor)
        cursor = node_map.get(cursor.parent_id) if cursor.parent_id else None
    ancestors.reverse()
    stack = []
    for node in ancestors:
        node_turns = [turn for turn in plan.turns if turn.node_id == node.id]
        sub_history = [
            {"role": "user", "content": turn.explicit_feedback}
            for turn in node_turns
        ]
        stack.append({
            "step": f"步骤{number_by_id[node.id]}：{node.prompt}",
            "step_number": number_by_id[node.id],
            "sub_step_counter": len(node.children_ids),
            "sub_history": sub_history,
        })
    return DiscussionProjection(
        discussion_tree=tree,
        current_step=f"步骤{current_number}：{active.prompt}",
        current_step_number=current_number,
        stack=stack,
    )


def migrate_legacy_discussion_plan(snapshot: dict) -> DiscussionPlan:
    tree = snapshot.get("discussion_tree") or snapshot.get("steps") or []
    if not isinstance(tree, list) or not tree:
        raise PlanningError("legacy discussion snapshot has no discussion tree")

    nodes: list[PlanNode] = []
    node_by_number: dict[str, PlanNode] = {}
    legacy_active_numbers: list[str] = []

    def visit(items: list, parent: Optional[PlanNode], prefix: str = "") -> None:
        if not isinstance(items, list):
            raise PlanningError("legacy discussion tree children must be a list")
        for index, item in enumerate(items, 1):
            if not isinstance(item, dict):
                raise PlanningError("legacy discussion tree node must be an object")
            number = f"{prefix}.{index}" if prefix else str(index)
            prompt = str(item.get("description") or item.get("content") or "").strip()
            if not prompt:
                raise PlanningError("legacy discussion tree node has no prompt")
            node = PlanNode(
                id=_new_node_id(),
                parent_id=parent.id if parent else None,
                sequence=index,
                kind="main" if parent is None else "remediation",
                objective=prompt,
                prompt=prompt,
                status="pending",
                created_from_turn=0,
            )
            nodes.append(node)
            node_by_number[number] = node
            if parent:
                parent.children_ids.append(node.id)
            if bool(item.get("isActive")):
                legacy_active_numbers.append(number)
            visit(item.get("subSteps") or [], node, number)

    visit(tree, None)
    current_number = str(snapshot.get("current_step_number") or "").strip().replace("-", ".")
    active_number: Optional[str] = current_number if current_number in node_by_number else None
    if active_number is None:
        if len(legacy_active_numbers) == 1:
            active_number = legacy_active_numbers[0]
        elif len(legacy_active_numbers) > 1:
            raise PlanningError("legacy discussion snapshot has multiple active nodes")
    if active_number is None:
        stack = snapshot.get("stack") or []
        if isinstance(stack, list) and stack:
            stack_number = str((stack[-1] or {}).get("step_number") or "").replace("-", ".")
            if stack_number in node_by_number:
                active_number = stack_number

    is_complete = bool(snapshot.get("is_complete"))
    if active_number is None and not is_complete:
        raise PlanningError("legacy discussion snapshot has no uniquely identifiable active node")

    active_node = node_by_number.get(active_number) if active_number else None
    for node in nodes:
        if node is active_node:
            node.status = "active"
        else:
            node.status = "completed"
            node.outcome = "skipped"
            node.completed_at_turn = 1

    roots = [node for node in nodes if node.parent_id is None]
    plan = DiscussionPlan(
        revision=0,
        active_node_id=active_node.id if active_node else None,
        root_node_ids=[node.id for node in roots],
        nodes=nodes,
        turns=[],
        completion=PlanCompletion(
            status="completed" if is_complete else "in_progress",
            reason="restored from completed legacy snapshot" if is_complete else None,
        ),
    )
    validate_plan(plan)
    return plan
