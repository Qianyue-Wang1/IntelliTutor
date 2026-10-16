from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, Dict, List, Optional


# New prompts emit the general categories. Legacy math-specific categories
# remain readable so saved sessions and older model responses can be restored.
KNOWLEDGE_TYPES = {
    "概念类", "事实类", "规则类", "方法类", "技能类",
    "运算类", "公式类", "定理类",
}
KNOWLEDGE_LEVELS = {"基础层级", "核心方法层级"}


@dataclass(frozen=True)
class KnowledgeAnalysis:
    core_knowledge: List[Dict[str, Any]]
    problem_solving_hint: Optional[Dict[str, str]]


class KnowledgeAnalysisError(ValueError):
    def __init__(self, errors: List[str]):
        self.errors = errors
        super().__init__("；".join(errors))


def _clean_text(value: Any) -> str:
    return re.sub(r"\s+", " ", value).strip() if isinstance(value, str) else ""


def _escape_latex_backslashes(content: str) -> str:
    """Escape LaTeX command slashes that an LLM emitted unescaped in JSON strings."""
    result = []
    in_string = False
    index = 0
    while index < len(content):
        char = content[index]
        if char == '"':
            in_string = not in_string
            result.append(char)
            index += 1
            continue
        if in_string and char == "\\":
            next_char = content[index + 1] if index + 1 < len(content) else ""
            if next_char in '"\\/':
                result.extend((char, next_char))
                index += 2
                continue
            if next_char in "bfnrt" and (index + 2 == len(content) or not content[index + 2].isalpha()):
                result.extend((char, next_char))
                index += 2
                continue
            if next_char == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", content[index + 2:index + 6]):
                result.append(content[index:index + 6])
                index += 6
                continue
            result.append("\\\\")
            index += 1
            continue
        result.append(char)
        index += 1
    return "".join(result)


def _extract_json_object(content: str) -> Dict[str, Any]:
    if not isinstance(content, str) or not content.strip():
        raise KnowledgeAnalysisError(["LLM 返回为空"])

    content = _escape_latex_backslashes(content)
    decoder = json.JSONDecoder()
    for match in re.finditer(r"\{", content):
        try:
            payload, _ = decoder.raw_decode(content[match.start():])
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            return payload

    raise KnowledgeAnalysisError(["未找到合法的 JSON 对象"])


def _normalize_core_knowledge(raw_items: Any) -> tuple[List[Dict[str, Any]], List[str]]:
    if not isinstance(raw_items, list):
        return [], ["core_knowledge 必须是数组"]

    normalized: List[Dict[str, Any]] = []
    errors: List[str] = []
    seen_ids = set()
    seen_names = set()

    for index, item in enumerate(raw_items, start=1):
        prefix = f"core_knowledge[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{prefix} 必须是对象")
            continue

        item_id = item.get("id")
        name = _clean_text(item.get("name"))
        knowledge_type = _clean_text(item.get("type"))
        level = _clean_text(item.get("level"))
        content = _clean_text(item.get("content"))

        item_errors = []
        if isinstance(item_id, bool) or not isinstance(item_id, int) or item_id <= 0:
            item_errors.append("id 必须是正整数")
        elif item_id in seen_ids:
            item_errors.append("id 不可重复")
        if not name:
            item_errors.append("name 不能为空")
        elif name in seen_names:
            item_errors.append("name 不可重复")
        if knowledge_type not in KNOWLEDGE_TYPES:
            item_errors.append("type 不在允许范围内")
        if level not in KNOWLEDGE_LEVELS:
            item_errors.append("level 不在允许范围内")
        if not content:
            item_errors.append("content 不能为空")

        if item_errors:
            errors.extend(f"{prefix}: {message}" for message in item_errors)
            continue

        seen_ids.add(item_id)
        seen_names.add(name)
        normalized.append({
            "id": item_id,
            "name": name,
            "type": knowledge_type,
            "level": level,
            "content": content,
        })

    if not normalized:
        errors.append("core_knowledge 至少需要一个有效知识点")
    present_levels = {item["level"] for item in normalized}
    for required_level in KNOWLEDGE_LEVELS:
        if required_level not in present_levels:
            errors.append(f"core_knowledge 缺少{required_level}条目")

    return normalized, errors


