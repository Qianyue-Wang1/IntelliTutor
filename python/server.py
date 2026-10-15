from __future__ import annotations
import traceback
import uuid
import re
import random
import requests
import httpx
import json
import os
import time
import logging
from logging.handlers import RotatingFileHandler
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.exception_handlers import http_exception_handler
from pydantic import BaseModel, Field
from typing import Any, List, Dict, Literal, Optional, Union
import uvicorn
from fastapi.middleware.cors import CORSMiddleware
from datetime import datetime, timedelta
import asyncio
from contextlib import asynccontextmanager
from common.concurrency import register_new_session, run_blocking, session_operation, shutdown, startup
from common.prompt.en.prompt import GEOMETRY_SYSTEM_PROMPT
from common.prompt.zh.prompt import IMAGE_INPUT_PROMPT, CHECK_INPUT_PROMPT, GEOGRABRA_SYSTEM_PROMPT
from common.knowledge_analysis import (
    KnowledgeAnalysisError,
    build_repair_prompt,
    knowledge_dict_from_core,
    legacy_knowledge_dict,
    parse_knowledge_analysis,
    salvage_core_knowledge,
)
from common.learning_path import LearningPathError, parse_learning_path
from explanation.prompt.zh.prompt import EXTRACT_KNOWLEDGE_POINTS_PROMPT, GENERATE_STEPS_PROMPT, PARSE_USER_IDEA_PROMPT, ANALYZE_USER_INTENT_PROMPT
from tools.llm_api_service import call_llm_async, close_clients, validate_config
from discussion.planning import (
    DiscussionPlan,
    PlanningError,
    create_initial_plan,
    migrate_legacy_discussion_plan,
    project_discussion_plan,
    validate_plan,
)


@asynccontextmanager
async def lifespan(app):
    validate_config()
    startup()
    try:
        yield
    finally:
        await shutdown()
        await close_clients()


app = FastAPI(lifespan=lifespan)

STEP_GENERATION_TIMEOUT_SECONDS = 600


@app.get("/healthz", include_in_schema=False)
async def healthz():
    """Container liveness endpoint; dependency readiness is handled by Compose."""
    return {"status": "ok"}

# 日志配置 
_LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs")
os.makedirs(_LOG_DIR, exist_ok=True)

logger = logging.getLogger("intellitutor")
logger.setLevel(logging.INFO)

_file_handler = RotatingFileHandler(
    os.path.join(_LOG_DIR, "server.log"),
    maxBytes=10 * 1024 * 1024,  # 10 MB
    backupCount=5,
    encoding="utf-8",
)
_file_handler.setFormatter(logging.Formatter(
    "%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
))
logger.addHandler(_file_handler)

@app.exception_handler(HTTPException)
async def _log_http_exception(request: Request, exc: HTTPException):
    logger.error(
        "HTTP %d | %s %s | %s",
        exc.status_code,
        request.method,
        str(request.url),
        exc.detail,
    )
    return await http_exception_handler(request, exc)


@app.exception_handler(Exception)
async def _log_unhandled_exception(request: Request, exc: Exception):
    logger.error(
        "500 Unhandled | %s %s | %s\n%s",
        request.method,
        str(request.url),
        str(exc),
        traceback.format_exc(),
    )
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal Server Error"},
    )


origins = [
    "http://localhost:5173",
    "http://localhost:5174",
    "http://localhost:8090",
    "http://127.0.0.1:5173",  
    "http://127.0.0.1:5174",
    "http://127.0.0.1:8090"
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],  
    allow_headers=["*"],
)

# Direct LLM is intentionally independent from the structured Tutor engines.
# The assigned question is immutable; only follow-up messages are accepted.
class DirectSession:
    def __init__(self, session_id: str, question: str, user_id: str = "default"):
        self.session_id = session_id
        self.question = question
        self.user_id = user_id
        self.session_type = "direct"
        self.created_at = datetime.now()
        self.last_accessed = datetime.now()
        self.messages = []
        self.initial_explanation_generated = False
        self.experiment_run_id = None


def _direct_text_only(content: str | None) -> str:
    content = content or ""
    def html_image_label(match: re.Match) -> str:
        alt = re.search(r'\balt\s*=\s*(["\'])(.*?)\1', match.group(0), re.IGNORECASE | re.DOTALL)
        return alt.group(2) if alt else "图示"

    content = re.sub(r'!\[([^\]]*)\]\([^)]*\)', lambda match: match.group(1) or "图示", content)
    content = re.sub(r'<img\b[^>]*>', html_image_label, content, flags=re.IGNORECASE | re.DOTALL)
    paragraphs = re.split(r'\n\s*\n', content.strip())
    visible = [paragraph for paragraph in paragraphs if not re.match(
        r'^\s*以下为严谨、分层、概念清晰的完整讲解', paragraph
    )]
    return "\n\n".join(visible)


def _direct_session_data(session: DirectSession) -> Dict[str, Any]:
    """Return the stable response contract shared by all Direct endpoints."""
    return {
        "session_id": session.session_id,
        "question": _learner_question(session),
        "user_id": session.user_id,
        "messages": session.messages,
        "initial_explanation_generated": session.initial_explanation_generated,
        "experiment_run_id": session.experiment_run_id,
    }

# 会话管理器
class SessionManager:
    def __init__(self):
        self.sessions = {}  # session_id -> BaseSession
        self.step_generation_locks = {}  # session_id -> asyncio.Lock
    
    def create_session(self, session_type, question, user_id="default"):
        session_id = str(uuid.uuid4())
        if session_type == "explanation":
            from explanation.explanationSession import ExplanationSession
            session = ExplanationSession(session_id, question, user_id=user_id)
        elif session_type == "discussion":
            from discussion.discussionSession import DiscussionSystem
            session = DiscussionSystem(session_id, question, user_id=user_id)
        elif session_type == "direct":
            session = DirectSession(session_id, question, user_id=user_id)
        else:
            raise ValueError("无效的会话类型")
        
        self.sessions[session_id] = session
        register_new_session(session_id, lambda: self.delete_session(session_id))
        return session
    
    def get_session(self, session_id):
        session = self.sessions.get(session_id)
        if session:
            session.last_accessed = datetime.now()
        return session

    def get_step_generation_lock(self, session_id):
        """获取当前会话的步骤生成锁；延迟创建以兼容恢复的会话。"""
        lock = self.step_generation_locks.get(session_id)
        if lock is None:
            lock = asyncio.Lock()
            self.step_generation_locks[session_id] = lock
        return lock
    
    def delete_session(self, session_id):
        if session_id in self.sessions:
            del self.sessions[session_id]
            self.step_generation_locks.pop(session_id, None)
            return True
        return False
    
    def get_all_sessions(self):
        return [
            {
                "session_id": session.session_id,
                "question": _learner_question(session),
                "type": session.session_type,
                "created_at": session.created_at.isoformat(),
                "last_accessed": session.last_accessed.isoformat()
            }
            for session in self.sessions.values()
        ]
    
    def cleanup_expired_sessions(self, max_age_hours=240):
        """清理过期会话"""
        cutoff = datetime.now() - timedelta(hours=max_age_hours)
        expired_ids = [
            session_id for session_id, session in self.sessions.items()
            if session.last_accessed < cutoff
        ]
        for session_id in expired_ids:
            del self.sessions[session_id]
            self.step_generation_locks.pop(session_id, None)
        return len(expired_ids)

class EduPlatform:
    def __init__(self):
        self.session_manager = SessionManager()         

# Pydantic模型定义
class ImgRequest(BaseModel):
    imgStr: str
    userText: str

class ImgResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[Dict[str, str]] = None

class Img2CodeRequest(BaseModel):
    imgStr: str
    userText: Optional[str] = None

class Img2CodeResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[Dict[str, List[str]]] = None

class CorrectifyCodeRequest(BaseModel):
    code: str
    errorMessage: Optional[List[str]]
    userText: Optional[str] = None

class CorrectifyCodeResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[Dict[str, List[str]]] = None

class ImageData(BaseModel):
    name: str
    url: str  # base64String形式的图片URL

class InitRequest(BaseModel):
    processedQuestion: str
    displayQuestion: Optional[str] = None
    userInput: Optional[str] = None
    personalizedMode: Optional[bool] = None
    images: Optional[List[ImageData]] = None
    imageCode: Optional[List[str]] = None
    userId: Optional[str] = "default"
    adaptiveMode: Optional[bool] = None
    crossSessionEnabled: Optional[bool] = None
    persistLearnerState: Optional[bool] = None
    experimentRunId: Optional[str] = None
    learnerStateScope: Optional[str] = None
    topic: Optional[str] = None
    objectives: Optional[List[str]] = None
    pretestSummary: Optional[Dict[str, bool]] = None
    priorRoundState: Optional[List[Dict[str, Any]]] = None
    interactionLimit: Optional[int] = None
    forceCoverageOnFinalTurn: Optional[bool] = None

class KnowledgePoint(BaseModel):
    title: str
    description: str

class CoreKnowledgePoint(BaseModel):
    id: int
    name: str
    type: str
    level: str
    content: str

class ProblemSolvingHint(BaseModel):
    basic_idea: str
    skill_enlightenment: str
    core_mathematical_thought: str
    related_method: str

class LearningPathStep(BaseModel):
    step_number: int
    title: str
    knowledge_points: List[str]
    connection: str
    assessment_focus: str

class StepNodeData(BaseModel):
    step_number: int
    analysis: str
    operation_prefix: str
    options: List[str]
    operation_summary_list: List[str] = []
    user_selected_option: str = ""

class StepOptionRequest(BaseModel):
    code: int
    message: Optional[str] = None
    data: StepNodeData

class StepOption(BaseModel):
    session_id: str
    option: str
    behavior_data: Optional[Dict[str, Any]] = None

class AdvanceExplanationRequest(BaseModel):
    session_id: str

class WrongAnswer(BaseModel):
    step_number: str
    operation: str
    wrong_option: str

class InitData(BaseModel):
    session_id: str
    question: str
    images: Optional[List[ImageData]] = None
    imageCode: Optional[List[str]] = None
    current_step: int
    total_steps: int
    in_summary: bool
    knowledge_points: List[KnowledgePoint]
    core_knowledge: List[CoreKnowledgePoint] = Field(default_factory=list)
    problem_solving_hint: Optional[ProblemSolvingHint] = None
    learning_path: List[LearningPathStep] = Field(default_factory=list)
    steps_dict: Dict[str, str]
    steps_data: Optional[Dict[str, Dict[str, str]]] 
    steps_op_prefix_dic: Dict[str, str]

class InitState(BaseModel):
    code: int
    message: Optional[str] = None
    data: InitData

