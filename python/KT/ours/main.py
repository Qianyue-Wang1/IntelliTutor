"""
KT模块实时交互标注入口
对每一轮交互对话进行标注：标注对错、部分对、相关知识点、对话梗概
"""
import os
import sys
import json
import threading
from copy import deepcopy
from common.state_storage import atomic_write_json, learner_state_operation
import numpy as np
from datetime import datetime

sys.path.append(os.path.join(os.path.dirname(__file__), "..", ".."))

from KT.ours.annotate import annotate_kc, annotate_correctness, annotate_summary
from tools.llm_api_service import call_llm


def evaluate_mastery_with_api(system_prompt: str, user_prompt: str) -> float | None:
    """Return a bounded mastery value from the configured ordinary LLM API."""
    response = call_llm(
        user_prompt + "\n\n只输出 JSON：{\"mastery\": 0 到 1 之间的数字}。",
        system_prompt=system_prompt,
    )
    try:
        text = str(response or "").strip()
        if text.startswith("```"):
            text = text.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        mastery = float(json.loads(text)["mastery"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None
    return mastery if 0.0 <= mastery <= 1.0 else None


def annotate_interaction(current_step: str, user_feedback: str, question: str) -> dict:
    """
    对单轮交互进行实时标注

    参数:
        current_step: 当前解题步骤内容
        user_feedback: 用户反馈内容
        question: 原始学习任务

    返回:
        dict: {
            "correctness": "true" | "false",
            "knowledge_points": [{"id": ..., "description": ..., ...}, ...],
        }
    """
    # 构造对话文本格式
    conversation = (
        f"题目: {question}\n"
        f"教师: {current_step}\n"
        f"学生: {user_feedback}"
    )

    # 1. 标注相关知识点
    knowledge_points = annotate_kc(conversation)

    # 2. 标注正确性（对错/部分对）
    correctness = annotate_correctness(conversation, knowledge_points)

    # 3. 生成对话梗概
    summary = annotate_summary(conversation)

    return {
        "correctness": correctness,
        "knowledge_points": knowledge_points,
        "summary": summary,
    }

def retrospective_annotate(history, question):
    """
    非个性化模式下，对完整对话进行回顾性标注，
    提取涉及的知识点、评估正确性、生成对话梗概
    """
    from KT.ours.annotate import annotate_kc, annotate_correctness, annotate_summary

    print("[KT] 非个性化模式，进行回顾性对话标注...")

    # 将对话历史构建为对话文本
    conversation_parts = []
    for msg in history:
        role = msg.get("role", "")
        content = msg.get("content", "")
        if role == "user":
            conversation_parts.append(f"学生: {content}")
        elif role == "assistant":
            conversation_parts.append(f"教师: {content}")

    full_conversation = f"题目: {question}\n" + "\n".join(conversation_parts)

    # 截断过长内容，避免 prompt 超限
    if len(full_conversation) > 4000:
        full_conversation = full_conversation[:4000] + "\n...(对话截断)"

    # 标注知识点
    knowledge_points = annotate_kc(full_conversation)

    # 标注正确性
    correctness = annotate_correctness(full_conversation, knowledge_points)

    # 生成对话梗概
    summary = annotate_summary(full_conversation)

    annotation_history = [{
        "round": 1,
        "step": "完整对话回顾",
        "user_feedback": "",
        "correctness": correctness,
        "knowledge_points": knowledge_points,
        "summary": summary,
        "cognitive_load_level": "",
        "kt_value": "",
        "correctness_analysis": "",
    }]

    print(f"[KT] 回顾性标注完成: 正确性={correctness}, 知识点数={len(knowledge_points)}")

    return annotation_history


def is_mastery_outlier(new_value, history_values, z_threshold=2.0, min_samples=3):
    """
    使用统计学方法判断本次知识追踪掌握度是否为异常值。
    综合使用 Z-score 和 IQR 两种方法，任一方法判定为异常即认为是异常值。

    参数:
        new_value: 本次预测的掌握概率 (prob_mastery)
        history_values: 历史各轮次的 this_round_mastery 列表
        z_threshold: Z-score 阈值，默认 2.0
        min_samples: 最少需要的历史样本数，不足则不做异常检测

    返回:
        (is_outlier: bool, reason: str)
    """
    if len(history_values) < min_samples:
        return False, f"历史样本数不足({len(history_values)}<{min_samples})，跳过异常检测"

    arr = np.array(history_values, dtype=float)
    mean = np.mean(arr)
    std = np.std(arr, ddof=1)  # 使用样本标准差

    # Z-score 检测
    z_outlier = False
    if std > 1e-6:
        z_score = abs(new_value - mean) / std
        z_outlier = z_score > z_threshold
        z_info = f"Z-score={z_score:.3f}(阈值{z_threshold})"
    else:
        # 标准差极小，用固定偏差阈值
        z_outlier = abs(new_value - mean) > 0.3
        z_info = f"std≈0, 偏差={abs(new_value - mean):.3f}(阈值0.3)"

    # IQR 四分位距检测
    q1 = np.percentile(arr, 25)
    q3 = np.percentile(arr, 75)
    iqr = q3 - q1
    if iqr > 1e-6:
        lower_bound = q1 - 1.5 * iqr
        upper_bound = q3 + 1.5 * iqr
        iqr_outlier = new_value < lower_bound or new_value > upper_bound
        iqr_info = f"IQR=[{lower_bound:.3f}, {upper_bound:.3f}]"
    else:
        # IQR 极小，回退到中位数偏差判定
        median = np.median(arr)
        iqr_outlier = abs(new_value - median) > 0.3
        iqr_info = f"IQR≈0, 中位数偏差={abs(new_value - median):.3f}(阈值0.3)"

    # 综合判定：两种方法都判定为异常才认为是异常值（保守策略）
    # 强制转为 Python 原生 bool，避免 numpy.bool_ 导致 FastAPI 序列化失败
    is_outlier = bool(z_outlier and iqr_outlier)
    reason = f"{z_info}, {iqr_info}, mean={mean:.3f}, std={std:.3f}, new={new_value:.3f}"

    return is_outlier, reason


@learner_state_operation
def knowledge_tracing(annotation_history, question, answer="", user_id="default"):
    """
    讨论完成后进行综合知识追踪评估：
    1. 收集本轮对话中所有轮次对话的标注结果
    2. 结合相关对话构建输入prompt
    3. 调用本地LLM获取True/False logits预测掌握度
    4. 根据分析结果更新知识图谱
    5. 使用统计异常检测判断掌握度是否需要更新
    """
    from KT.ours.prompt import KT_SYSTEM_PROMPT, LLM_KT_SYSTEM_PROMPT
    from KT.ours.annotate import generate_question_answer
    from tools.neo4j_service import update_knowledge_points_mastery, query_knowledge_points_mastery

    print("[KT] 开始知识追踪评估...")

    # 若未传入正确解答，则调用 LLM 生成
    if not answer:
        print("[KT] 未提供正确解答，调用 LLM 自动生成...")
        answer = generate_question_answer(question)

    if not annotation_history:
        print("[KT] 无标注记录，跳过知识追踪")
        return
    
    # 过滤掉 kt_value 为 Low 的低价值轮次
    filtered_history = [
        r for r in annotation_history
        if r.get("kt_value", "").strip().lower() not in ("low", "低")
    ]
    n_filtered = len(annotation_history) - len(filtered_history)
    if n_filtered > 0:
        print(f"[StudentKT] 已过滤 {n_filtered} 轮低价值对话 (kt_value=Low)")
    if not filtered_history:
        print("[StudentKT] 过滤后无有效轮次，跳过")
        return []
    annotation_history = filtered_history

    # 构建对话内容文本，同时收集所有涉及的知识点
    dialogue_lines = []
    all_kps = {}  # kp_id -> kp_info
    last_idx = len(annotation_history) - 1

    for idx, record in enumerate(annotation_history):
        r = record.get("round", 0)
        step = record.get("step", "")
        feedback = record.get("user_feedback", "")
        correctness = record.get("correctness", "na")
        kps = record.get("knowledge_points", [])
        # cognitive = record.get("cognitive_load_level", "暂无")
        # behavior_analysis = "学生在回答过程中" + record.get("correctness_analysis", "") + "，" + record.get("behavior_analysis", "")
        # kt_value = record.get("kt_value", "暂无")
        # analysis = record.get("correctness_analysis", "")

        kp_text = " ".join(
            [f"{i+1}) {kp.get('description', '')}" for i, kp in enumerate(kps)]
        )

        dialogue_lines.append(f"教师 第{r}轮: {step}")
        # 最后一轮不加入学生回答及相关信息
        if idx < last_idx:
            dialogue_lines.append(f"学生 第{r}轮: {feedback}")
            dialogue_lines.append(f"学生 第{r}轮 正确性: {correctness}")
            if kp_text:
                dialogue_lines.append(f"第{r}轮 知识点: {kp_text}")
        dialogue_lines.append("")

        for kp in kps:
            kp_id = kp.get("id", "")
            if kp_id:
                all_kps[kp_id] = kp

    if not all_kps:
        print("[KT] 未找到涉及的知识点，跳过知识追踪")
        return

    dialogue_text = "\n".join(dialogue_lines)
    kp_id_list = list(all_kps.keys())
    print(f"[KT] 涉及 {len(kp_id_list)} 个知识点: {kp_id_list}")

    # 查询知识图谱中的当前掌握度
    current_mastery_list = query_knowledge_points_mastery(kp_id_list, user_id=user_id)
    current_map = {kp["id"]: kp for kp in current_mastery_list}

    # 若存在未初始化的知识点节点，先为该用户创建全量知识点图谱
    missing_kp_ids = [kp_id for kp_id in kp_id_list if kp_id not in current_map]
    if missing_kp_ids:
        print(f"[KT] 用户 {user_id} 存在 {len(missing_kp_ids)} 个未初始化的知识点节点，触发知识图谱初始化...")
        from tools.neo4j_service import create_user_knowledge_points
        create_user_knowledge_points(user_id)
        # 重新查询以获取新创建节点的初始状态
        current_mastery_list = query_knowledge_points_mastery(kp_id_list, user_id=user_id)
        current_map = {kp["id"]: kp for kp in current_mastery_list}

    # 对每个知识点，构造 prompt 并通过普通 LLM API 评估掌握度。
    updates = []
    for kp_id, kp_info in all_kps.items():
        kp_desc = kp_info.get("description", "")

        user_prompt = (
            f"[题目开始]\n"
            f"{question}\n"
            f"[题目结束]\n\n"
            f"[正确解答开始]\n"
            f"{answer}\n"
            f"[正确解答结束]\n\n"
            f"[对话开始]\n"
            f"{dialogue_text}\n"
            f"[对话结束]\n\n"
            f"知识点: {kp_desc}\n"
        )

        print(f"[KT] 评估知识点 {kp_id} ({kp_desc}) 的掌握度...")
        print(f"[KT] 构造的用户prompt:\n{user_prompt}\n")  

        prob_mastery = evaluate_mastery_with_api(LLM_KT_SYSTEM_PROMPT, user_prompt)
        if prob_mastery is None:
            print(f"[KT] 知识点 {kp_id} 的掌握度响应无效，保留已有掌握度")
            continue
        print(f"[KT] 知识点 {kp_id} ({kp_desc}): 预测掌握概率={prob_mastery:.4f}")

        # 获取当前历史记录
        current_kp = current_map.get(kp_id, {})
        old_mastery = current_kp.get("mastery", 0.5)
        exercise_records = current_kp.get("exercise_records", [])

        # 融合历史掌握度: recommended = 0.4 × 本轮评估 + 0.6 × 历史掌握度
        alpha = 0.4
        recommended_mastery = alpha * prob_mastery + (1 - alpha) * old_mastery
        recommended_mastery = max(0.0, min(1.0, recommended_mastery))

        # 异常值检测：判断本次掌握度是否需要更新
        history_mastery_values = [
            rec.get("this_round_mastery", None)
            for rec in exercise_records
            if rec.get("this_round_mastery") is not None
        ]
        is_outlier, outlier_reason = is_mastery_outlier(prob_mastery, history_mastery_values)

        if is_outlier:
            print(
                f"[KT] 知识点 {kp_id} 本次掌握度评估为异常值，不更新掌握度。"
                f"原始预测={prob_mastery:.4f}, 融合值={recommended_mastery:.4f}, "
                f"原因: {outlier_reason}"
            )
            # 异常值情况下，保持原有掌握度不变
            recommended_mastery = old_mastery
        else:
            if history_mastery_values:
                print(f"[KT] 知识点 {kp_id} 异常检测通过，正常更新。{outlier_reason}")

        # 判断变化方向
        if recommended_mastery - old_mastery > 0.01:
            mastery_change = "提升"
        elif old_mastery - recommended_mastery > 0.01:
            mastery_change = "下降"
        else:
            mastery_change = "持平"

        # 进行做题记录的总结和分析
        exercise_summary_prompt = (
            f"请根据以下对话内容，用一句话概括学生在知识点「{kp_desc}」上的作答内容：\n\n"
            f"{dialogue_text}\n\n"
            f"只输出概括内容，不要添加任何前缀或解释。"
        )
        exercise_analysis_prompt = (
            f"请根据以下对话内容，针对知识点「{kp_desc}」，简要评估学生的错误部分及原因（若学生回答正确则说明回答正确）：\n\n"
            f"{dialogue_text}\n\n"
            f"只输出评估内容，不要添加任何前缀或解释。"
        )
        exercise_summary = call_llm(exercise_summary_prompt) or ""
        exercise_analysis = call_llm(exercise_analysis_prompt) or ""

        new_exercise = {
            "question": question[:100],
            "timestamp": datetime.now().isoformat(),
            "mastery_change": mastery_change,
            "old_mastery": old_mastery,
            "new_mastery": recommended_mastery,
            "this_round_mastery": prob_mastery,
            "is_outlier": is_outlier,
            "outlier_reason": outlier_reason,
            "annotation_history": deepcopy(annotation_history),
            "summary": exercise_summary.strip(),
            "analysis": exercise_analysis.strip(),
        }
        exercise_records.append(new_exercise)
        exercise_records = exercise_records[-20:]

        updates.append({
            "id": kp_id,
            "mastery": recommended_mastery,
            "old_mastery": old_mastery,
            "new_mastery": recommended_mastery,
            "this_round_mastery": prob_mastery,
            "exercise_records": exercise_records,
        })

        print(
            f"[KT] 知识点 {kp_id}: "
            f"{old_mastery:.2f} → {recommended_mastery:.2f} ({mastery_change})"
        )

    # 更新知识图谱
    if updates:
        update_knowledge_points_mastery(updates, user_id=user_id)
        print(f"[KT] 已更新 {len(updates)} 个知识点的掌握度到知识图谱")
    else:
        print("[KT] 无需更新知识点掌握度")

    return updates


def knowledge_tracing_async(annotation_history, question, user_id="default"):
    """异步执行知识追踪评估（后台线程），不阻塞主请求。"""
    thread = threading.Thread(
        target=knowledge_tracing,
        kwargs={
            "annotation_history": deepcopy(annotation_history),
            "question": question,
            "user_id": user_id,
        },
        daemon=True
    )
    thread.start()
    print(f"[KT] 知识追踪后台线程已启动: user_id={user_id}")


@learner_state_operation
def update_user_profile(user_id, annotation_history):
    """
    根据完整轮次对话标注历史，调用大模型分析是否需要增删改用户画像，
    并更新 data/user/{user_id}/user_profile.json。

    用户画像包含：
    - common_mistakes: 用户的常见错误
    - study_habits: 用户近期的做题习惯
    """
    from KT.ours.prompt import UPDATE_USER_PROFILE_PROMPT
    profile_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        "data", "user", user_id
    )
    profile_path = os.path.join(profile_dir, "user_profile.json")

    # 读取现有画像
    if os.path.exists(profile_path):
        with open(profile_path, "r", encoding="utf-8") as f:
            user_profile = json.load(f)
    else:
        user_profile = {
            "user_id": user_id,
            "common_mistakes": [],
            "study_habits": [],
        }

    # 构建完整轮次对话文本
    dialogue_parts = []
    for record in annotation_history:
        r = record.get("round", 0)
        step = record.get("step", "")
        feedback = record.get("user_feedback", "")
        correctness = record.get("correctness", "na")
        summary = record.get("summary", "")
        kps = record.get("knowledge_points", [])
        kp_text = "、".join([kp.get("description", "") for kp in kps]) if kps else "无"
        dialogue_parts.append(
            f"第{r}轮 - 教师: {step}\n"
            f"第{r}轮 - 学生: {feedback}\n"
            f"第{r}轮 - 正确性: {correctness}, 知识点: {kp_text}, 梗概: {summary}, 认知负荷: {record.get('cognitive_load_level', '未知')}, 行为分析: {record.get('behavior_analysis', '暂无')}, 分析价值: {record.get('kt_value', '暂无')}"
        )
    dialogue_text = "\n".join(dialogue_parts)

    current_profile_text = json.dumps(user_profile, ensure_ascii=False, indent=2)

    prompt = UPDATE_USER_PROFILE_PROMPT.format(
        dialogue_text=dialogue_text,
        current_profile_text=current_profile_text,
        user_id=user_id
    )

    print(f"[UserProfile] 开始更新用户 {user_id} 的画像...")
    response = call_llm(prompt, model_type="large")

    if not response:
        print("[UserProfile] LLM返回为空，跳过画像更新")
        return

    # 从LLM返回中提取JSON
    import re
    json_match = re.search(r"```json\s*(.*?)\s*```", response, re.DOTALL)
    raw_json = json_match.group(1) if json_match else response.strip()

    try:
        updated_profile = json.loads(raw_json)
        # 确保必要字段存在
        updated_profile.setdefault("user_id", user_id)
        updated_profile.setdefault("common_mistakes", [])
        updated_profile.setdefault("study_habits", [])
    except json.JSONDecodeError:
        print(f"[UserProfile] LLM返回的JSON解析失败: {raw_json[:200]}")
        return

    # 写入文件
    os.makedirs(profile_dir, exist_ok=True)
    atomic_write_json(profile_path, updated_profile)

    print(f"[UserProfile] 用户 {user_id} 画像已更新: {profile_path}")


def update_user_profile_async(user_id, annotation_history):
    """异步执行用户画像更新（后台线程），不阻塞主请求。"""
    thread = threading.Thread(
        target=update_user_profile,
        args=(user_id, deepcopy(annotation_history)),
        daemon=True
    )
    thread.start()
    print(f"[UserProfile] 用户画像更新后台线程已启动: user_id={user_id}")
