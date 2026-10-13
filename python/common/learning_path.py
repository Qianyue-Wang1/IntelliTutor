import re
from typing import Any, Dict, List


class LearningPathError(ValueError):
    pass


def _text(value: Any) -> str:
    return re.sub(r"\s+", " ", value).strip() if isinstance(value, str) else ""


def _knowledge_points(value: Any) -> List[str]:
    if isinstance(value, list):
        values = value
    elif isinstance(value, str):
        values = re.split(r"[；;、,，]", value.strip().strip("[]【】"))
    else:
        values = []
    return [cleaned for item in values if (cleaned := _text(item))]


def build_discussion_learning_path(core_knowledge: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Project ordered knowledge analysis into a learner-visible teaching plan."""
    valid = [
        {"name": _text(item.get("name")), "content": _text(item.get("content"))}
        for item in core_knowledge
        if isinstance(item, dict) and _text(item.get("name"))
    ]
    path = []
    for index, item in enumerate(valid, start=1):
        previous_name = valid[index - 2]["name"] if index > 1 else ""
        next_name = valid[index]["name"] if index < len(valid) else ""
        teaching_goal = item["content"] or f"理解{item['name']}"
        if next_name:
            if previous_name:
                connection = (
                    f"承接上一步“{previous_name}”，本步讲清{teaching_goal}；"
                    f"随后用它理解“{next_name}”。"
                )
            else:
                connection = f"本步先讲清{teaching_goal}，为下一步“{next_name}”建立基础。"
        elif previous_name:
            connection = (
                f"结合上一步“{previous_name}”，本步讲清{teaching_goal}，完成整体理解。"
            )
        else:
            connection = f"本步讲清{teaching_goal}，完成整体理解。"
        path.append({
            "step_number": index,
            "title": item["name"],
            "knowledge_points": [item["name"]],
            "connection": connection,
            "assessment_focus": f"确认是否理解{item['name']}的核心含义和作用",
        })
    return path


def parse_learning_path(steps_data: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    if not isinstance(steps_data, dict) or not steps_data:
        raise LearningPathError("学习路径不能为空")

    parsed = []
    focuses = set()
    for expected_number, (step_key, step) in enumerate(steps_data.items(), start=1):
        match = re.fullmatch(r"步骤\s*(\d+)", str(step_key))
        if not match or int(match.group(1)) != expected_number:
            raise LearningPathError("学习步骤编号必须从 1 开始连续排列")
        if not isinstance(step, dict):
            raise LearningPathError(f"{step_key} 必须是对象")

        title = _text(step.get("学习主题"))
        points = _knowledge_points(step.get("本步知识点"))
        connection = _text(step.get("承接关系"))
        focus = _text(step.get("检验重点"))
        missing = []
        if not title:
            missing.append("学习主题")
        if not points:
            missing.append("本步知识点")
        if not connection:
            missing.append("承接关系")
        if not focus:
            missing.append("检验重点")
        if missing:
            raise LearningPathError(f"{step_key} 缺少：{'、'.join(missing)}")
        if focus in focuses:
            raise LearningPathError("检验重点不可重复")
        focuses.add(focus)
        parsed.append({
            "step_number": expected_number,
            "title": title,
            "knowledge_points": points,
            "connection": connection,
            "assessment_focus": focus,
        })
    return parsed