class SessionData(BaseModel):
    session_id: str
    question: str
    images: Optional[List[ImageData]] = None
    imageCode: Optional[List[str]] = None
    current_step: int
    total_steps: int
    in_summary: bool
    knowledge_points: List[KnowledgePoint]
    core_knowledge: List[CoreKnowledgePoint] = Field(default_factory=list)
    problem_solving_hint: Optional[ProblemSolvingHint] = None
    learning_path: List[LearningPathStep] = Field(default_factory=list)
    wrong_answers: List[WrongAnswer]
    steps_info: List[StepNodeData]
    steps_history: List[StepNodeData] = []
    steps_dict: Dict[str, str]
    steps_data: Optional[Dict[str, Dict[str, str]]] = None
    steps_op_prefix_dic: Dict[str, str]
    personalized_mode: bool = False
    adaptive_mode: bool = False
    cross_session_enabled: bool = False
    persist_learner_state: bool = False
    experiment_run_id: Optional[str] = None
    user_id: Optional[str] = "default"
    current_step_true_answer_index: int = 0
    learner_state_status: str = "NOT_APPLICABLE"
    learner_state_error: str = ""
    current_answer_result: Optional[Dict[str, Any]] = None
    answer_results: Dict[str, Dict[str, Any]] = Field(default_factory=dict)
    awaiting_next: bool = False

class SessionState(BaseModel):
    code: int
    message: Optional[str] = None
    data: SessionData

class UpdateState(BaseModel):
    session_id: str
    imageCode: Optional[List[str]] = None
    current_step: int
    in_summary: bool
    wrong_answers: List[WrongAnswer]

class SessionListItem(BaseModel):
    session_id: str
    question: str
    type: str  
    created_at: str
    last_accessed: str

class SessionListResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: List[SessionListItem]

class CheckData(BaseModel):
    session_id: str
    question: str
    current_step: int
    total_steps: int
    in_summary: bool
    knowledge_points: List[KnowledgePoint]
    core_knowledge: List[CoreKnowledgePoint] = Field(default_factory=list)
    problem_solving_hint: Optional[ProblemSolvingHint] = None
    learning_path: List[LearningPathStep] = Field(default_factory=list)
    wrong_answers: List[WrongAnswer]
    error_analysis: str
    is_correct: bool
    selected_option: str
    correct_option: str
    answer_explanation: str
    awaiting_next: bool
    learner_state_status: str = "NOT_APPLICABLE"
    learner_state_error: str = ""

class CheckState(BaseModel):
    code: int
    message: Optional[str] = None
    data: CheckData

class StepHistoryRequest(BaseModel):
    session_id: str
    steps_history: List[StepNodeData]

class StepHistoryResponse(BaseModel):
    code: int
    message: str
    data: Optional[Any] = None

class DeleteSessionResponse(BaseModel):
    code: int
    message: str
    data: Optional[Any] = None

class RestoreSessionData(BaseModel):
    session_id: str

class RestoreSessionRequest(BaseModel):
    java_session_id: str
    snapshot: Dict[str, Any]
    user_id: Optional[str] = None

class RestoreSessionResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: RestoreSessionData

class CheckInputRequest(BaseModel):
    user_input: str

class CheckInputData(BaseModel):
    question: str
    user_input: str

class CheckInputResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[CheckInputData] = None

class DirectInitRequest(BaseModel):
    question: str
    user_id: Optional[str] = "default"
    experiment_run_id: Optional[str] = None

class DirectFollowupRequest(BaseModel):
    session_id: str
    message: str

class DirectRestoreRequest(BaseModel):
    java_session_id: str
    snapshot: Dict[str, Any]
    user_id: Optional[str] = None

# 初始化教育平台实例
edu_platform = EduPlatform()
session_manager = edu_platform.session_manager

@app.post("/api/v1/direct/init")
@session_operation
async def init_direct_session(request: DirectInitRequest):
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="题目不能为空")
    session = session_manager.create_session("direct", question, user_id=request.user_id or "default")
    session.experiment_run_id = request.experiment_run_id
    prompt = (
        "你是一名严谨的通用学习辅导者。请对下面这项预先指定的学习任务直接给出完整讲解。"
        "根据任务类型提供必要的概念、事实、步骤、理由、证据或操作，并包含一次自检；"
        "开放任务应说明评价标准，事实不确定时应明确待核实内容。不要询问学习者改题，也不要推荐其他任务。"
        "只输出对学习者的教学内容；不得复述任务中的工作方式、范围限制、资料来源或其他内部要求。"
        "不要输出 Markdown 图片、HTML 图片或外部图片链接；如需图示，请使用纯文本步骤说明。\n\n"
        f"学习任务：{question}"
    )
    explanation = _direct_text_only(await call_llm_async(prompt, model_type="instruct"))
    if not explanation:
        session_manager.delete_session(session.session_id)
        raise HTTPException(status_code=500, detail="讲解生成失败")
    timestamp = datetime.now().isoformat()
    session.messages = [
        {"role": "user", "content": question, "time": timestamp, "kind": "assigned_question"},
        {"role": "assistant", "content": explanation, "time": timestamp, "kind": "initial_explanation"},
    ]
    session.initial_explanation_generated = True
    return {"code": 200, "message": "success", "data": _direct_session_data(session)}

@app.post("/api/v1/direct/followup")
@session_operation
async def direct_followup(request: DirectFollowupRequest):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type != "direct":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    message = request.message.strip()
    if not message:
        raise HTTPException(status_code=400, detail="追问不能为空")
    history = "\n".join(f"{item['role']}: {item['content']}" for item in session.messages[-8:])
    prompt = ("请只围绕指定学习任务回答学习者追问，保持已确认事实和标准一致，不要引入无关任务。\n"
              f"指定学习任务：{session.question}\n对话：\n{history}\n学习者：{message}")
    reply = _direct_text_only(await call_llm_async(prompt, model_type="instruct"))
    if not reply:
        raise HTTPException(status_code=503, detail="追问生成失败，请稍后重试")
    timestamp = datetime.now().isoformat()
    session.messages.append({"role": "user", "content": message, "time": timestamp, "kind": "followup"})
    session.messages.append({"role": "assistant", "content": reply, "time": timestamp, "kind": "followup_reply"})
    return {"code": 200, "message": "success", "data": _direct_session_data(session)}

@app.get("/api/v1/direct/sessionstate/{session_id}")
@session_operation
async def direct_session_state(session_id: str):
    session = session_manager.get_session(session_id)
    if not session or session.session_type != "direct":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    return {"code": 200, "message": "success", "data": _direct_session_data(session)}

@app.post("/api/v1/direct/restoresession")
@session_operation
async def restore_direct_session(request: DirectRestoreRequest):
    snapshot = request.snapshot or {}
    session = session_manager.create_session("direct", str(snapshot.get("question", "")),
                                             user_id=request.user_id or str(snapshot.get("user_id", "default")))
    session.messages = _as_list(snapshot.get("messages"))
    session.initial_explanation_generated = _safe_bool(snapshot.get("initial_explanation_generated"), bool(session.messages))
    session.experiment_run_id = snapshot.get("experiment_run_id")
    return {"code": 200, "message": "session restored", "data": {"session_id": session.session_id}}

# 通用接口：分析输入是否合法、图片识别问题、图片转代码

@app.post("/api/v1/checkinput", response_model=CheckInputResponse)
@session_operation
async def check_input(request: CheckInputRequest):

    prompt = CHECK_INPUT_PROMPT.format(user_input=request.user_input)
    llm_response = await call_llm_async(prompt, model_type="small")

    if not llm_response:
        raise HTTPException(status_code=500, detail="LLM 服务不可用")

    try:
        # 兼容代码块包裹的 JSON
        if "```json" in llm_response:
            json_start = llm_response.find("```json") + 7
            json_end = llm_response.find("```", json_start)
            json_str = llm_response[json_start:json_end].strip()
        elif "{" in llm_response and "}" in llm_response:
            json_start = llm_response.find("{")
            json_end = llm_response.rfind("}") + 1
            json_str = llm_response[json_start:json_end]
        else:
            json_str = llm_response

        parsed = json.loads(json_str)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=500, detail=f"LLM 响应解析失败：{str(e)}")

    if not parsed.get("valid", False):
        return CheckInputResponse(code=400, message="输入内容不合法")

    return CheckInputResponse(
        code=200,
        data=CheckInputData(
            question=parsed.get("question", ""),
            user_input=parsed.get("user_input", "")
        )
    )


@app.post("/api/v1/imginput", response_model=ImgResponse)
@session_operation
async def img_input(request: ImgRequest):
    from tools.llm_api_service import call_llm_vl
    if not os.getenv("VLLM_MODEL_VL", "").strip():
        raise HTTPException(status_code=503, detail="未配置本地视觉模型，Qwen3-32B 不支持图片输入")
    
    # 处理图像输入
    try:
        img_base64 = request.imgStr
        llm_response = await run_blocking(call_llm_vl, img_base64, type="vl", prompt = IMAGE_INPUT_PROMPT)
        if not llm_response:
            raise HTTPException(status_code=500, detail="本地视觉模型服务不可用")
        # 安全地解析JSON响应
        try:
            # 处理可能的代码块格式
            if "```json" in llm_response:
                json_start = llm_response.find("```json") + 7
                json_end = llm_response.find("```", json_start)
                json_str = llm_response[json_start:json_end].strip()
            elif "```" in llm_response and "{" in llm_response:
                # 处理其他代码块格式
                lines = llm_response.split('\n')
                json_lines = []
                in_json = False
                for line in lines:
                    if '{' in line and not in_json:
                        in_json = True
                    if in_json:
                        json_lines.append(line)
                    if '}' in line and in_json:
                        break
                json_str = '\n'.join(json_lines)
            elif "{" in llm_response and "}" in llm_response:
                # 直接提取JSON部分
                json_start = llm_response.find("{")
                json_end = llm_response.rfind("}") + 1
                json_str = llm_response[json_start:json_end]
            else:
                # 如果没有找到JSON格式，尝试直接解析
                json_str = llm_response
            
            parsed_content = json.loads(json_str)
            if parsed_content.get("status") == "error":
                error_message = parsed_content.get("message", "未知错误")
                raise HTTPException(status_code=400, detail=f"图像内容解析失败：{error_message}")
            parsed_content["extracted_text"] = parsed_content["extracted_text"].replace('\n\n', '\\newline')
            return {
                "code": 200,
                "message": "success",
                "data": parsed_content
            }
        except json.JSONDecodeError as e:
            print(f"JSON解析错误: {e}")
            print(f"原始响应: {llm_response}")
            raise HTTPException(status_code=500, detail=f"图像内容解析失败：JSON格式错误 - {str(e)}")

    except HTTPException:
        raise
    except Exception as e:
        print(f"图像输入处理异常: {str(e)}")
        raise HTTPException(status_code=500, detail="图像输入处理失败")


