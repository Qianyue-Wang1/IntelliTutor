import os
import json
import re
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), "..", ".."))
from tools.llm_api_service import call_llm
from KT.ours.prompt import ANNO_KC_SYSTEM_PROMPT, GENERATE_ANSWER_PROMPT


# 加载知识点数据 
KC_DATA_PATH = os.path.join(os.path.dirname(__file__), "data", "kc_data.json")

def load_kc_data() -> dict:
    with open(KC_DATA_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


# 工具函数：从 LLM 返回文本中提取 JSON list 
def extract_json_list(text: str) -> list | None:
    """从 LLM 返回的文本中提取 JSON 列表"""
    if text is None:
        return None
    # 清理 LaTeX 符号
    text = text.replace("\\(", "").replace("\\)", "")
    # 尝试匹配 ```json ... ``` 或 result = ...
    match = re.search(r"```json\s*(.*?)\s*```|result\s*=\s*(\[.*?\])", text, re.DOTALL)
    raw = None
    if match:
        raw = match.group(1) or match.group(2)
    else:
        # 尝试直接匹配最外层 JSON 数组
        match = re.search(r"\[.*\]", text, re.DOTALL)
        if match:
            raw = match.group(0)
    if raw is None:
        return None
    try:
        result = json.loads(raw)
        if isinstance(result, dict) and "result" in result:
            result = result["result"]
        return result if isinstance(result, list) else None
    except json.JSONDecodeError:
        return None


def extract_json_str(text: str) -> str | None:
    """从 LLM 返回的文本中提取 JSON 字符串值（用于正确性判断）"""
    if text is None:
        return None
    text = text.strip()
    # 尝试匹配 ```json ... ``` 或 result = ...
    match = re.search(r'```json\s*(.*?)\s*```|result\s*=\s*(".*?"|\{.*?\})', text, re.DOTALL)
    if match:
        raw = match.group(1) or match.group(2)
        try:
            result = json.loads(raw)
            if isinstance(result, dict) and "result" in result:
                return str(result["result"])
            return str(result)
        except json.JSONDecodeError:
            pass
    # 直接匹配 true/false/na
    for keyword in ("true", "false", "na"):
        if keyword in text.lower():
            return keyword
    return text.strip()


# build_system_prompt
def build_system_prompt(level: str) -> str:
    """
    根据标注层级构建系统提示词

    参数:
        level: "domain" | "topic" | "sub_topic" | "kc" | "correctness" | "summary"
    """
    role = "你是一名通用学习标注专家。你将看到一轮围绕学习任务的师生对话。"
    if level == "domain":
        return (
            role + "请从给定知识领域列表中选择与本轮学习目标相关的领域。\n"
            "- 只能使用候选列表中的原始名称，不要补造领域。\n"
            "- 最终回答为 JSON 列表: result = [\"领域1\", \"领域2\", ...]"
        )
    elif level == "topic":
        return (
            role + "请从给定主题列表中选择与本轮学习目标相关的主题。\n"
            "- 只能使用候选列表中的原始名称，不要补造主题。\n"
            "- 最终回答为 JSON 列表: result = [\"主题1\", \"主题2\", ...]"
        )
    elif level == "sub_topic":
        return (
            role + "请从给定子主题列表中选择与本轮学习目标相关的子主题。\n"
            "- 只能使用候选列表中的原始名称，不要补造子主题。\n"
            "- 最终回答为 JSON 列表: result = [\"子主题1\", \"子主题2\", ...]"
        )
    elif level == "kc":
        return (
            role + "请从候选知识点中选择完成教师当前要求真正需要的知识点。\n"
            "- 知识点可属于概念、事实、规则、方法或技能，但只能选择候选列表中的描述。\n"
            "- 最终回答为 JSON 列表: result = [\"知识点1\", \"知识点2\", ...]"
        )
    elif level == "correctness":
        return (
            role + "请判断学习者是否完成教师在当前学习任务中的要求。\n"
            "- 正确完成并提供必要依据、证据或操作时为 true。\n"
            "- 关键错误、遗漏或未回答时为 false。\n"
            "- 不评价知识或没有可用评价标准时为 na；开放任务按给定标准判断。\n"
            '- 最终预测为字符串: result = "true/false/na"'
        )
    elif level == "summary":
        return (
            role + "请用不超过 100 字总结教师要求、学习者回答、涉及知识点和理解程度。"
            "对事实不确定性和开放任务的评价标准如实记录，不要编造。直接输出总结文本。"
        )
    else:
        raise ValueError(f"未知的标注层级: {level}")


# build_prompt
def build_prompt(conversation: str, candidates: list[str] | None = None, kc_list: list[dict] | None = None) -> str:
    """
    构建用户提示词

    参数:
        conversation: 单轮对话文本（包含教师和学生的发言）
        candidates:   可选的候选项列表（领域/主题/子主题名称）
        kc_list:      可选的知识点列表（用于最终 KC 标注），每个元素包含 id 和 description
    """
    prompt = f"[BEGIN DIALOGUE]\n{conversation}\n[END DIALOGUE]"

    if candidates is not None:
        prompt += "\n\n[BEGIN OPTIONS]\n- " + "\n- ".join(candidates) + "\n[END OPTIONS]"

    if kc_list is not None:
        kp_strs = [f"ID: {kp['id']}, 描述: {kp['description']}" for kp in kc_list]
        prompt += "\n\n[BEGIN KNOWLEDGE POINTS]\n- " + "\n- ".join(kp_strs) + "\n[END KNOWLEDGE POINTS]"
        prompt += (
            "\n\n请从上述知识点中选择与该轮对话相关的知识点。"
            "\n你的最终回答应该是一个JSON列表，列表中每个元素是一个知识点的ID。"
            '\n使用以下模板: result = ["ID1", "ID2", ...]'
        )

    return prompt


# get_domain_groups
def get_domain_groups(conversation: str, domain_groups: list[str]) -> list[str]:
    """
    根据传入的 domain groups 候选列表，调用 LLM 返回与对话相关的 domain groups

    参数:
        conversation:  单轮对话文本
        domain_groups: 所有可选的领域列表
    返回:
        相关领域名称列表
    """
    system_prompt = build_system_prompt("domain")
    prompt = build_prompt(conversation, candidates=domain_groups)
    response = call_llm(prompt, system_prompt=system_prompt)
    result = extract_json_list(response)
    if result is None:
        return []
    # 过滤，只保留合法的 domain group
    return [dg for dg in result if dg in domain_groups]


# get_topics
def get_topics(conversation: str, topics: list[str]) -> list[str]:
    """
    根据传入的 topics 候选列表，调用 LLM 返回与对话相关的 topics

    参数:
        conversation: 单轮对话文本
        topics:       所有可选的主题列表
    返回:
        相关主题名称列表
    """
    system_prompt = build_system_prompt("topic")
    prompt = build_prompt(conversation, candidates=topics)
    response = call_llm(prompt, system_prompt=system_prompt)
    result = extract_json_list(response)
    if result is None:
        return []
    return [t for t in result if t in topics]


# get_sub_topics
def get_sub_topics(conversation: str, sub_topics: list[str]) -> list[str]:
    """
    根据传入的 sub_topics 候选列表，调用 LLM 返回与对话相关的 sub_topics

    参数:
        conversation: 单轮对话文本
        sub_topics:   所有可选的子主题列表
    返回:
        相关子主题名称列表
    """
    system_prompt = build_system_prompt("sub_topic")
    prompt = build_prompt(conversation, candidates=sub_topics)
    response = call_llm(prompt, system_prompt=system_prompt)
    result = extract_json_list(response)
    if result is None:
        return []
    return [st for st in result if st in sub_topics]


# annotate_correctness
def annotate_correctness(conversation: str, kc_list: list[dict]) -> str:
    """
    标注正确性：传入单轮对话和知识点列表，调用 LLM 判断学生回答是否正确

    参数:
        conversation: 单轮对话文本
        kc_list:      该轮对话相关的知识点列表（每个元素含 id, description）
    返回:
        "true" / "false" / "na"
    """
    system_prompt = build_system_prompt("correctness")
    kp_desc = "、".join([kp["description"] for kp in kc_list]) if kc_list else "无"
    prompt = (
        f"[BEGIN DIALOGUE]\n{conversation}\n[END DIALOGUE]\n\n"
        f"该轮对话涉及的知识点: {kp_desc}\n\n"
        "请判断学生是否正确回答了教师的问题。"
    )
    response = call_llm(prompt, system_prompt=system_prompt, model_type = "large")
    result = extract_json_str(response)
    if result in ("true", "false"):
        return result
    return "na"

def annotate_kc(conversation: str) -> list[dict]:
    """
    标注知识点主函数

    流程:
        1. 调用 get_domain_groups → 获取相关的 domain groups
        2. 根据 domain groups 在 taxonomy 中查找 topics 候选列表
        3. 调用 get_topics → 获取相关的 topics
        4. 根据 topics 在 taxonomy 中查找 sub_topics 候选列表
        5. 调用 get_sub_topics → 获取相关的 sub_topics
        6. 根据 sub_topics 在 knowledge_points 中查找所有对应的知识点
        7. 将知识点注入 prompt，调用 LLM → 返回最终相关知识点列表

    参数:
        conversation: 单轮对话文本
    返回:
        list[dict]，每个元素是一个 knowledge point（含 id, description, domain, topic, sub_topic）
    """
    kc_data = load_kc_data()
    all_domain_groups = kc_data["domain_groups"]
    taxonomy = kc_data["taxonomy"]
    knowledge_points = kc_data["knowledge_points"]

    # 获取相关 domain groups
    selected_domains = get_domain_groups(conversation, all_domain_groups)
    if not selected_domains:
        return []

    # 根据 domain groups 收集候选 topics
    candidate_topics = []
    for domain in selected_domains:
        if domain in taxonomy:
            for topic in taxonomy[domain]:
                if topic not in candidate_topics:
                    candidate_topics.append(topic)
    if not candidate_topics:
        return []

    # 3. 获取相关 topics
    selected_topics = get_topics(conversation, candidate_topics)
    if not selected_topics:
        return []

    # 4. 根据 topics 收集候选 sub_topics
    candidate_sub_topics = []
    for domain in selected_domains:
        if domain in taxonomy:
            for topic, sub_topics in taxonomy[domain].items():
                if topic in selected_topics:
                    for st in sub_topics:
                        if st not in candidate_sub_topics:
                            candidate_sub_topics.append(st)
    if not candidate_sub_topics:
        return []

    # 5. 获取相关 sub_topics
    selected_sub_topics = get_sub_topics(conversation, candidate_sub_topics)
    if not selected_sub_topics:
        return []

    # 6. 根据 sub_topics 查找所有对应的 knowledge points
    candidate_kps = [
        kp for kp in knowledge_points
        if kp["sub_topic"] in selected_sub_topics
    ]
    if not candidate_kps:
        return []

    # 7. 将 knowledge points 注入 prompt，调用 LLM 获取最终结果
    system_prompt = build_system_prompt("kc")
    prompt = build_prompt(conversation, kc_list=candidate_kps)
    response = call_llm(prompt, system_prompt=system_prompt)
    selected_ids = extract_json_list(response)
    if selected_ids is None:
        return [] # 解析失败时返回空列表

    # 根据 ID 过滤出最终的 knowledge points
    kp_map = {kp["id"]: kp for kp in candidate_kps}
    normalized_ids = []
    for item in selected_ids:
        if isinstance(item, str):
            normalized_ids.append(item)
        elif isinstance(item, dict):
            kp_id = item.get("id") or item.get("ID") or item.get("kp_id")
            if isinstance(kp_id, str):
                normalized_ids.append(kp_id)

    result = [kp_map[kp_id] for kp_id in normalized_ids if kp_id in kp_map]
    return result if result else candidate_kps

def build_question_system_prompt(level: str) -> str:
    """
    根据层级构建基于问题的系统提示词

    参数:
        level: "domain" | "topic" | "sub_topic" | "kc"
    """
    level_desc = {
        "domain": ("知识点领域", "领域"),
        "topic": ("知识主题", "主题"),
        "sub_topic": ("子主题", "子主题"),
    }
    if level in level_desc:
        name_full, name_short = level_desc[level]
        return (
            "你是一名通用学习标注专家。你将看到一个学习任务。"
            f"请从给定的{name_full}列表中，找出与该学习任务相关的{name_short}。\n"
            f"- 请从给定的{name_short}列表中选择，完全按照它们出现的名称书写。不要使用不在列表中的{name_short}。\n"
            "- 在给出最终回答之前，请写一个简短的分析。\n"
            f'- 你的最终回答应该是一个JSON列表，使用以下模板: result = ["{name_short}1", "{name_short}2", ...]'
        )
    elif level == "kc":
        return (
            "你是一名通用学习标注专家。你将看到一个学习任务。"
            "请从给定的多个知识点中，找出完成该学习任务需要掌握的知识点。\n"
            "- 请从给定的知识点列表中选择，完全按照它们出现的描述书写。不要使用不在列表中的知识点。\n"
            "- 在给出最终回答之前，请写一个简短的分析。\n"
            '- 你的最终回答应该是一个JSON列表，使用以下模板: result = ["ID1", "ID2", ...]'
        )
    else:
        raise ValueError(f"未知的标注层级: {level}")


def build_question_prompt(question: str, candidates: list[str] | None = None, kc_list: list[dict] | None = None) -> str:
    """
    构建基于问题的用户提示词
    """
    prompt = f"[BEGIN QUESTION]\n{question}\n[END QUESTION]"

    if candidates is not None:
        prompt += "\n\n[BEGIN OPTIONS]\n- " + "\n- ".join(candidates) + "\n[END OPTIONS]"

    if kc_list is not None:
        kp_strs = [f"ID: {kp['id']}, 描述: {kp['description']}" for kp in kc_list]
        prompt += "\n\n[BEGIN KNOWLEDGE POINTS]\n- " + "\n- ".join(kp_strs) + "\n[END KNOWLEDGE POINTS]"
        prompt += (
            "\n\n请从上述知识点中选择解决该问题所需要掌握的知识点。"
            "\n你的最终回答应该是一个JSON列表，列表中每个元素是一个知识点的ID。"
            '\n使用以下模板: result = ["ID1", "ID2", ...]'
        )

    return prompt


def get_question_knowledge_points(question: str) -> list[dict]:
    """
    根据传入的学习任务，依次调用 LLM 获取相关的 domain groups、topics、sub topics，
    最终返回与该学习任务相关的 knowledge points。

    流程:
        1. 调用 LLM 获取相关的 domain groups
        2. 根据 domain groups 在 taxonomy 中查找 topics 候选列表
        3. 调用 LLM 获取相关的 topics
        4. 根据 topics 在 taxonomy 中查找 sub_topics 候选列表
        5. 调用 LLM 获取相关的 sub_topics
        6. 根据 sub_topics 查找所有对应的知识点
        7. 调用 LLM 从候选知识点中筛选最终相关知识点

    参数:
        question: 学习任务文本
    返回:
        list[dict]，每个元素是一个 knowledge point（含 id, description, domain, topic, sub_topic）
    """
    kc_data = load_kc_data()
    all_domain_groups = kc_data["domain_groups"]
    taxonomy = kc_data["taxonomy"]
    knowledge_points = kc_data["knowledge_points"]

    # 获取相关 domain groups
    system_prompt = build_question_system_prompt("domain")
    prompt = build_question_prompt(question, candidates=all_domain_groups)
    response = call_llm(prompt, system_prompt=system_prompt)
    selected_domains = extract_json_list(response)
    if not selected_domains:
        return []
    selected_domains = [dg for dg in selected_domains if dg in all_domain_groups]
    if not selected_domains:
        return []

    # 根据 domain groups 收集候选 topics
    candidate_topics = []
    for domain in selected_domains:
        if domain in taxonomy:
            for topic in taxonomy[domain]:
                if topic not in candidate_topics:
                    candidate_topics.append(topic)
    if not candidate_topics:
        return []

    # 获取相关 topics
    system_prompt = build_question_system_prompt("topic")
    prompt = build_question_prompt(question, candidates=candidate_topics)
    response = call_llm(prompt, system_prompt=system_prompt)
    selected_topics = extract_json_list(response)
    if not selected_topics:
        return []
    selected_topics = [t for t in selected_topics if t in candidate_topics]
    if not selected_topics:
        return []

    # 根据 topics 收集候选 sub_topics
    candidate_sub_topics = []
    for domain in selected_domains:
        if domain in taxonomy:
            for topic, sub_topics in taxonomy[domain].items():
                if topic in selected_topics:
                    for st in sub_topics:
                        if st not in candidate_sub_topics:
                            candidate_sub_topics.append(st)
    if not candidate_sub_topics:
        return []

    # 获取相关 sub_topics
    system_prompt = build_question_system_prompt("sub_topic")
    prompt = build_question_prompt(question, candidates=candidate_sub_topics)
    response = call_llm(prompt, system_prompt=system_prompt)
    selected_sub_topics = extract_json_list(response)
    if not selected_sub_topics:
        return []
    selected_sub_topics = [st for st in selected_sub_topics if st in candidate_sub_topics]
    if not selected_sub_topics:
        return []

    # 根据 sub_topics 查找所有对应的 knowledge points
    candidate_kps = [
        kp for kp in knowledge_points
        if kp["sub_topic"] in selected_sub_topics
    ]
    if not candidate_kps:
        return []

    # 将 knowledge points 注入 prompt，调用 LLM 获取最终结果
    system_prompt = build_question_system_prompt("kc")
    prompt = build_question_prompt(question, kc_list=candidate_kps)
    response = call_llm(prompt, system_prompt=system_prompt)
    selected_ids = extract_json_list(response)
    if selected_ids is None:
        return candidate_kps  # 解析失败时返回全部候选

    # 根据 ID 过滤出最终的 knowledge points
    kp_map = {kp["id"]: kp for kp in candidate_kps}
    normalized_ids = []
    for item in selected_ids:
        if isinstance(item, str):
            normalized_ids.append(item)
        elif isinstance(item, dict):
            kp_id = item.get("id") or item.get("ID") or item.get("kp_id")
            if isinstance(kp_id, str):
                normalized_ids.append(kp_id)

    result = [kp_map[kp_id] for kp_id in normalized_ids if kp_id in kp_map]
    return result if result else candidate_kps


def annotate_summary(conversation: str) -> str:
    """
    生成单轮对话的总结

    参数:
        conversation: 单轮对话文本
    返回:
        对话总结文本，不超过100字
    """
    system_prompt = build_system_prompt("summary")
    prompt = f"[BEGIN DIALOGUE]\n{conversation}\n[END DIALOGUE]\n\n请对该轮对话进行总结，概括教师的提问、学生的回答、涉及的知识点和学生的理解程度，不超过100字。"
    response = call_llm(prompt, system_prompt=system_prompt)
    return response if response else ""


def generate_question_answer(question: str) -> str:
    """
    调用 LLM 对题目生成逐步解答，返回格式：
        [正确解答开始]
        解题步骤（每步一行）
        与任务类型相符的最终结果
        [正确解答结束]

    参数:
        question: 原始学习任务文本
    返回:
        包含标签的完整解答字符串；调用失败时返回空字符串
    """
    prompt = GENERATE_ANSWER_PROMPT.format(question=question)
    response = call_llm(prompt)
    if not response:
        print("[KT] 题目解答生成失败，answer 将为空")
        return ""
    solution = response.strip()
    return f"[正确解答开始]\n{solution}\n[正确解答结束]"
