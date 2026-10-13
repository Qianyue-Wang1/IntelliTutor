
import os
import json
from datetime import datetime


# 交互记录存储根目录
DATA_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "output", "user",
)

USER_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "data", "user",
)


def _get_user_interaction_dir(user_id: str) -> str:
    """返回用户交互记录目录路径，不存在则创建"""
    interaction_dir = os.path.join(DATA_DIR, user_id, "interactions")
    os.makedirs(interaction_dir, exist_ok=True)
    return interaction_dir


def record_interaction(session) -> str:
    """
    在讨论会话完成后，将完整交互信息持久化到JSON文件。

    参数:
        session: DiscussionSystem 实例，包含本次讨论的所有状态

    返回:
        str: 保存的文件路径

    保存内容:
        - user_id: 用户ID
        - session_id: 会话ID
        - question: 原始题目
        - created_at: 会话创建时间
        - completed_at: 会话完成时间
        - personalized_mode: 是否为个性化模式
        - history: 全局对话历史
        - annotation_history: 每轮交互的KT标注记录
        - conversations: 对话与标注结果存档
        - summary: 最终总结
    """
    user_id = getattr(session, "user_id", "default")
    session_id = getattr(session, "session_id", "unknown")
    now = datetime.now()

    # 读取当前用户画像
    profile_path = os.path.join(USER_DIR, user_id, "user_profile.json")
    user_profile = None
    if os.path.exists(profile_path):
        try:
            with open(profile_path, "r", encoding="utf-8") as f:
                user_profile = json.load(f)
        except (json.JSONDecodeError, OSError):
            user_profile = None

    record = {
        "user_id": user_id,
        "session_id": session_id,
        "question": getattr(session, "question", ""),
        "created_at": session.created_at.isoformat() if hasattr(session, "created_at") else None,
        "completed_at": now.isoformat(),
        "personalized_mode": getattr(session, "personalized_mode", False),
        "history": getattr(session, "history", []),
        "annotation_history": getattr(session, "annotation_history", []),
        "conversations": getattr(session, "conversations", []),
        "summary": getattr(session, "summary_text", ""),
        "knowledge_points_updated": getattr(session, "knowledge_points_updated", []),
        "user_profile_snapshot": user_profile,
    }

    # 写入文件
    interaction_dir = _get_user_interaction_dir(user_id)
    timestamp_str = now.strftime("%Y%m%d_%H%M%S")
    filename = f"{session_id}_{timestamp_str}.json"
    filepath = os.path.join(interaction_dir, filename)

    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(record, f, ensure_ascii=False, indent=2, default=str)

    print(f"[RecordInteraction] 交互记录已保存: {filepath}")
    return filepath


def record_interaction_async(session) -> None:
    """异步执行交互记录（后台线程），不阻塞主请求。"""
    import threading
    thread = threading.Thread(
        target=record_interaction,
        args=(session,),
        daemon=True,
    )
    thread.start()
    print(f"[RecordInteraction] 交互记录后台线程已启动: session_id={getattr(session, 'session_id', '?')}")