@app.post("/api/v1/imgtocode", response_model=Img2CodeResponse)
@session_operation
async def img_input(request: ImgRequest):
    from tools.llm_api_service import call_llm_vl
    if not os.getenv("VLLM_MODEL_VL", "").strip():
        raise HTTPException(status_code=503, detail="未配置本地视觉模型，Qwen3-32B 不支持图片输入")

    response = await run_blocking(call_llm_vl, request.imgStr, type="vl", system_prompt = GEOGRABRA_SYSTEM_PROMPT, prompt = "请使用 GeoGebra 中的相关命令，以交互方式重现此图像中的几何图形或函数图像。仅输出 JSON 数组。")

    try:
        # 有时 LLM 可能会把数组包在一个对象里，比如 {"commands": [...]}，
        # 需要兼容处理。Prompt 已经强调了要raw array
        commands_data = json.loads(response)
        
        if isinstance(commands_data, list):
            commands = commands_data
        elif isinstance(commands_data, dict) and "commands" in commands_data:
            commands = commands_data["commands"]
        else:
            commands = []

        # 过滤掉空的字符串
        commands = [cmd.strip() for cmd in commands if cmd and isinstance(cmd, str)]
        
        return {
            "code": 200,
            "message": "success",
            "data": {
                "commands": commands
            }
        }

    except json.JSONDecodeError:
        print("解析 AI 响应 JSON 失败。")
        return JSONResponse(status_code=500, content={"error": "AI returned invalid JSON format."})

async def _apply_explanation_knowledge_analysis(explanation_session, response: str, question: str) -> None:
    try:
        explanation_session.parse_knowledge_point(response)
        return
    except KnowledgeAnalysisError as first_error:
        logger.warning("知识点结构校验失败，尝试纠正: %s", first_error)
        validation_error = first_error

    repair_prompt = build_repair_prompt(question, response, validation_error)
    repaired_response = ""
    try:
        repaired_response = await explanation_session.call_llm_async(repair_prompt)
        explanation_session.parse_knowledge_point(repaired_response)
        return
    except Exception as final_error:
        logger.warning("知识点纠正仍失败，降级为可用知识点: %s", final_error)

    explanation_session.core_knowledge = (
        salvage_core_knowledge(repaired_response)
        or salvage_core_knowledge(response)
    )
    explanation_session.problem_solving_hint = None
    explanation_session.knowledge_dict = knowledge_dict_from_core(
        explanation_session.core_knowledge
    )
    if not explanation_session.knowledge_dict:
        explanation_session.knowledge_dict = (
            legacy_knowledge_dict(repaired_response)
            or legacy_knowledge_dict(response)
        )


# 初始化API，返回问题相关知识点和解题步骤
@app.post("/api/v1/explanation/init", response_model=InitState)
@session_operation
async def init_explanation_session(request: InitRequest):
    t_start = time.time()
    print(f"[INIT] 收到请求，时间: {datetime.now().strftime('%H:%M:%S.%f')[:-3]}")

    session = session_manager.create_session("explanation", request.processedQuestion, user_id=request.userId or "default")
    explanationSession = session
    explanationSession.question = request.processedQuestion
    explanationSession.learner_question = request.displayQuestion or request.processedQuestion
    explanationSession.images = [img.dict() for img in request.images] if request.images else []
    explanationSession.imageCode = request.imageCode if request.imageCode else []
    explanationSession.personalized_mode = request.personalizedMode if request.personalizedMode is not None else False
    explanationSession.adaptive_mode = request.adaptiveMode if request.adaptiveMode is not None else True
    explanationSession.cross_session_enabled = request.crossSessionEnabled if request.crossSessionEnabled is not None else False
    explanationSession.persist_learner_state = request.persistLearnerState if request.persistLearnerState is not None else False
    explanationSession.experiment_run_id = request.experimentRunId
    explanationSession.learner_state_scope = request.learnerStateScope
    explanationSession.experiment_topic = request.topic
    explanationSession.experiment_objectives = request.objectives or []
    explanationSession.pretest_summary = request.pretestSummary or {}
    explanationSession.prior_round_state = request.priorRoundState or []
    explanationSession.interaction_limit = request.interactionLimit
    explanationSession.force_coverage_on_final_turn = bool(request.forceCoverageOnFinalTurn)
    explanationSession.learner_state_status = "PENDING" if explanationSession.persist_learner_state else "NOT_APPLICABLE"

    # 如果有用户输入  
    if request.userInput:
        knowledge_point_prompt = EXTRACT_KNOWLEDGE_POINTS_PROMPT.format(question=request.processedQuestion)
        parse_user_idea_prompt = PARSE_USER_IDEA_PROMPT.format(question=request.processedQuestion, user_input=request.userInput)

        # 并行提速（AsyncOpenAI 内置 async IO）
        t0 = time.time()
        print(f"[INIT] 开始并行调用: 知识点提取 + 用户意图解析 (+{t0 - t_start:.2f}s)")
        knowledge_point_response, user_idea_response = await asyncio.gather(
            call_llm_async(knowledge_point_prompt),
            call_llm_async(parse_user_idea_prompt),
        )
        print(f"[INIT] 并行调用完成，耗时 {time.time() - t0:.2f}s")

        # 分析知识点
        if not knowledge_point_response:
            session_manager.delete_session(session.session_id)
            raise HTTPException(status_code=500, detail="知识点分析失败")
        await _apply_explanation_knowledge_analysis(
            explanationSession,
            knowledge_point_response,
            request.processedQuestion,
        )

        # 分析用户意图
        if not user_idea_response:
            session_manager.delete_session(session.session_id)
            raise HTTPException(status_code=500, detail="用户意图分析失败")
        
        # 安全地解析JSON响应
        try:
            raw = user_idea_response

            if "```json" in raw:
                json_start = raw.find("```json") + 7
                json_end = raw.find("```", json_start)
                json_str = raw[json_start:json_end].strip()
            elif "```" in raw and "{" in raw:
                lines = raw.split('\n')
                json_lines = []
                in_json = False
                for line in lines:
                    if '{' in line and not in_json:
                        in_json = True
                    if in_json:
                        json_lines.append(line)
                    if '}' in line and in_json:
                        break
                json_str = '\n'.join(json_lines)
            elif "{" in raw and "}" in raw:
                json_start = raw.find("{")
                json_end = raw.rfind("}") + 1
                json_str = raw[json_start:json_end]
            else:
                json_str = raw

            parsed_content = json.loads(json_str)

        except json.JSONDecodeError as e:
            print(f"JSON解析错误: {e}")
            print(f"原始响应: {user_idea_response}")
            session_manager.delete_session(session.session_id)
            raise HTTPException(status_code=500, detail=f"用户意图解析失败：JSON格式错误 - {str(e)}")
        except Exception as e:
            print(f"解析异常: {e}")
            print(f"原始响应: {user_idea_response}")
            session_manager.delete_session(session.session_id)
            raise HTTPException(status_code=500, detail=f"用户意图解析失败：{str(e)}")

        # 结合用户意图生成解题步骤
        t0 = time.time()
        print(f"[INIT] 开始调用: 用户意图 → 解题步骤 (+{t0 - t_start:.2f}s)")
        prompt = ANALYZE_USER_INTENT_PROMPT.format(question=request.processedQuestion, user_input=parsed_content)
        if explanationSession.cross_session_enabled:
            await run_blocking(explanationSession.build_personalized_system_prompt)
        llm_response = await explanationSession.call_llm_async(prompt)
        print(llm_response)
        print(f"[INIT] 解题步骤生成完成，耗时 {time.time() - t0:.2f}s")
        if not llm_response:
            session_manager.delete_session(session.session_id)
            raise HTTPException(status_code=500, detail="步骤解析失败")
        explanationSession.steps_dict = explanationSession.parse_solving_steps(llm_response)
        explanationSession.steps_data = explanationSession.parse_total_steps(explanationSession.steps_dict)
        explanationSession.learning_path = parse_learning_path(explanationSession.steps_data)
        print("步骤解析完成")
    else:     
        knowledge_point_prompt = EXTRACT_KNOWLEDGE_POINTS_PROMPT.format(question=request.processedQuestion)
        solving_steps_prompt = GENERATE_STEPS_PROMPT.format(question=request.processedQuestion)

        # 并行提速（AsyncOpenAI 内置 async IO）
        if not explanationSession.cross_session_enabled:
            t0 = time.time()
            print(f"[INIT] 开始并行调用: 知识点提取 + 解题步骤生成 (+{t0 - t_start:.2f}s)")
            knowledge_point_response, solving_steps_response = await asyncio.gather(
                explanationSession.call_llm_async(knowledge_point_prompt),
                explanationSession.call_llm_async(solving_steps_prompt),
            )
            print(f"[INIT] 并行调用完成，耗时 {time.time() - t0:.2f}s")
        else:
            knowledge_point_response = await explanationSession.call_llm_async(knowledge_point_prompt)
            await run_blocking(explanationSession.build_personalized_system_prompt)
            solving_steps_response = await explanationSession.call_llm_async(solving_steps_prompt)
        
        # 处理知识点分析结果
        if not knowledge_point_response:
            session_manager.delete_session(session.session_id)
            raise HTTPException(status_code=500, detail="知识点分析失败")
        await _apply_explanation_knowledge_analysis(
            explanationSession,
            knowledge_point_response,
            request.processedQuestion,
        )
        print("知识点解析完成")
        
        # 处理解题步骤结果
        max_retries = 3
        for attempt in range(1, max_retries + 1):
            if not solving_steps_response:
                if attempt < max_retries:
                    print(f"[INIT] 步骤生成返回空，第 {attempt} 次重试...")
                    solving_steps_response = await explanationSession.call_llm_async(solving_steps_prompt)
                    continue
                session_manager.delete_session(session.session_id)
                raise HTTPException(status_code=500, detail="步骤解析失败")
            try:
                explanationSession.steps_dict = explanationSession.parse_solving_steps(solving_steps_response)
                explanationSession.steps_data = explanationSession.parse_total_steps(explanationSession.steps_dict)
                explanationSession.learning_path = parse_learning_path(explanationSession.steps_data)
                print(f"步骤解析完成（第 {attempt} 次尝试）")
                break
            except (ValueError, LearningPathError) as e:
                print(f"[INIT] parse_solving_steps 失败（第 {attempt} 次）: {e}")
                if attempt < max_retries:
                    print(f"[INIT] 重新调用 LLM 生成步骤，第 {attempt + 1} 次尝试...")
                    solving_steps_response = await explanationSession.call_llm_async(solving_steps_prompt)
                else:
                    session_manager.delete_session(session.session_id)
                    raise HTTPException(status_code=500, detail=f"步骤解析失败（已重试 {max_retries} 次）: {e}")

    # 初始化状态
    explanationSession.current_step = 1
    explanationSession.step_count = len(explanationSession.steps_dict)
    explanationSession.in_summary = False

    steps_op_prefix_dic = {
        f"步骤{item['step_number']}": item["title"]
        for item in explanationSession.learning_path
    }

    explanationSession.steps_op_prefix_dic = steps_op_prefix_dic

    print(f"[INIT] 全部完成，总耗时 {time.time() - t_start:.2f}s")

    # 准备响应数据
    knowledge_points = [
        {"title": k, "description": v}
        for k, v in explanationSession.knowledge_dict.items()
    ]
    
    return {
        "code": 200,
        "message": "success",
        "data": {
            "session_id": session.session_id,
            "question": _learner_question(explanationSession),
            "images": explanationSession.images,
            "imageCode": explanationSession.imageCode,
            "current_step": explanationSession.current_step,
            "total_steps": explanationSession.step_count,
            "in_summary": explanationSession.in_summary,
            "knowledge_points": knowledge_points,
            "core_knowledge": explanationSession.core_knowledge,
            "problem_solving_hint": explanationSession.problem_solving_hint,
            "learning_path": explanationSession.learning_path,
            "steps_dict": explanationSession.steps_dict,
            "steps_data": explanationSession.steps_data,
            "steps_op_prefix_dic": steps_op_prefix_dic
        }
    }