def _normalize_hint(raw_hint: Any, core_knowledge: List[Dict[str, Any]]) -> tuple[Optional[Dict[str, str]], List[str]]:
    if not isinstance(raw_hint, dict):
        return None, ["problem_solving_hint 必须是对象"]

    fields = (
        "basic_idea",
        "skill_enlightenment",
        "core_mathematical_thought",
        "related_method",
    )
    hint = {field: _clean_text(raw_hint.get(field)) for field in fields}
    errors = [f"problem_solving_hint.{field} 不能为空" for field in fields if not hint[field]]

    related = hint["related_method"]
    method_entry = next((item for item in core_knowledge if item["name"] == related), None)
    if related and method_entry is None:
        errors.append("related_method 必须与 core_knowledge 中的 name 完全一致")
    elif method_entry is not None and method_entry["level"] != "核心方法层级":
        errors.append("related_method 必须指向核心方法层级知识点")

    return (hint if not errors else None), errors


def parse_knowledge_analysis(content: str) -> KnowledgeAnalysis:
    payload = _extract_json_object(content)
    core_knowledge, core_errors = _normalize_core_knowledge(payload.get("core_knowledge"))
    hint, hint_errors = _normalize_hint(payload.get("problem_solving_hint"), core_knowledge)
    errors = core_errors + hint_errors
    if errors:
        raise KnowledgeAnalysisError(errors)
    return KnowledgeAnalysis(core_knowledge=core_knowledge, problem_solving_hint=hint)


def salvage_core_knowledge(content: str) -> List[Dict[str, Any]]:
    """Return individually valid entries after a failed strict parse."""
    try:
        payload = _extract_json_object(content)
    except KnowledgeAnalysisError:
        return []
    core_knowledge, _ = _normalize_core_knowledge(payload.get("core_knowledge"))
    return core_knowledge


def legacy_knowledge_dict(content: str) -> Dict[str, str]:
    pattern = r"\d+\.\s+\*\*(.*?)\*\*\s*：(.*?)(?=\n\d+\. \*\*|\n\Z|\Z)"
    matches = re.findall(pattern, content or "", re.DOTALL)
    return {
        _clean_text(title): _clean_text(description)
        for title, description in matches
        if _clean_text(title)
    }


def knowledge_dict_from_core(core_knowledge: List[Dict[str, Any]]) -> Dict[str, str]:
    return {item["name"]: item["content"] for item in core_knowledge}


def legacy_analysis_markdown(core_knowledge: List[Dict[str, Any]]) -> str:
    return "\n".join(
        f'{index}. **{item["name"]}**：{item["content"]}'
        for index, item in enumerate(core_knowledge, start=1)
    )


def build_repair_prompt(question: str, invalid_response: str, validation_error: Exception) -> str:
    invalid_text = str(invalid_response or "")
    return f"""你是一位跨学科学习设计专家。上一次对学习任务的知识分析不符合结构要求，请根据校验错误重新生成完整内容。

严格只输出一个合法 JSON 对象，不要使用 Markdown 代码块，不要输出解释文字。

字段要求：
1. core_knowledge 为非空数组；每项包含正整数 id，以及非空的 name、content。
2. type 只能是：概念类、事实类、规则类、方法类、技能类。
3. level 只能是：基础层级、核心方法层级；两种层级都必须出现。
4. problem_solving_hint 包含 basic_idea、skill_enlightenment、core_mathematical_thought、related_method。
5. basic_idea 只描述常规学习或处理方向；skill_enlightenment 按“观察线索→联想知识或方法→引导提问”书写。
6. related_method 必须与某个核心方法层级知识点的 name 完全一致。
7. 禁止直接给出最终答案、完整结论或可照抄的成品；只提供完成任务所需的知识与启发。

学习任务：{question}

上一次输出：{invalid_text[:8000]}

校验错误：{validation_error}
"""
