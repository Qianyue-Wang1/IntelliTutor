from common.state_storage import learner_state_operation
from common.concurrency import run_blocking
from datetime import datetime
import re
import random
import json
from loguru import logger
from explanation.prompt.zh.prompt import EXTRACT_KNOWLEDGE_POINTS_PROMPT, PARSE_USER_IDEA_PROMPT, ANALYZE_USER_INTENT_PROMPT, STEP_KNOWLEDGE_EXPLANATION_PROMPT, CORRECT_ANSWER_FEEDBACK_PROMPT, OPTION_GENERATION_PROMPT, OPTION_GENERATION_PERSONALIZED_PROMPT, INCORRECT_ANSWER_PROMPT
from common.knowledge_analysis import knowledge_dict_from_core, parse_knowledge_analysis
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

class ExplanationSession(BaseSession):
    def __init__(self, session_id, question, user_id="default"):
        super().__init__(session_id, question)
        self.session_type = "explanation"
        self.user_id = user_id
        self.system_prompt = ""
        self.current_step = 0
        self.knowledge_dict = {}
        self.core_knowledge = []
        self.problem_solving_hint = None
        self.learning_path = []
        self.steps_dict = {}
        self.question = question
        self.wrong_answer_list = []
        self.step_count = 0
        self.current_step_true_answer_index = 0
        self.in_summary = False
        self.steps_op_prefix_dic = {}
        self.steps_data = {}
        self.steps_info = []
        self.images = []
        self.imageCode = []
        self.personalized_mode = False
        self.adaptive_mode = False
        self.cross_session_enabled = False
        self.persist_learner_state = False
        self.experiment_run_id = None
        self.learner_state_scope = None
        self.learner_state_status = "NOT_APPLICABLE"
        self.learner_state_error = ""
        self.annotation_history = []  # KT标注历史记录
        self.conversations = []  # 对话与标注结果存档
        self.user_profile_text = ""  # 用户画像文本
        self.weak_points = ""  # 知识薄弱点文本
        self.history = []  # 系统与学生的交互历史（role: assistant/user）
        self.knowledge_tracing_history = []  # 知识追踪结果记录
        self.steps_history = []  # 步骤节点历史记录（StepNodeData）
        self.related_kps = []  # 本道题相关知识点
        self.current_answer_result = None  # 当前阶段提交后的判定与解析
        self.answer_results = {}  # 按步骤保存作答结果，供刷新和回看恢复

    def call_llm(self, prompt):
        """LLM调用（instruct模型），委托给 llm_api_service，并记录完整 prompt/response。"""
        context = {
            "session_id": self.session_id,
            "user_id": self.user_id,
            "has_system_prompt": bool(self.system_prompt),
        }
        _log_llm_prompt("explanation.call_llm", "instruct", prompt, system_prompt=self.system_prompt, **context)
        response = call_llm(prompt, system_prompt=self.system_prompt)
        _log_llm_response("explanation.call_llm", "instruct", response, **context)
        return response

    async def call_llm_async(self, prompt, model_type="instruct"):
        """异步LLM调用，委托给 llm_api_service，并记录完整 prompt/response。"""
        context = {
            "session_id": self.session_id,
            "user_id": self.user_id,
            "has_system_prompt": bool(self.system_prompt),
        }
        _log_llm_prompt("explanation.call_llm_async", model_type, prompt, system_prompt=self.system_prompt, **context)
        response = await call_llm_async(prompt, model_type=model_type, system_prompt=self.system_prompt)
        _log_llm_response("explanation.call_llm_async", model_type, response, **context)
        return response

    def call_llm_normal_api(self, prompt):
        """大参数模型调用（large），委托给 llm_api_service，并记录完整 prompt/response。"""
        context = {
            "session_id": self.session_id,
            "user_id": self.user_id,
            "has_system_prompt": bool(self.system_prompt),
        }
        _log_llm_prompt("explanation.call_llm_normal_api", "large", prompt, system_prompt=self.system_prompt, **context)
        response = call_llm(prompt, model_type="large", system_prompt=self.system_prompt)
        _log_llm_response("explanation.call_llm_normal_api", "large", response, **context)
        return response

    def init_system_prompt(self):
        self.system_prompt = f"""
            你是一名通用学习辅导者，能够把概念、事实、程序、推理、语言、计算或开放学习任务拆成清晰步骤，并用交互式、引导式教学帮助学习者理解每一步的目标、依据和检查方法。事实不确定时明确待核实内容，开放任务按证据和评价标准推进。\n
        """
    
    def build_personalized_system_prompt(self):
        """构建结合认知负荷、KT标注和用户画像的个性化系统提示"""
        from explanation.prompt.zh.prompt import SYSTEM_PROMPT_PERSONALIZED

        # 构建用户画像文本
        user_profile_text = self.user_profile_text if self.user_profile_text else self.build_user_profile()
        weak_points = self.weak_points if self.weak_points else self.build_weak_points()

        user_profile_parts = [
            f"## 用户画像:\n{user_profile_text}",
            f"## 薄弱知识点:\n{weak_points}",
        ]

        user_profile = "\n".join(user_profile_parts)

        # 构建知识图谱文本（基于 self.related_kps 查询 neo4j 掌握度）
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
                knowledge_graph_parts.append(
                    f"- {kp_desc} (ID: {kp_id}): 掌握度 {mastery:.2f}"
                )

        knowledge_graph = "\n".join(knowledge_graph_parts) if knowledge_graph_parts else "暂无知识点追踪数据"

        # 使用个性化模板构建系统提示
        self.system_prompt = SYSTEM_PROMPT_PERSONALIZED.format(
            user_profile=user_profile,
            knowledge_graph=knowledge_graph,
        )

        return self.system_prompt

    def parse_knowledge_point(self, content):
        analysis = parse_knowledge_analysis(content)
        self.core_knowledge = analysis.core_knowledge
        self.problem_solving_hint = analysis.problem_solving_hint
        self.knowledge_dict = knowledge_dict_from_core(self.core_knowledge)
        return self.knowledge_dict

    def parse_solving_steps(self, content):
        steps_content = None
        section_patterns = [
            r'3\.[\s]*\**拆解具体步骤\**[\s]*[:：]?(.*?)(?:\n\s*4\.|\n\s*答案\s*[:：]|\Z)',
            r'\**拆解具体步骤\**[\s]*[:：]?(.*?)(?:\n\s*4\.|\n\s*答案\s*[:：]|\Z)',
            r'完整解题步骤[\s]*[:：]?(.*?)(?:\n\s*答案\s*[:：]|\Z)',
            r'##\s*解题步骤\s*(.*?)(?:\n\s*答案\s*[:：]|\Z)',
        ]

        for pattern in section_patterns:
            match = re.search(pattern, content, re.DOTALL | re.IGNORECASE)
            if match:
                candidate = match.group(1).strip()
                if candidate:
                    steps_content = candidate
                    break

        if not steps_content:
            steps_content = content.strip()

        steps_dict = {}

        pattern = re.compile(
            r'^\s*[-*]*\s*\**步骤\s*(\d+)\s*[:：]\**\s*(.*?)'
            r'(?=^\s*[-*]*\s*\**步骤\s*\d+\s*[:：]|\n\s*[-*]*\s*答案\s*[-*]*\s*[:：]|\Z)',
            re.DOTALL | re.MULTILINE,
        )

        matches = pattern.finditer(steps_content)
        
        for match in matches:
            step_num = match.group(1)
            step_content = match.group(2).strip()
            
            # 清理格式
            cleaned_content = re.sub(r'^\s*[-*]\s*', '', step_content, flags=re.MULTILINE)
            cleaned_content = cleaned_content.strip()
            
            steps_dict[f"步骤{step_num}"] = cleaned_content

        if not steps_dict:
            raise ValueError("API返回值格式错误:未解析到任何步骤，请检查模型输出中的步骤标题格式")
            
        return steps_dict

    def parse_total_steps(self, content):

        parsed_steps = {}
        
        re_analysis = re.compile(r'\**分析与提示\**\s*[:：]')
        re_summary = re.compile(r'\**操作总结\**\s*[:：]')
        re_op = re.compile(r'\**具体操作\**\s*[:：]')
        
        for step_name, text in content.items():
            match_analysis = re_analysis.search(text)
            match_summary = re_summary.search(text)
            match_op = re_op.search(text)
            
            step_detail = {
                "学习主题": "",
                "本步知识点": "",
                "承接关系": "",
                "检验重点": "",
                "分析与提示": "",
                "操作总结": "",
                "具体操作": ""
            }

            planning_labels = ("学习主题", "本步知识点", "承接关系", "检验重点")
            all_labels = planning_labels + ("分析与提示", "操作总结", "具体操作")
            for label in planning_labels:
                field_match = re.search(
                    rf'\**{label}\**\s*[:：]\s*(.*?)(?=\n\s*\**(?:{"|".join(all_labels)})\**\s*[:：]|$)',
                    text,
                    re.DOTALL,
                )
                if field_match:
                    step_detail[label] = field_match.group(1).strip()
            
            # 确定“分析与提示”内容的真正起点
            pos_analysis_end = match_analysis.end() if match_analysis else 0
            
            # 先找“具体操作”
            if match_op:
                pos_op_start = match_op.start()
                # “具体操作”后面的所有内容都归它
                step_detail["具体操作"] = text[match_op.end():].strip()
                
                # 再看“具体操作”前面有没有“操作总结”
                if match_summary and match_summary.start() < pos_op_start:
                    # 有操作总结：夹在“总结”和“具体操作”之间的是总结内容
                    step_detail["操作总结"] = text[match_summary.end():pos_op_start].strip()
                    # 夹在“分析”起点和“总结”之间的是分析内容
                    step_detail["分析与提示"] = text[pos_analysis_end:match_summary.start()].strip()
                else:
                    # 没有操作总结：夹在“分析”起点和“具体操作”之间的全都是分析内容
                    step_detail["分析与提示"] = text[pos_analysis_end:pos_op_start].strip()
                    
            # 极限异常情况：连“具体操作”标题都丢了
            else:
                if match_summary:
                    step_detail["操作总结"] = text[match_summary.end():].strip()
                    step_detail["分析与提示"] = text[pos_analysis_end:match_summary.start()].strip()
                else:
                    step_detail["分析与提示"] = text[pos_analysis_end:].strip()
                    
            # 清理空字段：如果不存在操作总结，直接把这个 key 删掉，保持数据结构干净
            if not step_detail.get("操作总结"):
                step_detail.pop("操作总结", None)
                
            parsed_steps[step_name] = step_detail
            
        return parsed_steps

    def parse_step(self):
        val = self.steps_dict[f"步骤{self.current_step}"]
        split_points = ["分析与提示：", "具体操作："]
        positions = {}
        
        for point in split_points:
            index = val.find(point)
            positions[point] = index

        if all(index != -1 for index in positions.values()):
            if positions["分析与提示："] < positions["具体操作："]:
                analysis = val[positions["分析与提示："]+len("分析与提示："):positions["具体操作："]].strip()
                operation = val[positions["具体操作："]+len("具体操作："):].strip()
                return analysis, operation
            else:
                raise ValueError("API返回值格式错误：分析与提示应在具体操作之前")
        else:
            missing = [k for k, v in positions.items() if v == -1]
            raise ValueError(f"API返回值缺少必要部分：{', '.join(missing)}")

    def parse_summary_step(self):
        val = self.steps_dict[f"步骤{self.current_step}"]
        split_points = ["分析与提示：", "操作总结：", "具体操作："]
        positions = {}
        
        for point in split_points:
            index = val.find(point)
            positions[point] = index

        if all(index != -1 for index in positions.values()):
            if positions["分析与提示："] < positions["操作总结："] < positions["具体操作："]:
                analysis = val[positions["分析与提示："]+len("分析与提示："):positions["操作总结："]].strip()
                operation_summary = val[positions["操作总结："]+len("操作总结："):positions["具体操作："]].strip()
                operation = val[positions["具体操作："]+len("具体操作："):].strip()
                return analysis, operation_summary, operation
            else:
                raise ValueError("API返回值格式错误：顺序错误")
        else:
            missing = [k for k, v in positions.items() if v == -1]
            raise ValueError(f"API返回值缺少必要部分：{', '.join(missing)}")

    def parse_option(self, content):
        results = []
        lines = content.strip().split('\n')
        
        for line in lines:
            match = re.match(r'\d+\.\s*(.*)', line)
            if match:
                content = match.group(1).strip()
                results.append(content)
        
        return results

    def generate_options(self, analysis, operation, assessment_focus="", previous_assessments="暂无前序检验记录"):
        prompt = OPTION_GENERATION_PROMPT.format(
            question=self.question, 
            analysis=analysis, 
            current_step_specific_operation_and_correct_answer=operation,
            assessment_focus=assessment_focus,
            previous_assessments=previous_assessments,
        )
        llm_response = self.call_llm(prompt)
        option_array = self.parse_option(llm_response)
        return option_array

    async def generate_options_async(self, analysis, operation, assessment_focus="", previous_assessments="暂无前序检验记录"):
        """异步生成普通步骤选项，避免阻塞 FastAPI 事件循环。"""
        prompt = OPTION_GENERATION_PROMPT.format(
            question=self.question,
            analysis=analysis,
            current_step_specific_operation_and_correct_answer=operation,
            assessment_focus=assessment_focus,
            previous_assessments=previous_assessments,
        )
        llm_response = await self.call_llm_async(prompt)
        return self.parse_option(llm_response)

    def parse_step_lesson(self, content, enforce_max_length=True):
        text = str(content or "").strip()
        title_match = re.search(r'\**知识主题\**\s*[:：]\s*(.+)', text)
        lesson_match = re.search(r'\**知识讲解\**\s*[:：]\s*(.+)', text, re.DOTALL)
        if not title_match or not lesson_match:
            raise ValueError("知识课程缺少知识主题或知识讲解")

        title = title_match.group(1).strip().strip('*# ')
        lesson = lesson_match.group(1).strip()

        def normalize_bracketed_formula(match):
            formula = match.group(1).strip()
            if re.search(r'\\(?:frac|sum|bar|sqrt|mathrm|mathbf)|[_^=]', formula):
                return f"$${formula}$$"
            return match.group(0)

        lesson = re.sub(
            r'(?m)^\s*\[\s*([^\[\]\r\n]+?)\s*\]\s*$',
            normalize_bracketed_formula,
            lesson,
        )
        banned = (
            "[输入框]", "[操作总结]", "[可执行细节]", "[待填写]",
            "确认基础", "需要确认你是否了解", "请先自行学习",
            "本步学习目标", "掌握检验将考查",
        )
        if any(token in title or token in lesson for token in banned):
            raise ValueError("知识课程包含模板占位符或教学元话语")
        lesson_length = len(re.sub(r'\s+', '', lesson))
        if lesson_length < 120:
            raise ValueError("知识课程内容过短")
        if enforce_max_length and lesson_length > 1000:
            raise ValueError("知识课程内容过长")
        section_count = len(re.findall(r'(?m)^###\s+\S.+$', lesson))
        if not 2 <= section_count <= 4:
            raise ValueError("知识课程必须包含 2～4 个知识模块")
        return title, lesson

    async def generate_step_lesson_async(self, outline, operation, previous_lessons="暂无前序步骤，这是第一个学习阶段。", learning_step=None):
        prompt = STEP_KNOWLEDGE_EXPLANATION_PROMPT.format(
            question=self.question,
            outline=outline,
            operation=operation,
            previous_lessons=previous_lessons,
            learning_step=json.dumps(learning_step or {}, ensure_ascii=False),
        )
        last_error = None
        for attempt in range(2):
            retry_prompt = prompt
            if attempt:
                retry_prompt += "\n\n上一次输出未达到要求。请完整重写，使用 2～4 个三级 Markdown 小标题组织正文，知识讲解不超过 600 个非空白字符，确保内容具体且不含占位符或教学元话语。"
            response = await self.call_llm_async(retry_prompt, model_type="large")
            try:
                return self.parse_step_lesson(response, enforce_max_length=(attempt == 0))
            except ValueError as error:
                last_error = error
        raise ValueError(f"知识课程生成不合格：{last_error}")

    def generate_personalized_options(self, analysis, operation, assessment_focus="", previous_assessments="暂无前序检验记录"):
        common_mistakes_list, study_habits_list = self._option_adaptation_context()
        common_mistakes = "\n".join(f"- {m}" for m in common_mistakes_list) if common_mistakes_list else "暂无记录"
        study_habits = "\n".join(f"- {h}" for h in study_habits_list) if study_habits_list else "暂无记录"
        prompt = OPTION_GENERATION_PERSONALIZED_PROMPT.format(
            question=self.question,
            analysis=analysis,
            current_step_specific_operation_and_correct_answer=operation,
            assessment_focus=assessment_focus,
            previous_assessments=previous_assessments,
            common_mistakes=common_mistakes,
            study_habits=study_habits,
        )
        llm_response = self.call_llm_normal_api(prompt)
        option_array = self.parse_option(llm_response)
        return option_array

    async def generate_personalized_options_async(self, analysis, operation, assessment_focus="", previous_assessments="暂无前序检验记录"):
        """异步生成个性化步骤选项，使用与同步版本一致的大模型配置。"""
        common_mistakes_list, study_habits_list = await run_blocking(self._option_adaptation_context)
        common_mistakes = "\n".join(f"- {m}" for m in common_mistakes_list) if common_mistakes_list else "暂无记录"
        study_habits = "\n".join(f"- {h}" for h in study_habits_list) if study_habits_list else "暂无记录"
        prompt = OPTION_GENERATION_PERSONALIZED_PROMPT.format(
            question=self.question,
            analysis=analysis,
            current_step_specific_operation_and_correct_answer=operation,
            assessment_focus=assessment_focus,
            previous_assessments=previous_assessments,
            common_mistakes=common_mistakes,
            study_habits=study_habits,
        )
        llm_response = await self.call_llm_async(prompt, model_type="large")
        return self.parse_option(llm_response)

    def _option_adaptation_context(self):
        """Return adaptation signals without leaking prior sessions into Basic/Full round 1."""
        if self.cross_session_enabled:
            user_profile = self.get_user_profile_dict()
            return user_profile.get("common_mistakes", []), user_profile.get("study_habits", [])
        mistakes = [item.get("wrong_option", "") for item in self.wrong_answer_list[-5:] if item.get("wrong_option")]
        current_signals = []
        for item in self.annotation_history[-5:]:
            signal = item.get("cognitive_load_level") or item.get("behavior_analysis")
            if signal:
                current_signals.append(str(signal))
        return mistakes, current_signals

    def move_first_to_random_position(self, arr):
        if not arr:
            return [], 0
            
        result = arr.copy()
        first_item = result.pop(0)
        random_index = random.randint(0, len(result))
        result.insert(random_index, first_item)
        return result, random_index

    def parse_incorrect_analysis(self, content):
        keyword = "错误分析提示"
        content = str(content or "").strip()
        start_index = content.find(keyword)
        if start_index != -1:
            content_start = start_index + len(keyword)
            content = content[content_start:].lstrip("：: \t\r\n")
        content = re.sub(r'\n[ \t]*\n(?:[ \t]*\n)+', '\n\n', content).strip()
        if not content.startswith("### 错在哪里") or "### 正确理解" not in content:
            raise ValueError("错误解析缺少规定的知识模块")
        if any(token in content for token in ("温和反馈", "建议巩固", "鼓励语")):
            raise ValueError("错误解析包含教学元话语")
        return content

    def parse_correct_analysis(self, content):
        keyword = "正确理解"
        start_index = str(content or "").find(keyword)
        if start_index == -1:
            raise ValueError("API返回值格式错误：缺少正确理解部分")
        return str(content)[start_index + len(keyword):].lstrip("：: \t\r\n").strip()

    def correct_selection_analysis(self, lesson, correct_answer):
        prompt = CORRECT_ANSWER_FEEDBACK_PROMPT.format(
            question=self.question,
            lesson=lesson,
            correct_answer=correct_answer,
        )
        normalized_answer = re.sub(r'\s+', '', correct_answer)
        for attempt in range(2):
            retry_prompt = prompt
            if attempt:
                retry_prompt += "\n\n上一次输出复述了选项或格式不合格。请重新说明原理和进阶理解，不要改写正确选项。"
            try:
                feedback = self.parse_correct_analysis(self.call_llm(retry_prompt))
                normalized_feedback = re.sub(r'\s+', '', feedback)
                if normalized_answer and normalized_answer in normalized_feedback:
                    raise ValueError("答对解析重复正确选项")
                if any(token in feedback for token in ("正确答案的依据", "本步知识回顾", "温和反馈")):
                    raise ValueError("答对解析包含教学元话语")
                return feedback
            except ValueError:
                continue
        return "本步的核心原理已经掌握；在新情境中继续核对任务目标与判断依据是否一致。"

    def incorrect_selection_analysis(self, lesson, correct_answer, content):
        prompt = INCORRECT_ANSWER_PROMPT.format(
            question=self.question,
            lesson=lesson,
            correct_answer=correct_answer,
            incorrect_answer=content
        )
        llm_response = self.call_llm(prompt)
        return self.parse_incorrect_analysis(llm_response)
    
    def analyze_personalized_input(self, user_input):
        prompt = PARSE_USER_IDEA_PROMPT.format(
            question=self.question, 
            user_input=user_input
        )
        llm_response = self.call_llm(prompt)
        try:
            result = json.loads(llm_response)
            return result
        except json.JSONDecodeError as e:
            raise ValueError(f"JSON解析错误: {str(e)}")
        
    def process_behavior_data(self, behavior_data, user_feedback, current_step_context=None):
        """处理行为数据：将事件流转化为语义标签，调用LLM分析认知负荷和KT价值"""
        # 兼容 Pydantic 模型与普通字典两种输入形式
        if hasattr(behavior_data, "model_dump"):
            behavior_data = behavior_data.model_dump()
        if not isinstance(behavior_data, dict):
            return None
        event_stream = behavior_data.get("event_stream") or []
        if not event_stream:
            return None

        from explanation.prompt.zh.prompt import ANALYZE_BEHAVIOR_PROMPT

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

        has_blur = bool(events_by_type.get("window_blur"))

        # 总耗时（从 step_submit 事件中提取）
        total_time_spent_ms = 0
        submit_events = events_by_type.get("step_submit", [])
        if submit_events:
            total_time_spent_ms = submit_events[-1].get("payload", {}).get("total_time_spent_ms", 0)

        # 切屏行为（多次 window_blur 视为频繁借助外部帮助）
        blur_count = len(events_by_type.get("window_blur", []))

        # 非打字/提交相关的交互事件
        interaction_types = {"window_blur", "window_focus"}
        has_interaction = any(events_by_type.get(t) for t in interaction_types)

        # window_blur 超过 30 秒且伴随 clipboard_action（疑似外部求助）
        scene1_cheating = max_time_away >= 30000
        if scene1_cheating:
            labels.append("存在较长时间离开页面，可能发生了外部信息参考或跨窗口编辑行为。")

        # 频繁切屏（blur_count >= 3）
        if blur_count >= 3:
            labels.append(f"共检测到 {blur_count} 次离开页面事件，可能存在分心或频繁借助外部帮助。")

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

        # 获取当前步骤文本（优先使用外部传入的包含分析+选项的完整上下文）
        if current_step_context:
            current_step = current_step_context
        else:
            current_step_key = f"步骤{self.current_step}"
            current_step = self.steps_data.get(current_step_key, {}).get("分析与提示", f"步骤{self.current_step}")

        # 组装 prompt 并调用 LLM
        prompt = ANALYZE_BEHAVIOR_PROMPT.format(
            question=self.question,
            current_step=current_step,
            user_feedback=user_feedback,
            behavior_data=json.dumps(event_stream, ensure_ascii=False),
            analysis=analysis_text
        )

        _log_llm_prompt("explanation.process_behavior_data", "large", prompt, session_id=self.session_id, user_id=self.user_id)
        llm_response = call_llm(prompt, model_type="large")
        _log_llm_response("explanation.process_behavior_data", "large", llm_response, session_id=self.session_id, user_id=self.user_id)

        if llm_response:
            try:
                result = json.loads(llm_response)
                return {
                    "reasoning_process": result.get("reasoning_process", ""),
                    "correctness_analysis": result.get("correctness_analysis", ""),
                    "cognitive_load_level": result.get("cognitive_load_level", ""),
                    "kt_value": result.get("kt_value", "")
                }
            except json.JSONDecodeError:
                return None
        return None
    
    def get_user_profile_dict(self) -> dict:
        """读取 data/user/{user_id}/user_profile.json，返回原始字典；文件不存在时返回空字典"""
        import os
        from common.learner_state import learner_state_key
        profile_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "data", "user", learner_state_key(self.user_id, self.learner_state_scope), "user_profile.json"
        )
        if os.path.exists(profile_path):
            with open(profile_path, "r", encoding="utf-8") as f:
                return json.load(f)
        return {}

    def build_user_profile(self) -> str:
        """从 data/user/{user_id}/user_profile.json 读取用户画像并返回文本"""
        user_profile = self.get_user_profile_dict()
        if not user_profile:
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

        # 筛选掌握度 < 0.5 的薄弱知识点，提取 exercise_records 中的 analysis
        weak_parts = []
        for kp in related_kps:
            kp_id = kp["id"]
            kp_desc = kp.get("description", "")
            kp_mastery_info = mastery_map.get(kp_id)
            mastery = kp_mastery_info["mastery"] if kp_mastery_info else 0.5

            if mastery < 0.5:
                weak_parts.append(f"- {kp_desc} (掌握度: {mastery:.2f}，注：掌握度数据范围为0-1，数值越低表示越薄弱)")
                # 提取 exercise_records 中的 analysis（只取最近4条）
                historical_analysis = []
                if kp_mastery_info:
                    recent_records = kp_mastery_info.get("exercise_records", [])[-3:]
                    for record in recent_records:
                        analysis = record.get("analysis", "")
                        if analysis:
                            historical_analysis.append(f"  练习分析: {analysis}")
                if historical_analysis:
                    weak_parts.append(" 最近的相关练习分析:")
                    weak_parts.extend(historical_analysis)

        return "\n".join(weak_parts) if weak_parts else "暂无已识别的薄弱知识点"

    def generate_teacher_explanation(self, user_profile: str, weak_points: str, question: str) -> str:
        from tools.llm_api_service import call_llm
        from explanation.prompt.zh.prompt import INSTRUCTION_EXPLANATION_PROMPT

        prompt = INSTRUCTION_EXPLANATION_PROMPT.format(
            profile_str=user_profile,
            weak_points_str=weak_points,
            question=question,
        )

        _log_llm_prompt("explanation.generate_teacher_explanation", "large", prompt, session_id=self.session_id, user_id=self.user_id)
        response = call_llm(prompt, model_type="large")
        _log_llm_response("explanation.generate_teacher_explanation", "large", response, session_id=self.session_id, user_id=self.user_id)
        return response if response else ""
    
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
            record_interaction(self)  # 同步写入日志，确保KT结果已记录
            self.learner_state_status = "READY"
            return True
        except Exception as e:
            self.learner_state_status = "FAILED"
            self.learner_state_error = str(e)[:1000]
            logger.exception("[KT] knowledge_tracing error")
            return False