# 获取会话列表
@app.get("/api/v1/sessions", response_model=SessionListResponse)
@session_operation
async def get_session_list():
    session_list = session_manager.get_all_sessions()
    
    return {
        "code": 200,
        "message": "success",
        "data": session_list
    }

# 返回当前步骤的分析和选项
@app.get("/api/v1/explanation/getstep/{session_id}", response_model=StepOptionRequest)
@session_operation
async def get_current_step(session_id: str):
    session = session_manager.get_session(session_id)
    if not session or session.session_type != "explanation":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    explanationSession = session

    if explanationSession.current_step not in range(1, explanationSession.step_count + 1):
        raise HTTPException(status_code=400, detail="无效的步骤")

    step_number = explanationSession.current_step
    step_nodes = (
        explanationSession.steps_history
        if explanationSession.in_summary
        else explanationSession.steps_info
    )

    def find_cached_step():
        return next(
            (node for node in reversed(step_nodes) if node.step_number == step_number),
            None
        )

    def build_response(step_node):
        return {
            "code": 200,
            "message": "success",
            "data": step_node.model_dump()
        }

    # 快速路径：相同步骤已生成时直接复用，页面刷新不会再次调用 LLM。
    cached_step = find_cached_step()
    if cached_step is not None:
        return build_response(cached_step)

    generation_lock = session_manager.get_step_generation_lock(session_id)
    async with generation_lock:
        # 获取锁后再次检查，避免并发请求排队后重复生成相同步骤。
        cached_step = find_cached_step()
        if cached_step is not None:
            return build_response(cached_step)

        current_step_key = f"步骤{step_number}"
        step_info = explanationSession.steps_data.get(current_step_key, {})
        outline_analysis = step_info.get("分析与提示", "")
        operation = step_info.get("具体操作", "")
        operation_summary_list = []

        if explanationSession.in_summary:
            operation_summary = step_info.get("操作总结", "")
            pattern = r'([一二三四五六七八九十]+、|\d+\.)\s*(.*?)(?=(?:[一二三四五六七八九十]+、|\d+\.|$))'
            matches = re.findall(pattern, operation_summary, re.DOTALL)
            for match in matches:
                content = match[1].strip()
                content = content.rstrip('；;。').strip()
                operation_summary_list.append(content)

            if not operation_summary_list:
                operation_summary_list = [
                    part.strip().rstrip('；;。').strip()
                    for part in re.split(r'[；。]', operation_summary)
                    if part.strip()
                ]

        try:
            async with asyncio.timeout(STEP_GENERATION_TIMEOUT_SECONDS):
                learning_step = next(
                    (item for item in explanationSession.learning_path if item["step_number"] == step_number),
                    {
                        "step_number": step_number,
                        "title": explanationSession.steps_op_prefix_dic.get(current_step_key, ""),
                        "knowledge_points": [],
                        "connection": "",
                        "assessment_focus": operation,
                    },
                )
                previous_lessons = "\n\n".join(
                    f"步骤 {node.step_number}（{node.operation_prefix}）：\n{node.analysis}"
                    for node in step_nodes
                    if node.step_number < step_number
                ) or "暂无前序步骤，这是第一个学习阶段。"
                previous_assessments = "\n\n".join(
                    f"步骤 {item['step_number']} 检验重点：{item['assessment_focus']}\n"
                    f"已生成选项：{'；'.join(next((node.options for node in step_nodes if node.step_number == item['step_number']), []))}"
                    for item in explanationSession.learning_path
                    if item["step_number"] < step_number
                ) or "暂无前序检验记录"
                operation_prefix, analysis = await explanationSession.generate_step_lesson_async(
                    outline_analysis,
                    operation,
                    previous_lessons,
                    learning_step,
                )
                if explanationSession.adaptive_mode:
                    option_list = await explanationSession.generate_personalized_options_async(
                        analysis,
                        operation,
                        learning_step.get("assessment_focus", operation),
                        previous_assessments,
                    )
                else:
                    option_list = await explanationSession.generate_options_async(
                        analysis,
                        operation,
                        learning_step.get("assessment_focus", operation),
                        previous_assessments,
                    )
        except TimeoutError as exc:
            logger.error(
                "Step generation timed out session_id=%s step=%s timeout_seconds=%s",
                session_id,
                step_number,
                STEP_GENERATION_TIMEOUT_SECONDS,
            )
            raise HTTPException(status_code=504, detail="步骤生成超时，请稍后重试") from exc

        if not option_list:
            raise HTTPException(status_code=500, detail="选项生成失败")

        option_list, true_answer_index = explanationSession.move_first_to_random_position(option_list)
        explanationSession.current_step_true_answer_index = true_answer_index

        step_node = StepNodeData(
            step_number=step_number,
            analysis=analysis,
            operation_prefix=operation_prefix,
            options=option_list,
            operation_summary_list=operation_summary_list,
            user_selected_option=""
        )
        step_nodes.append(step_node)
        return build_response(step_node)

# 提交用户答案并返回判定与解析；步骤推进由 /advance 单独触发
@app.post("/api/v1/explanation/commitanswer", response_model=CheckState)
@session_operation
async def submit_answer(request: StepOption):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type != "explanation":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    explanationSession = session

    if explanationSession.current_answer_result:
        saved = explanationSession.current_answer_result
        if saved.get("selected_option") != request.option.strip().upper():
            raise HTTPException(status_code=409, detail="当前检验题已经提交，请进入下一步")
        knowledge_points = [
            {"title": k, "description": v}
            for k, v in explanationSession.knowledge_dict.items()
        ]
        return {
            "code": 200,
            "message": "success",
            "data": {
                "session_id": request.session_id,
                "question": _learner_question(explanationSession),
                "current_step": explanationSession.current_step,
                "total_steps": explanationSession.step_count,
                "in_summary": explanationSession.in_summary,
                "knowledge_points": knowledge_points,
                "core_knowledge": explanationSession.core_knowledge,
                "problem_solving_hint": explanationSession.problem_solving_hint,
                "learning_path": explanationSession.learning_path,
                "wrong_answers": explanationSession.wrong_answer_list,
                "error_analysis": saved["answer_explanation"] if not saved["is_correct"] else "True",
                **saved,
                "learner_state_status": explanationSession.learner_state_status,
                "learner_state_error": explanationSession.learner_state_error,
                "current_answer_result": explanationSession.current_answer_result,
                "answer_results": explanationSession.answer_results,
            },
        }

    # 验证输入格式
    user_input = request.option.strip().upper()
    if len(user_input) != 1 or not user_input.isalpha():
        raise HTTPException(status_code=400, detail="无效的选项格式")
    
    # 转换选项为索引 (A->0, B->1)
    user_index = ord(user_input) - 65

    # 从已存储的步骤数据中获取 analysis、operation 和 option_list，避免重新生成
    current_step_key = f"步骤{explanationSession.current_step}"
    step_data_dict = explanationSession.steps_data.get(current_step_key, {})
    analysis = step_data_dict.get("分析与提示", "")
    operation = step_data_dict.get("具体操作", "")

    if explanationSession.in_summary:
        step_node = next(
            (s for s in reversed(explanationSession.steps_history)
             if s.step_number == explanationSession.current_step),
            None
        )
    else:
        step_node = next(
            (s for s in reversed(explanationSession.steps_info)
             if s.step_number == explanationSession.current_step),
            None
        )

    if step_node is None:
        raise HTTPException(status_code=400, detail="步骤数据不存在，请先调用 getstep 获取步骤")

    analysis = step_node.analysis
    option_list = step_node.options

    if not option_list or user_index >= len(option_list):
        raise HTTPException(status_code=400, detail="无效的选项索引")

    is_correct = (user_index == explanationSession.current_step_true_answer_index)
    step_node.user_selected_option = user_input

    # 记录本轮交互历史：assistant 包含分析+选项列表，user 包含所选选项字母及对应文本
    option_letters = [chr(65 + i) for i in range(len(option_list))]
    options_text = "\n".join(f"{letter}. {opt}" for letter, opt in zip(option_letters, option_list))
    explanationSession.history.append({
        "role": "assistant",
        "content": f"{analysis}\n\n{options_text}"
    })
    explanationSession.history.append({
        "role": "user",
        "content": f"{user_input}. {option_list[user_index]}"
    })

    user_feedback_text = f"{user_input}. {option_list[user_index]}"
    # 当前步骤上下文：分析提示 + 完整 A/B/C/D 选项文本，与展示给学生的内容一致
    current_step_context = f"{analysis}\n\n{options_text}"

    # 行为数据只由 Full/个性化模式采集和分析。缺失或分析失败不能阻断答题。
    behavior_result = None
    if explanationSession.personalized_mode:
        event_stream = request.behavior_data.get("event_stream") if isinstance(request.behavior_data, dict) else None
        if event_stream:
            try:
                behavior_result = await run_blocking(explanationSession.process_behavior_data,
                    request.behavior_data, user_feedback_text, current_step_context
                )
            except Exception:
                logger.exception(
                    "Behavior analysis failed; continuing without behavior result "
                    "session_id=%s user_id=%s step=%s",
                    explanationSession.session_id,
                    explanationSession.user_id,
                    explanationSession.current_step,
                )
        else:
            logger.warning(
                "Behavior data missing or empty; continuing without behavior result "
                "session_id=%s user_id=%s step=%s",
                explanationSession.session_id,
                explanationSession.user_id,
                explanationSession.current_step,
            )

    # 自适应模式负责当前轮标注与后续教学调整，与行为数据采集开关相互独立。
    if explanationSession.adaptive_mode:
        # 标注当前步骤相关知识点（对错已由选项比对确定，无需调用annotate_interaction）
        from KT.ours.annotate import annotate_kc
        conversation = (
            f"题目: {explanationSession.question}\n"
            f"教师: {current_step_context}\n"
            f"学生: {user_feedback_text}"
        )
        knowledge_points_annotation = await run_blocking(annotate_kc, conversation)

        # 保存标注结果和认知负荷分析到session
        round_record = {
            "round": len(explanationSession.annotation_history) + 1,
            "step": current_step_context,
            "user_feedback": user_feedback_text,
            "correctness": "true" if is_correct else "false",
            "knowledge_points": knowledge_points_annotation,
            "cognitive_load_level": behavior_result.get("cognitive_load_level", "") if behavior_result else "",
            "kt_value": behavior_result.get("kt_value", "") if behavior_result else "",
            "correctness_analysis": behavior_result.get("correctness_analysis", "") if behavior_result else "",
            "behavior_analysis": behavior_result.get("reasoning_process", "") if behavior_result else "",
        }
        explanationSession.annotation_history.append(round_record)
        explanationSession.conversations.append({
            "round": round_record["round"],
            "dialogue": {"step": current_step_context, "user_feedback": user_feedback_text},
            "annotation": round_record,
        })

    
    if is_correct:
        correct_answer_text = option_list[explanationSession.current_step_true_answer_index]
        answer_explanation = await run_blocking(explanationSession.correct_selection_analysis,
            analysis,
            correct_answer_text,
        )
    else:
        wrong_item = {
            "step_number": f"步骤{explanationSession.current_step}",
            "operation": operation,
            "wrong_option": option_list[user_index]
        }
        explanationSession.wrong_answer_list.append(wrong_item)
        correct_answer_text = option_list[explanationSession.current_step_true_answer_index]
        answer_explanation = await run_blocking(explanationSession.incorrect_selection_analysis,
            analysis, correct_answer_text, option_list[user_index]
        )

    correct_option = chr(65 + explanationSession.current_step_true_answer_index)
    answer_result = {
        "is_correct": is_correct,
        "selected_option": user_input,
        "correct_option": correct_option,
        "answer_explanation": answer_explanation,
        "awaiting_next": True,
    }
    explanationSession.current_answer_result = answer_result
    explanationSession.answer_results[str(explanationSession.current_step)] = answer_result.copy()

    # 准备响应数据
    knowledge_points = [
        {"title": k, "description": v}
        for k, v in explanationSession.knowledge_dict.items()
    ]
    
    wrong_answers = [
        {
            "step_number": item["step_number"],
            "operation": item["operation"],
            "wrong_option": item["wrong_option"]
        }
        for item in explanationSession.wrong_answer_list
    ]
    
    return {
        "code": 200,
        "message": "success",
        "data": {
            "session_id": request.session_id,
            "question": _learner_question(explanationSession),
            "current_step": explanationSession.current_step,
            "total_steps": explanationSession.step_count,
            "in_summary": explanationSession.in_summary,
            "knowledge_points": knowledge_points,
            "core_knowledge": explanationSession.core_knowledge,
            "problem_solving_hint": explanationSession.problem_solving_hint,
            "learning_path": explanationSession.learning_path,
            "wrong_answers": wrong_answers,
            "error_analysis": answer_explanation if not is_correct else "True",
            **answer_result,
            "learner_state_status": explanationSession.learner_state_status,
            "learner_state_error": explanationSession.learner_state_error,
            "current_answer_result": explanationSession.current_answer_result,
            "answer_results": explanationSession.answer_results,
            "awaiting_next": bool(explanationSession.current_answer_result),
        }
    }

@app.post("/api/v1/explanation/advance")
@session_operation
async def advance_explanation(request: AdvanceExplanationRequest):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type != "explanation":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    explanationSession = session
    if not explanationSession.current_answer_result:
        raise HTTPException(status_code=409, detail="请先完成当前阶段的掌握检验")

    if explanationSession.in_summary:
        explanationSession.current_step = 0
        if explanationSession.persist_learner_state:
            await run_blocking(explanationSession.knowledge_tracing)
    else:
        explanationSession.current_step += 1
        if explanationSession.current_step == explanationSession.step_count:
            explanationSession.in_summary = True

    explanationSession.current_answer_result = None
    return await get_session_state(request.session_id)

# 更新会话状态
@app.post("/api/v1/explanation/updatestate", response_model=StepHistoryResponse)
@session_operation
async def update_session_state(request: UpdateState):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type != "explanation":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    explanationSession = session

    # 更新会话状态
    explanationSession.imageCode = request.imageCode if request.imageCode else []
    explanationSession.current_step = request.current_step
    explanationSession.in_summary = request.in_summary
    explanationSession.wrong_answers = request.wrong_answers
    
    return {
        "code": 200,
        "message": "会话状态已更新",
        "data": None
    }

# 获取会话状态
@app.get("/api/v1/explanation/sessionstate/{session_id}", response_model=SessionState)
@session_operation
async def get_session_state(session_id: str):
    session = session_manager.get_session(session_id)
    if not session or session.session_type != "explanation":
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    explanationSession = session

    knowledge_points = [
        {"title": k, "description": v}
        for k, v in explanationSession.knowledge_dict.items()
    ]
    
    wrong_answers = [
        {
            "step_number": item["step_number"],
            "operation": item["operation"],
            "wrong_option": item["wrong_option"]
        }
        for item in explanationSession.wrong_answer_list
    ]
    
    return {
        "code": 200,
        "message": "success",
        "data": {
            "session_id": session_id,
            "question": _learner_question(explanationSession),
            "personalized_mode": explanationSession.personalized_mode,
            "adaptive_mode": explanationSession.adaptive_mode,
            "cross_session_enabled": explanationSession.cross_session_enabled,
            "persist_learner_state": explanationSession.persist_learner_state,
            "experiment_run_id": explanationSession.experiment_run_id,
            "user_id": explanationSession.user_id,
            "images": explanationSession.images,
            "imageCode": explanationSession.imageCode,
            "current_step": explanationSession.current_step,
            "total_steps": explanationSession.step_count,
            "in_summary": explanationSession.in_summary,
            "knowledge_points": knowledge_points,
            "core_knowledge": explanationSession.core_knowledge,
            "problem_solving_hint": explanationSession.problem_solving_hint,
            "learning_path": explanationSession.learning_path,
            "wrong_answers": wrong_answers,
            "steps_info": explanationSession.steps_info,
            "steps_history": explanationSession.steps_history,
            "steps_dict": explanationSession.steps_dict,
            "steps_data": explanationSession.steps_data,
            "steps_op_prefix_dic": explanationSession.steps_op_prefix_dic,
            "current_step_true_answer_index": explanationSession.current_step_true_answer_index,
            "learner_state_status": explanationSession.learner_state_status,
            "learner_state_error": explanationSession.learner_state_error,
            "current_answer_result": explanationSession.current_answer_result,
            "answer_results": explanationSession.answer_results,
            "awaiting_next": bool(explanationSession.current_answer_result),
        }
    }

# 删除会话接口
@app.delete("/api/v1/deletesession/{session_id}", response_model=DeleteSessionResponse)
@session_operation
async def delete_session(session_id: str):
    if session_manager.delete_session(session_id):
        return {
            "code": 200,
            "message": "会话已成功删除",
            "data": None
        }
    else:
        raise HTTPException(status_code=404, detail="会话不存在")
    
# 功能二

# 讨论系统相关模型
class DiscussionInitRequest(BaseModel):
    processedQuestion: str
    displayQuestion: Optional[str] = None
    images: Optional[List[ImageData]] = None
    imageCode: Optional[List[str]] = None
    personalizedMode: Optional[bool] = None
    userId: Optional[str] = "default"
    adaptiveMode: Optional[bool] = None
    crossSessionEnabled: Optional[bool] = None
    persistLearnerState: Optional[bool] = None
    experimentRunId: Optional[str] = None
    learnerStateScope: Optional[str] = None
    topic: Optional[str] = None
    objectives: Optional[List[str]] = None
    pretestSummary: Optional[Dict[str, bool]] = None
    priorRoundState: Optional[List[Dict[str, Any]]] = None
    interactionLimit: Optional[int] = None
    forceCoverageOnFinalTurn: Optional[bool] = None

class DiscussionInitResponse(BaseModel):
    session_id: str
    initial_step: str
    analysis: str
    knowledge_points: List[KnowledgePoint]
    core_knowledge: List[CoreKnowledgePoint] = Field(default_factory=list)
    problem_solving_hint: Optional[ProblemSolvingHint] = None
    learning_path: List[LearningPathStep] = Field(default_factory=list)
    current_options: List[str] = Field(default_factory=list)
    current_correct_option: str = ""
    messages_history: List[MessageData]
    discussion_tree: List[StepData]

class DiscussionInitState(BaseModel):
    code: int
    message: Optional[str] = None
    data: DiscussionInitResponse

class BehaviorSessionMeta(BaseModel):
    user_id: str
    session_id: str
    current_mode: str
    current_step_id: str

class BehaviorEvent(BaseModel):
    event_type: str
    timestamp: int
    payload: Dict[str, Any] = Field(default_factory=dict)

class BehaviorData(BaseModel):
    session_meta: BehaviorSessionMeta
    event_stream: List[BehaviorEvent] = Field(default_factory=list)

class DiscussionFeedbackRequest(BaseModel):
    session_id: str
    feedback: str
    submission_type: Literal["option", "free_text"] = "free_text"
    behavior_data: Optional[BehaviorData] = None
    userId: Optional[str] = "default"

class DiscussionFeedbackResponse(BaseModel):
    status: str  # "sub_step", "main_step", "completed"
    feedback: Optional[str] = None
    step: Optional[str] = None
    summary: Optional[str] = None
    correctness: Optional[str] = None
    discussion_tree: List["StepData"] = Field(default_factory=list)
    messages_history: List["MessageData"] = Field(default_factory=list)
    current_options: List[str] = Field(default_factory=list)
    current_correct_option: str = ""
    learner_state_status: str = "NOT_APPLICABLE"
    learner_state_error: str = ""

class DiscussionFeedbackState(BaseModel):
    code: int
    message: Optional[str] = None
    data: DiscussionFeedbackResponse

class StepData(BaseModel):
    id: Union[int, str]
    title: Optional[str] = ""           
    description: Optional[str] = ""     
    content: Optional[str] = ""         
    isActive: bool = False
    isExpanded: bool = False
    isMain: bool = True
    subSteps: List["StepData"] = Field(default_factory=list)

class MessageData(BaseModel):
    content: str
    type: str
    time: str  # ISO格式的时间字符串

class DiscussionUpdateStateRequest(BaseModel):
    session_id: str
    question: str
    images: Optional[List[ImageData]] = None
    imageCode: Optional[List[str]] = None
    current_step: str
    discussion_tree: List[StepData]
    messages_history: List[MessageData]
    knowledge_points: List[KnowledgePoint]
    is_complete: bool
    summary_text: Optional[str] = ""

class DiscussionSessionStateResponse(BaseModel):
    session_id: str
    question: str
    personalized_mode: bool
    user_id: Optional[str] = "default"
    images: Optional[List[ImageData]] = None
    imageCode: Optional[List[str]] = None
    current_step: str
    discussion_tree: List[StepData]
    messages_history: List[MessageData]
    knowledge_points: List[KnowledgePoint]
    core_knowledge: List[CoreKnowledgePoint] = Field(default_factory=list)
    problem_solving_hint: Optional[ProblemSolvingHint] = None
    learning_path: List[LearningPathStep] = Field(default_factory=list)
    current_options: List[str] = Field(default_factory=list)
    current_correct_option: str = ""
    pending_user_question: str = ""
    pending_resume_step: str = ""
    pending_resume_options: List[str] = Field(default_factory=list)
    pending_resume_correct_option: str = ""
    pending_advance_feedback: str = ""
    pending_advance_reason: str = ""
    is_complete: bool
    summary_text: Optional[str] = ""
    adaptive_mode: bool = False
    cross_session_enabled: bool = False
    persist_learner_state: bool = False
    experiment_run_id: Optional[str] = None
    learner_state_status: str = "NOT_APPLICABLE"
    learner_state_error: str = ""
    discussion_plan: Optional[Dict[str, Any]] = None

class LearnerStateRetryRequest(BaseModel):
    session_id: str

class DiscussionSessionState(BaseModel):
    code: int
    message: Optional[str] = None
    data: DiscussionSessionStateResponse

class DiscussionUpdateStateResponse(BaseModel):
    code: int
    message: str
    data: Optional[Any] = None

StepData.model_rebuild()

def _safe_bool(value, default=False):
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"true", "1", "yes", "y"}
    return bool(value)

def _safe_int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default

def _as_list(value):
    return value if isinstance(value, list) else []

def _learner_question(session):
    return getattr(session, "learner_question", session.question)

def _discussion_options(value):
    return [
        str(option) for option in _as_list(value)
        if str(option).strip() not in {"不知道", "我不知道"}
    ]

def _as_dict(value):
    return value if isinstance(value, dict) else {}

def _resolve_restore_user_id(request: RestoreSessionRequest) -> str:
    snapshot = request.snapshot or {}
    return (
        request.user_id
        or snapshot.get("user_id")
        or snapshot.get("userId")
        or "default"
    )

def _restore_knowledge_dict(snapshot: Dict[str, Any]) -> Dict[str, str]:
    direct = snapshot.get("knowledge_points_dict") or snapshot.get("knowledge_dict")
    if isinstance(direct, dict):
        return {str(k): str(v) for k, v in direct.items()}

    restored = {}
    for item in _as_list(snapshot.get("knowledge_points")):
        if hasattr(item, "model_dump"):
            item = item.model_dump()
        if not isinstance(item, dict):
            continue
        title = item.get("title")
        if title:
            restored[str(title)] = str(item.get("description", ""))
    return restored

def _restore_structured_knowledge(snapshot: Dict[str, Any]):
    raw_core = snapshot.get("core_knowledge")
    if not isinstance(raw_core, list):
        return [], None

    serialized = json.dumps({
        "core_knowledge": raw_core,
        "problem_solving_hint": snapshot.get("problem_solving_hint"),
    }, ensure_ascii=False)
    try:
        analysis = parse_knowledge_analysis(serialized)
        return analysis.core_knowledge, analysis.problem_solving_hint
    except KnowledgeAnalysisError:
        return salvage_core_knowledge(serialized), None

def _restore_step_nodes(items) -> List[StepNodeData]:
    nodes = []
    for item in _as_list(items):
        if isinstance(item, StepNodeData):
            nodes.append(item)
            continue
        if not isinstance(item, dict):
            continue
        try:
            nodes.append(StepNodeData(**item))
        except Exception as exc:
            logger.warning("Skip invalid restored step node: %s", exc)
    return nodes



def _apply_discussion_projection(session) -> None:
    projection = project_discussion_plan(session.discussion_plan)
    session.steps = projection.discussion_tree
    session.current_step = projection.current_step
    session.current_step_number = projection.current_step_number
    session.stack = projection.stack


def _initialize_discussion_plan(session, initial_prompt: str) -> None:
    prompt = str(initial_prompt or "").strip()
    if not prompt:
        raise PlanningError("initial discussion prompt is empty")
    objective = (
        session.learning_path[0]["title"]
        if getattr(session, "learning_path", None)
        else prompt
    )
    session.discussion_plan = create_initial_plan(objective, prompt)
    _apply_discussion_projection(session)

@app.post("/api/v1/explanation/restoresession", response_model=RestoreSessionResponse)
@session_operation
async def restore_explanation_session(request: RestoreSessionRequest):
    snapshot = request.snapshot or {}
    if not isinstance(snapshot, dict):
        raise HTTPException(status_code=400, detail="snapshot 格式错误")

    session_id = str(uuid.uuid4())
    user_id = _resolve_restore_user_id(request)

    from explanation.explanationSession import ExplanationSession
    session = ExplanationSession(session_id, str(snapshot.get("question", "")), user_id=user_id)

    session.images = _as_list(snapshot.get("images"))
    session.imageCode = _as_list(snapshot.get("imageCode"))
    session.current_step = _safe_int(snapshot.get("current_step"), 0)
    session.step_count = _safe_int(snapshot.get("total_steps") or snapshot.get("step_count"), 0)
    session.in_summary = _safe_bool(snapshot.get("in_summary"), False)
    session.personalized_mode = _safe_bool(
        snapshot.get("personalized_mode", snapshot.get("personalizedMode")),
        False
    )
    session.adaptive_mode = _safe_bool(snapshot.get("adaptive_mode"), session.personalized_mode)
    session.cross_session_enabled = _safe_bool(snapshot.get("cross_session_enabled"), session.personalized_mode)
    session.persist_learner_state = _safe_bool(snapshot.get("persist_learner_state"), session.personalized_mode)
    session.experiment_run_id = snapshot.get("experiment_run_id")
    session.learner_state_scope = snapshot.get("learner_state_scope")
    session.learner_state_status = str(snapshot.get("learner_state_status", "PENDING" if session.persist_learner_state else "NOT_APPLICABLE"))
    session.learner_state_error = str(snapshot.get("learner_state_error", ""))
    session.knowledge_dict = _restore_knowledge_dict(snapshot)
    session.core_knowledge, session.problem_solving_hint = _restore_structured_knowledge(snapshot)
    session.learning_path = _as_list(snapshot.get("learning_path"))
    if not session.knowledge_dict and session.core_knowledge:
        session.knowledge_dict = knowledge_dict_from_core(session.core_knowledge)
    session.steps_dict = _as_dict(snapshot.get("steps_dict"))
    session.steps_data = _as_dict(snapshot.get("steps_data"))
    try:
        session.learning_path = parse_learning_path(session.steps_data) if snapshot.get("learning_path") else []
        if snapshot.get("learning_path") and isinstance(snapshot.get("learning_path"), list):
            restored_path = snapshot.get("learning_path")
            if restored_path != session.learning_path:
                session.learning_path = []
    except LearningPathError:
        session.learning_path = []
    session.steps_op_prefix_dic = _as_dict(snapshot.get("steps_op_prefix_dic"))
    session.steps_info = _restore_step_nodes(snapshot.get("steps_info"))
    session.steps_history = _restore_step_nodes(snapshot.get("steps_history"))
    session.wrong_answer_list = _as_list(
        snapshot.get("wrong_answers") or snapshot.get("wrong_answer_list")
    )
    session.wrong_answers = session.wrong_answer_list
    session.user_profile_text = str(snapshot.get("user_profile_text", ""))
    session.weak_points = str(snapshot.get("weak_points", ""))
    session.related_kps = _as_list(snapshot.get("related_kps"))
    session.annotation_history = _as_list(snapshot.get("annotation_history"))
    session.knowledge_tracing_history = _as_list(snapshot.get("knowledge_tracing_history"))
    session.current_step_true_answer_index = _safe_int(snapshot.get("current_step_true_answer_index"), 0)
    restored_answer = snapshot.get("current_answer_result")
    session.current_answer_result = restored_answer if isinstance(restored_answer, dict) else None
    restored_answers = snapshot.get("answer_results")
    session.answer_results = restored_answers if isinstance(restored_answers, dict) else {}
    session.history = _as_list(snapshot.get("history"))
    session.conversations = _as_list(snapshot.get("conversations"))
    session.system_prompt = str(snapshot.get("system_prompt", ""))

    session_manager.sessions[session_id] = session
    logger.info(
        "Restored explanation session java_session_id=%s python_session_id=%s",
        request.java_session_id,
        session_id,
    )
    return {
        "code": 200,
        "message": "session restored",
        "data": {"session_id": session_id}
    }

@app.post("/api/v1/discussion/restoresession", response_model=RestoreSessionResponse)
@session_operation
async def restore_discussion_session(request: RestoreSessionRequest):
    snapshot = request.snapshot or {}
    if not isinstance(snapshot, dict):
        raise HTTPException(status_code=400, detail="snapshot 格式错误")

    session_id = str(uuid.uuid4())
    user_id = _resolve_restore_user_id(request)

    from discussion.discussionSession import DiscussionSystem
    session = DiscussionSystem(session_id, str(snapshot.get("question", "")), user_id=user_id)

    try:
        if snapshot.get("discussion_plan") is not None:
            saved_plan = dict(snapshot["discussion_plan"])
            if "active_node_id" not in saved_plan:
                active_nodes = [
                    node for node in _as_list(saved_plan.get("nodes"))
                    if isinstance(node, dict) and node.get("status") == "active"
                ]
                completion = _as_dict(saved_plan.get("completion"))
                if len(active_nodes) == 1:
                    saved_plan["active_node_id"] = active_nodes[0].get("id")
                elif completion.get("status") == "completed":
                    saved_plan["active_node_id"] = None
            session.discussion_plan = DiscussionPlan.model_validate(saved_plan)
            validate_plan(session.discussion_plan)
        else:
            session.discussion_plan = migrate_legacy_discussion_plan(snapshot)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"discussion_plan 恢复失败: {exc}") from exc

    session.images = _as_list(snapshot.get("images"))
    session.imageCode = _as_list(snapshot.get("imageCode"))
    session.personalized_mode = _safe_bool(
        snapshot.get("personalized_mode", snapshot.get("personalizedMode")),
        False
    )
    session.adaptive_mode = _safe_bool(snapshot.get("adaptive_mode"), session.personalized_mode)
    session.cross_session_enabled = _safe_bool(snapshot.get("cross_session_enabled"), session.personalized_mode)
    session.persist_learner_state = _safe_bool(snapshot.get("persist_learner_state"), session.personalized_mode)
    session.experiment_run_id = snapshot.get("experiment_run_id")
    session.learner_state_scope = snapshot.get("learner_state_scope")
    session.learner_state_status = str(snapshot.get("learner_state_status", "PENDING" if session.persist_learner_state else "NOT_APPLICABLE"))
    session.learner_state_error = str(snapshot.get("learner_state_error", ""))
    session.messages_history = _as_list(snapshot.get("messages_history"))
    session.messagesHistory = session.messages_history
    session.knowledge_dict = _restore_knowledge_dict(snapshot)
    session.core_knowledge, session.problem_solving_hint = _restore_structured_knowledge(snapshot)
    session.learning_path = _as_list(snapshot.get("learning_path"))
    session.current_options = _discussion_options(snapshot.get("current_options"))
    session.current_correct_option = str(snapshot.get("current_correct_option", "")).strip()
    session.pending_user_question = str(snapshot.get("pending_user_question", ""))
    session.pending_resume_step = str(snapshot.get("pending_resume_step", ""))
    session.pending_resume_options = _discussion_options(snapshot.get("pending_resume_options"))
    session.pending_resume_correct_option = str(snapshot.get("pending_resume_correct_option", "")).strip()
    session.pending_advance_feedback = str(snapshot.get("pending_advance_feedback", ""))
    session.pending_advance_reason = str(snapshot.get("pending_advance_reason", ""))
    if not session.knowledge_dict and session.core_knowledge:
        session.knowledge_dict = knowledge_dict_from_core(session.core_knowledge)
    session.is_complete = _safe_bool(snapshot.get("is_complete"), False)
    session.summary_text = str(snapshot.get("summary_text", ""))
    session.user_profile_text = str(snapshot.get("user_profile_text", ""))
    session.weak_points = str(snapshot.get("weak_points", ""))
    session.related_kps = _as_list(snapshot.get("related_kps"))
    session.annotation_history = _as_list(snapshot.get("annotation_history"))
    session.knowledge_tracing_history = _as_list(snapshot.get("knowledge_tracing_history"))
    session.history = _as_list(snapshot.get("history"))
    session.conversations = _as_list(snapshot.get("conversations"))
    session.normalize_summary_text()
    session.system_prompt = str(snapshot.get("system_prompt", ""))
    _apply_discussion_projection(session)
    session.is_complete = session.discussion_plan.completion.status == "completed"

    session_manager.sessions[session_id] = session
    logger.info(
        "Restored discussion session java_session_id=%s python_session_id=%s",
        request.java_session_id,
        session_id,
    )
    return {
        "code": 200,
        "message": "session restored",
        "data": {"session_id": session_id}
    }

# 初始化讨论系统
@app.post("/api/v1/discussion/init", response_model=DiscussionInitState)
@session_operation
async def init_discussion_session(request: DiscussionInitRequest):
    cross_session_enabled = request.crossSessionEnabled if request.crossSessionEnabled is not None else False
    if cross_session_enabled:
        session = session_manager.create_session("discussion", request.processedQuestion, user_id=request.userId or "default")
        discussSession = session

        discussSession.question = request.processedQuestion
        discussSession.learner_question = request.displayQuestion or request.processedQuestion
        discussSession.personalized_mode = True
        discussSession.adaptive_mode = request.adaptiveMode if request.adaptiveMode is not None else True
        discussSession.cross_session_enabled = True
        discussSession.persist_learner_state = request.persistLearnerState if request.persistLearnerState is not None else True
        discussSession.experiment_run_id = request.experimentRunId
        discussSession.learner_state_scope = request.learnerStateScope
        discussSession.experiment_topic = request.topic
        discussSession.experiment_objectives = request.objectives or []
        discussSession.pretest_summary = request.pretestSummary or {}
        discussSession.prior_round_state = request.priorRoundState or []
        discussSession.interaction_limit = request.interactionLimit
        discussSession.force_coverage_on_final_turn = bool(request.forceCoverageOnFinalTurn)
        discussSession.learner_state_status = "PENDING" if discussSession.persist_learner_state else "NOT_APPLICABLE"
        discussSession.images = [img.dict() for img in request.images] if request.images else []      

        # 分析问题
        analysis = await run_blocking(discussSession.analyze_question)
        knowledge_dict = discussSession.knowledge_dict

        knowledge_points = [
            {"title": k, "description": v}
            for k, v in knowledge_dict.items()
        ]

        # 生成初始步骤
        initial_step = await run_blocking(discussSession.generate_initial_step, request.processedQuestion)
        # 截取"步骤1"之后的纯内容，统一拼装
        _m = re.match(r'^步骤\s*1\s*[:：]\s*', initial_step)
        step_content = initial_step[_m.end():] if _m else initial_step
        step1_description = f'步骤1：{step_content}'
        _initialize_discussion_plan(discussSession, step_content)
        initial_step_node = discussSession.steps[0]

        current_time = datetime.now().isoformat()
        messages_history = [
            {
                "content": f'接下来我们将讨论这个问题："{_learner_question(discussSession)}"。',
                "type": "system",
                "time": current_time
            },
            {
                # "content": f'{step1_description}\n\n您能根据相关提示完成这个解题步骤吗？有什么疑问或者不懂的地方都可以提出来！',
                "content": f'{step1_description}',
                "type": "system",
                "time": current_time
            }
        ]
        discussSession.messages_history = messages_history
        
        # 保存初始化对话
        conversations = [
            {"role": "user", "content": request.processedQuestion},
            {"role": "assistant", "content": initial_step}
        ]
        
        discussSession.conversations.append(conversations)

        return {
            "code": 200,
            "message": "success",
            "data": {
                "session_id": session.session_id,
                "initial_step": initial_step,
                "analysis": analysis,
                "knowledge_points": knowledge_points,
                "core_knowledge": discussSession.core_knowledge,
                "problem_solving_hint": discussSession.problem_solving_hint,
                "learning_path": discussSession.learning_path,
                "current_options": discussSession.current_options,
                "current_correct_option": discussSession.current_correct_option,
                "messages_history": messages_history,
                "discussion_tree": [initial_step_node]
            }
        }

    else:
        session = session_manager.create_session("discussion", request.processedQuestion, user_id=request.userId or "default")
        discussSession = session
        discussSession.question = request.processedQuestion
        discussSession.learner_question = request.displayQuestion or request.processedQuestion
        discussSession.personalized_mode = request.personalizedMode if request.personalizedMode is not None else False
        discussSession.adaptive_mode = request.adaptiveMode if request.adaptiveMode is not None else True
        discussSession.cross_session_enabled = cross_session_enabled
        discussSession.persist_learner_state = request.persistLearnerState if request.persistLearnerState is not None else False
        discussSession.experiment_run_id = request.experimentRunId
        discussSession.learner_state_scope = request.learnerStateScope
        discussSession.experiment_topic = request.topic
        discussSession.experiment_objectives = request.objectives or []
        discussSession.pretest_summary = request.pretestSummary or {}
        discussSession.prior_round_state = request.priorRoundState or []
        discussSession.interaction_limit = request.interactionLimit
        discussSession.force_coverage_on_final_turn = bool(request.forceCoverageOnFinalTurn)
        discussSession.learner_state_status = "PENDING" if discussSession.persist_learner_state else "NOT_APPLICABLE"
        discussSession.images = [img.dict() for img in request.images] if request.images else []
        discussSession.imageCode = request.imageCode if request.imageCode else []

        # 分析问题
        analysis = await run_blocking(discussSession.analyze_question)
        knowledge_dict = discussSession.knowledge_dict

        knowledge_points = [
            {"title": k, "description": v}
            for k, v in knowledge_dict.items()
        ]
        
        # 生成初始步骤
        initial_step = await run_blocking(discussSession.generate_initial_step, discussSession.question)
        # 截取"步骤1"之后的纯内容，统一拼装
        _m = re.match(r'^步骤\s*1\s*[:：]\s*', initial_step)
        step_content = initial_step[_m.end():] if _m else initial_step
        step1_description = f'步骤1：{step_content}'

        _initialize_discussion_plan(discussSession, step_content)
        initial_step_node = discussSession.steps[0]

        current_time = datetime.now().isoformat()
        messages_history = [
            {
                "content": f'接下来我们将讨论这个问题："{_learner_question(discussSession)}"。',
                "type": "system",
                "time": current_time
            },
            {
                # "content": f'{step1_description}\n\n您能根据相关提示完成这个解题步骤吗？有什么疑问或者不懂的地方都可以提出来！',
                "content": f'{step1_description}',
                "type": "system",
                "time": current_time
            }
        ]
        discussSession.messages_history = messages_history

        return {
            "code": 200,
            "message": "success",
            "data": {
                "session_id": session.session_id,
                "initial_step": initial_step,
                "analysis": analysis,
                "knowledge_points": knowledge_points,
                "core_knowledge": discussSession.core_knowledge,
                "problem_solving_hint": discussSession.problem_solving_hint,
                "learning_path": discussSession.learning_path,
                "current_options": discussSession.current_options,
                "messages_history": messages_history,
                "discussion_tree": [initial_step_node]
            }
        }


# 提交用户反馈
@app.post("/api/v1/discussion/feedback", response_model=DiscussionFeedbackState)
@session_operation
async def submit_discussion_feedback(request: DiscussionFeedbackRequest):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type not in ["discussion"]:
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    discussSession = session

    if (
        request.submission_type == "option"
        and request.feedback.strip() not in {
            str(option).strip() for option in discussSession.current_options
        }
    ):
        raise HTTPException(status_code=400, detail="提交的选项已失效，请重新选择")

    current_time = datetime.now().isoformat()

    response = await run_blocking(discussSession.process_feedback,
        request.feedback,
        request.behavior_data,
        submission_type=request.submission_type,
    )
    if request.feedback.strip() != "重试进入下一步":
        discussSession.messages_history.append({
            "content": request.feedback,
            "type": "user",
            "time": current_time
        })
    if discussSession.annotation_history:
        latest_annotation = discussSession.annotation_history[-1]
        response["correctness"] = latest_annotation.get("correctness", "na")
        response["knowledge_points"] = latest_annotation.get("knowledge_points", [])
    
    status = response.get("status")
    feedback_text = response.get("feedback", "")
    step_text = response.get("step", "")
    summary = response.get("summary", "")
    
    ai_message_content = ""

    if status in {"main_step", "sub_step"} and step_text:
        ai_message_content = step_text

    elif status == "completed":
        discussSession.is_complete = True
        discussSession.summary_text = summary
        ai_message_content = "🎉 恭喜！问题研讨已完成。"
        
        # 讨论完成后进行知识追踪评估：分析掌握度变化并更新知识图谱
        if discussSession.persist_learner_state:
            await run_blocking(discussSession.knowledge_tracing)
        
    else:
        ai_message_content = "请继续分析当前步骤，或者提出您的疑问。"

    if feedback_text:
        discussSession.messages_history.append({
            "content": feedback_text,
            "type": "system",
            "time": current_time
        })

    # 记录 AI 的回复消息
    if ai_message_content:
        discussSession.messages_history.append({
            "content": ai_message_content,
            "type": "system",
            "time": current_time
        })

    return {
        "code": 200,
        "message": "success",
        "data": {
            **response,
            "discussion_tree": discussSession.steps,
            "messages_history": discussSession.messages_history,
            "current_options": discussSession.current_options,
            "current_correct_option": discussSession.current_correct_option,
            "pending_advance_feedback": discussSession.pending_advance_feedback,
            "pending_advance_reason": discussSession.pending_advance_reason,
            "learner_state_status": discussSession.learner_state_status,
            "learner_state_error": discussSession.learner_state_error
        }
    }

# 获取讨论会话状态
@app.get("/api/v1/discussion/sessionstate/{session_id}", response_model=DiscussionSessionState)
@session_operation
async def get_discussion_session_state(session_id: str):
    session = session_manager.get_session(session_id)
    if not session or session.session_type not in ["discussion"]:
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    discussSession = session
    discussSession.normalize_summary_text()

    # 准备知识点的字典
    knowledge_points = [
        {"title": k, "description": v}
        for k, v in discussSession.knowledge_dict.items()
    ]
    
    return {
        "code": 200,
        "message": "success",
        "data": {
            "session_id": session_id,
            "question": _learner_question(discussSession),
            "personalized_mode": discussSession.personalized_mode,
            "adaptive_mode": discussSession.adaptive_mode,
            "cross_session_enabled": discussSession.cross_session_enabled,
            "persist_learner_state": discussSession.persist_learner_state,
            "experiment_run_id": discussSession.experiment_run_id,
            "user_id": getattr(discussSession, 'user_id', 'default'),
            "images": discussSession.images if isinstance(discussSession.images, list) else [],
            "imageCode": discussSession.imageCode if isinstance(discussSession.imageCode, list) else [],
            "current_step": discussSession.current_step,
            "discussion_tree": discussSession.steps,
            "messages_history": discussSession.messages_history,
            "knowledge_points": knowledge_points,
            "core_knowledge": discussSession.core_knowledge,
            "problem_solving_hint": discussSession.problem_solving_hint,
            "learning_path": discussSession.learning_path,
            "current_options": discussSession.current_options,
            "current_correct_option": discussSession.current_correct_option,
            "pending_user_question": discussSession.pending_user_question,
            "pending_resume_step": discussSession.pending_resume_step,
            "pending_resume_options": discussSession.pending_resume_options,
            "pending_resume_correct_option": discussSession.pending_resume_correct_option,
            "pending_advance_feedback": discussSession.pending_advance_feedback,
            "pending_advance_reason": discussSession.pending_advance_reason,
            "is_complete": discussSession.is_complete,
            "summary_text": discussSession.summary_text,
            "learner_state_status": discussSession.learner_state_status,
            "learner_state_error": discussSession.learner_state_error,
            "discussion_plan": (
                discussSession.discussion_plan.model_dump()
                if discussSession.discussion_plan is not None
                else None
            )
        }
    }

@app.post("/api/v1/learnerstate/retry")
@session_operation
async def retry_learner_state(request: LearnerStateRetryRequest):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type not in ["explanation", "discussion"]:
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    if not getattr(session, "persist_learner_state", False):
        raise HTTPException(status_code=400, detail="该会话不保存跨轮 learner state")
    await run_blocking(session.knowledge_tracing)
    return {"code": 200, "message": "success", "data": {
        "session_id": session.session_id,
        "learner_state_status": session.learner_state_status,
        "learner_state_error": session.learner_state_error,
        "knowledge_tracing_history": session.knowledge_tracing_history
    }}

@app.get("/api/v1/discussion/knowledgetracing/{session_id}")
@session_operation
async def get_discussion_knowledge_tracing(session_id: str):
    session = session_manager.get_session(session_id)
    if not session or session.session_type not in ["discussion"]:
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    discussSession = session

    try:
        knowledge_tracing_data = discussSession.knowledge_tracing_history
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"知识追踪历史获取失败: {str(e)}")

    return {
        "code": 200,
        "message": "success",
        "data": {
            "knowledge_tracing_data": knowledge_tracing_data
        }
    }

@app.get("/api/v1/explanation/knowledgetracing/{session_id}")
@session_operation
async def get_explanation_knowledge_tracing(session_id: str):
    session = session_manager.get_session(session_id)
    if not session or session.session_type not in ["explanation"]:
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    explainSession = session

    try:
        knowledge_tracing_data = explainSession.knowledge_tracing_history
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"知识追踪历史获取失败: {str(e)}")

    return {
        "code": 200,
        "message": "success",
        "data": {
            "knowledge_tracing_data": knowledge_tracing_data
        }
    }

# 更新讨论会话状态
@app.post("/api/v1/discussion/updatestate", response_model=DiscussionSessionState)
@session_operation
async def update_discussion_session_state(request: DiscussionUpdateStateRequest):
    session = session_manager.get_session(request.session_id)
    if not session or session.session_type not in ["discussion"]:
        raise HTTPException(status_code=404, detail="会话不存在或类型不匹配")
    discussSession = session

    try:
        # 更新会话基础字段
        discussSession.images = [img.dict() for img in request.images] if request.images else []
        discussSession.imageCode = request.imageCode if request.imageCode else []
        # 转换消息格式
        discussSession.messages_history = [
            {
                "content": msg.content,
                "type": msg.type,
                "time": msg.time
            }
            for msg in request.messages_history
        ]

        # 同步完成状态与总结
        discussSession.summary_text = request.summary_text
        discussSession.normalize_summary_text()
        if discussSession.discussion_plan is not None:
            _apply_discussion_projection(discussSession)
            discussSession.is_complete = discussSession.discussion_plan.completion.status == "completed"

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"状态更新失败: {str(e)}")

    return await get_discussion_session_state(request.session_id)


# 交互图聊天相关模型
class MessageHistoryItem(BaseModel):
    role: str  # 'user' 或 'assistant'
    content: str
    time: str

class GraphChatRequest(BaseModel):
    currentQuestion: str  # 当前题目
    question: str  # 用户提问
    imageUrl: Optional[str] = ""  # 图片URL
    messageHistory: List[MessageHistoryItem] = []  # 消息历史（最后5个元素）
    interactionHistory: List[str] = []  # 交互历史

class GraphChatResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[Dict[str, str]] = None

class KnowledgeGraphResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[Dict[str, List[Dict]]] = None

# 用户相关api
@app.get("/api/v1/user/knowledge-graph", response_model=KnowledgeGraphResponse)
@session_operation
async def get_knowledge_graph(user_id: str):
    return await run_blocking(_get_knowledge_graph_sync, user_id)


def _get_knowledge_graph_sync(user_id: str):
    # 该接口只应由已鉴权的 Java 网关调用。即使 Python 服务仅监听
    # loopback，也必须按认证主体隔离图数据，避免把所有用户的节点返回给前端。
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", user_id):
        raise HTTPException(status_code=400, detail="无效的用户标识")

    try:
        from neo4j import GraphDatabase
        from tools.neo4j_service import (
            NEO4J_DATABASE,
            NEO4J_PASSWORD,
            NEO4J_URI,
            NEO4J_USERNAME,
        )

        if not NEO4J_PASSWORD:
            raise RuntimeError("NEO4J_PASSWORD is not configured")
        
        # 连接到Neo4j数据库
        driver = GraphDatabase.driver(
            NEO4J_URI,
            auth=(NEO4J_USERNAME, NEO4J_PASSWORD),
        )

        with driver.session(database=NEO4J_DATABASE) as session:
            # 查询所有节点
            nodes_result = session.run("""
                MATCH (n:KnowledgePoint)
                WHERE n.user_id = $user_id
                RETURN n.id as id, n.name as name, 
                       n.nesting_level as nesting_level, 
                       n.mastery_rate as mastery_rate
            """, user_id=user_id)
            
            nodes = []
            for record in nodes_result:
                nodes.append({
                    "id": record["id"],
                    "name": record["name"],
                    "nesting_level": record["nesting_level"],
                    "mastery_rate": record["mastery_rate"]
                })
            
            # 查询所有关系
            edges_result = session.run("""
                MATCH (from:KnowledgePoint)-[r]->(to:KnowledgePoint)
                WHERE from.user_id = $user_id AND to.user_id = $user_id
                RETURN from.id as from_id, to.id as to_id, type(r) as relation
            """, user_id=user_id)
            
            edges = []
            for record in edges_result:
                edges.append({
                    "from": record["from_id"],
                    "to": record["to_id"],
                    "relation": record["relation"]
                })
        
        driver.close()

        return {
            "code": 200,
            "message": "success",
            "data": {
                "nodes": nodes,
                "edges": edges
            }
        }

    except HTTPException:
        raise
    except Exception:
        logger.exception("获取知识图谱失败 | user_id=%s", user_id)
        raise HTTPException(status_code=503, detail="知识图谱服务暂不可用")

if __name__ == "__main__":
    # 推理端没有面向终端用户的认证层，默认仅供本机 Java 网关访问。
    # 容器部署若需跨容器监听，可显式设置 PYTHON_HOST=0.0.0.0 并在网络层隔离。
    server_host = os.getenv("PYTHON_HOST", "127.0.0.1")
    try:
        server_port = int(os.getenv("PYTHON_PORT", "8000"))
    except ValueError as exc:
        raise RuntimeError("PYTHON_PORT must be an integer") from exc
    uvicorn.run(app, host=server_host, port=server_port)
