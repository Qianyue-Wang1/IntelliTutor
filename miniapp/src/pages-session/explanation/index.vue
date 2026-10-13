<template>
	<view class="page explanation-page">
		<LoadingState v-if="loading" :text="loadingText" hint="AI 任务最长等待 5 分钟，可切后台后继续" />

		<template v-else-if="session">
			<view class="card question-card">
				<text class="card-label">题目</text>
				<text class="question-plain" selectable>{{ session.question }}</text>
				<scroll-view v-if="safeImages.length" scroll-x class="image-strip">
					<image v-for="item in safeImages" :key="item.name" :src="item.url" mode="aspectFit" />
				</scroll-view>
			</view>

			<view v-if="session.knowledge_points.length" class="card knowledge-card">
				<view class="knowledge-card-header" @tap="knowledgeExpanded = !knowledgeExpanded">
					<view class="knowledge-title-wrap">
						<text class="knowledge-title">相关知识点</text>
						<text class="knowledge-count">{{ session.knowledge_points.length }} 个知识点</text>
					</view>
					<view :class="['knowledge-toggle', { expanded: knowledgeExpanded }]"></view>
				</view>
				<view v-if="knowledgeExpanded" class="knowledge-content">
					<view v-for="(point, index) in session.knowledge_points" :key="`${point.title}-${index}`"
						class="knowledge-row">
						<text class="knowledge-index">{{ index + 1 }}</text>
						<view class="knowledge-copy">
							<text class="knowledge-name">{{ point.title }}</text>
							<SafeRichContent :content="point.description" />
						</view>
					</view>
				</view>
			</view>

			<view class="card flow-card">
				<view class="flow-header">
					<text class="flow-title">解题流程</text>
					<text class="flow-meta">{{ flowMetaText }}</text>
				</view>
				<scroll-view scroll-x class="flow-scroll">
					<view class="flow-chain">
						<template v-for="(step, index) in flowSteps" :key="step.number">
							<view
								:class="[
									'flow-node',
									step.status,
									{
										active: displayedStepNumber === step.number,
										clickable: canSelectFlowStep(step.number),
									},
								]"
								@tap="selectFlowStep(step.number)">
								<view class="flow-node-head">
									<text class="flow-step-number">步骤 {{ step.number }}</text>
									<text v-if="step.badge" class="flow-badge">{{ step.badge }}</text>
								</view>
								<text class="flow-step-title">{{ step.title }}</text>
							</view>
							<view v-if="index < flowSteps.length - 1"
								:class="['flow-connector', step.status === 'pending' ? 'pending' : 'active']"></view>
						</template>
					</view>
				</scroll-view>
			</view>

			<view v-if="sessionFinished && !displayStep" class="card summary-card">
				<text class="section-title">完整思路</text>
				<view v-for="item in summarySteps" :key="item.key" class="summary-step">
					<text class="summary-index">{{ item.key }}</text>
					<SafeRichContent :content="item.content" />
				</view>
				<view v-if="session.wrong_answers.length" class="wrong-summary">
					<text class="section-title">需要复习</text>
					<view v-for="wrong in session.wrong_answers" :key="`${wrong.step_number}-${wrong.wrong_option}`"
						class="wrong-item">
						<text>步骤 {{ wrong.step_number }}：选择了 {{ wrong.wrong_option }}</text>
						<SafeRichContent :content="wrong.operation" />
					</view>
				</view>
				<button class="primary-button" @tap="backToQuestion">完成学习</button>
			</view>

			<view v-else-if="displayStep" class="card step-card">
				<view v-if="displayStep.operation_prefix" class="step-operation-heading">
					<text class="step-operation-label">
						步骤 {{ displayStep.step_number }}
					</text>

					<view class="step-operation-title">
						<SafeRichContent :content="displayStep.operation_prefix" />
					</view>
				</view>
				<view class="step-analysis-block">
					<text class="section-title">分析与提示</text>
					<SafeRichContent :content="displayStep.analysis" />
				</view>

				<view v-if="displayStepIsSummaryStep && displaySummaryItems.length" class="thinking-summary">
					<text class="thinking-summary-title">思考流程总结</text>
					<text class="thinking-summary-intro">我们先回顾前面已经完成的步骤</text>
					<view class="thinking-summary-list">
						<view v-for="item in displaySummaryItems" :key="item.number" class="thinking-summary-item">
							<view class="thinking-summary-head">
								<text class="thinking-summary-index">{{ item.number }}</text>
								<view class="thinking-summary-step-title">
									<SafeRichContent :content="item.title" />
								</view>
							</view>
							<view v-if="item.analysis" class="thinking-summary-analysis">
								<SafeRichContent :content="item.analysis" />
							</view>
						</view>
					</view>
				</view>

				<view class="options">
					<view v-for="(option, index) in displayStep.options" :key="`${displayStep.step_number}-${index}`"
						:class="['option', { selected: isDisplayOptionSelected(option, index), readonly: !displayStepIsCurrent }]"
						@tap="handleOptionTap(index)">
						<text class="option-letter">{{ optionLetter(index) }}</text>
						<SafeRichContent :content="option" />
					</view>
				</view>

				<view v-if="displayErrorAnalysis" class="error-analysis">
					<text class="error-title">再想一想</text>
					<SafeRichContent :content="displayErrorAnalysis" />
				</view>

				<button v-if="displayStepIsCurrent" class="primary-button"
					:disabled="submitting || stepLoading || !selectedOption" @tap="submitAnswer">
					{{ submitting ? '正在分析答案…' : '提交答案' }}
				</button>
				<view v-else class="history-step-note">
					<text>已完成步骤，选项仅供回顾</text>
				</view>
			</view>

			<view v-else class="card missing-step">
				<view v-if="stepLoading" class="option-loading-state">
					<view class="option-loading-mark">
						<view class="option-loading-dot dot-one"></view>
						<view class="option-loading-dot dot-two"></view>
						<view class="option-loading-dot dot-three"></view>
					</view>
					<text class="missing-title">选项正在加载中</text>
					<text class="missing-hint">正在根据刚才的选择重新生成这一轮选项</text>
					<view class="option-skeleton-list">
						<view v-for="item in 3" :key="item" class="option-skeleton"></view>
					</view>
				</view>
				<view v-else class="missing-retry-state">
					<text class="missing-title">当前步骤尚未加载</text>
					<text class="missing-hint">可以重新拉取当前步骤的选项</text>
					<button :disabled="submitting || stepLoading" @tap="loadCurrentStep()">重新加载</button>
				</view>
				<view v-if="errorAnalysis" class="error-analysis missing-error-analysis">
					<text class="error-title">再想一想</text>
					<SafeRichContent :content="errorAnalysis" />
				</view>
			</view>
		</template>
	</view>
</template>

<script setup lang="ts">
	import { computed, ref } from 'vue'
	import { onHide, onLoad, onShow } from '@dcloudio/uni-app'
	import { explanationApi } from '@/api'
	import { ApiError, showApiError } from '@/api/request'
	import { resumePendingTask } from '@/api/task'
	import type { ExplanationCheckData, ExplanationInitData, ExplanationSessionData, StepNodeData } from '@/api/types'
	import LoadingState from '@/components/LoadingState.vue'
	import SafeRichContent from '@/pages-session/components/SafeRichContent.vue'
	import { requireAuthentication } from '@/composables/useAuthGuard'
	import { usePendingTaskStore } from '@/stores/pending-task'
	import { useSessionStore } from '@/stores/session'

	type FlowStepStatus = 'completed' | 'current' | 'pending'

	interface FlowStep {
		number : number
		title : string
		status : FlowStepStatus
		badge : string
	}

	interface SummaryReviewItem {
		number : number
		title : string
		analysis : string
	}

	interface LoadCurrentStepOptions {
		preserveErrorAnalysis ?: boolean
		loadingText ?: string
	}

	const sessionStore = useSessionStore()
	const pendingStore = usePendingTaskStore()
	const sessionId = ref('')
	const session = ref<ExplanationSessionData | null>(null)
	const currentStep = ref<StepNodeData | null>(null)
	const selectedStepNumber = ref<number | null>(null)
	const stepSelectedOptions = ref<Record<string, string>>({})
	const selectedOption = ref('')
	const errorAnalysis = ref('')
	const knowledgeExpanded = ref(true)
	const loading = ref(true)
	const loadingText = ref('正在恢复讲解…')
	const submitting = ref(false)
	const stepLoading = ref(false)
	const hidden = ref(false)
	const initialized = ref(false)
	let initializePromise : Promise<void> | null = null
	let stepStateRevision = 0

	const safeImages = computed(() => (session.value?.images || []).filter(item =>
		item.url.startsWith('data:image/') || item.url.startsWith('wxfile://') || item.url.startsWith('blob:')
	))

	const sessionFinished = computed(() => session.value ? isSessionFinished(session.value) : false)

	const flowMetaText = computed(() => {
		if (!session.value) return ''
		if (sessionFinished.value) return '已完成'
		return session.value.in_summary
			? `总结 ${session.value.current_step} / ${session.value.total_steps}`
			: `步骤 ${session.value.current_step} / ${session.value.total_steps}`
	})

	const flowSteps = computed<FlowStep[]>(() => {
		if (!session.value) return []
		const totalSteps = Math.max(1, normalizeStepNumber(session.value.total_steps, 1))
		const currentStepNumber = sessionFinished.value
			? totalSteps + 1
			: normalizeStepNumber(session.value.current_step, 1)

		return Array.from({ length: totalSteps }, (_, index) => {
			const number = index + 1
			const stepKey = `步骤${number}`
			const loadedStep = stepByNumber(number)
			const title = session.value?.steps_op_prefix_dic?.[stepKey]
				|| loadedStep?.operation_prefix
				|| (number === totalSteps ? '总结与验证' : `步骤 ${number}`)
			const status : FlowStepStatus = sessionFinished.value || number < currentStepNumber
				? 'completed'
				: number === currentStepNumber
					? 'current'
					: 'pending'
			const badge = number === 1
				? '起点'
				: session.value?.in_summary && number === totalSteps
					? '总结'
					: status === 'current'
						? '当前'
						: status === 'completed'
							? '完成'
							: ''

			return { number, title, status, badge }
		})
	})

	const displayStep = computed<StepNodeData | null>(() => {
		const selectedNumber = selectedStepNumber.value
		if (selectedNumber) return stepByNumber(selectedNumber)
		if (sessionFinished.value) return null
		if (session.value?.in_summary) {
			return currentStep.value || stepByNumber(normalizeStepNumber(session.value.current_step, 1))
		}
		return currentStep.value
	})

	const displayedStepNumber = computed(() => normalizeStepNumber(displayStep.value?.step_number, 0))

	const displayStepIsCurrent = computed(() => {
		if (!session.value || sessionFinished.value || !displayStep.value) return false
		return displayedStepNumber.value === normalizeStepNumber(session.value.current_step, 1)
	})

	const displayErrorAnalysis = computed(() => displayStepIsCurrent.value ? errorAnalysis.value : '')

	const displayStepIsSummaryStep = computed(() => Boolean(
		session.value
		&& displayStep.value
		&& displayedStepNumber.value === normalizeStepNumber(session.value.total_steps, 0)
		&& (session.value.in_summary || displayStep.value.operation_summary_list.length > 0)
	))

	const displaySummaryItems = computed<SummaryReviewItem[]>(() => {
		if (!session.value || !displayStep.value || !displayStepIsSummaryStep.value) return []
		const totalSteps = normalizeStepNumber(session.value.total_steps, 0)
		return Array.from({ length: Math.max(0, totalSteps - 1) }, (_, index) => {
			const number = index + 1
			const stepKey = `步骤${number}`
			const stepData = session.value?.steps_data?.[stepKey] || {}
			const loadedStep = stepByNumber(number)
			const title = loadedStep?.operation_prefix
				|| session.value?.steps_op_prefix_dic?.[stepKey]
				|| operationPrefixFromText(stepData['具体操作'])
				|| `步骤 ${number}`
			const analysis = loadedStep?.analysis
				|| stepData['分析与提示']
				|| ''
			return {
				number,
				title: normalizeText(title).trim(),
				analysis: normalizeSummaryAnalysis(analysis)
			}
		}).filter(item => item.title || item.analysis)
	})

	const summarySteps = computed(() => Object.entries(session.value?.steps_dict || {})
		.sort(([left], [right]) => Number(left.replace(/\D/g, '')) - Number(right.replace(/\D/g, '')))
		.map(([key, content]) => ({ key, content })))

	function withDefaults(data : ExplanationInitData) : ExplanationSessionData {
		return {
			...data,
			session_id: sessionId.value,
			wrong_answers: [],
			steps_info: [],
			steps_history: []
		}
	}

	function isRecord(value : unknown) : value is Record<string, unknown> {
		return typeof value === 'object' && value !== null && !Array.isArray(value)
	}

	function isExplanationPayload(value : unknown) : value is ExplanationSessionData {
		return isRecord(value) && ('current_step' in value || 'steps_info' in value || 'question' in value)
	}

	function unwrapExplanationPayload(data : ExplanationSessionData) : ExplanationSessionData {
		const raw = data as unknown
		if (isRecord(raw) && isExplanationPayload(raw.data)) return raw.data
		return data
	}

	function normalizeStepNumber(value : unknown, fallback = 0) : number {
		if (typeof value === 'number' && Number.isFinite(value)) return value
		const source = String(value ?? '').trim()
		const directNumber = Number(source)
		if (Number.isFinite(directNumber)) return directNumber
		const match = source.match(/\d+/)
		if (!match) return fallback
		const parsed = Number(match[0])
		return Number.isFinite(parsed) ? parsed : fallback
	}

	function isSessionFinished(data : Pick<ExplanationSessionData, 'current_step' | 'in_summary'>) : boolean {
		return Boolean(data.in_summary && normalizeStepNumber(data.current_step, 0) <= 0)
	}

	function normalizeText(value : unknown) : string {
		return typeof value === 'string' ? value : String(value ?? '')
	}

	function operationPrefixFromText(value : unknown) : string {
		const text = normalizeText(value).trim()
		if (!text) return ''
		const [prefix] = text.split(/[:：]/)
		return prefix?.trim() || text
	}

	function normalizeSummaryAnalysis(value : unknown) : string {
		return normalizeText(value)
			.trim()
			.replace(/^分析与提示\s*[:：]\s*/, '')
			.trim()
	}

	function normalizeStringList(value : unknown) : string[] {
		return Array.isArray(value) ? value.map(item => normalizeText(item)) : []
	}

	function optionLetter(index : number) : string {
		return String.fromCharCode(65 + index)
	}

	function stepByNumber(stepNumber : number) : StepNodeData | null {
		if (!session.value) return null
		const activeStep = currentStep.value
		if (activeStep && normalizeStepNumber(activeStep.step_number) === stepNumber) {
			return activeStep
		}

		const loadedStep = session.value.steps_info.find(item => normalizeStepNumber(item.step_number) === stepNumber)
		if (loadedStep) return loadedStep
		const historyStep = (session.value.steps_history || []).find(
			item => normalizeStepNumber(item.step_number) === stepNumber
		)
		if (historyStep) return historyStep

		const stepKey = `步骤${stepNumber}`
		const stepData = session.value.steps_data?.[stepKey] || {}
		const analysis = stepData['分析与提示'] || session.value.steps_dict?.[stepKey] || ''
		const operationPrefix = session.value.steps_op_prefix_dic?.[stepKey] || stepData['具体操作'] || ''
		if (!analysis && !operationPrefix) return null

		return normalizeStepNode({
			step_number: stepNumber,
			analysis,
			operation_prefix: operationPrefix,
			options: [],
			operation_summary_list: normalizeStringList(stepData['操作总结']),
			user_selected_option: ''
		})
	}

	function canSelectFlowStep(stepNumber : number) : boolean {
		if (!session.value || !stepByNumber(stepNumber)) return false
		if (sessionFinished.value) return true
		return stepNumber <= normalizeStepNumber(session.value.current_step, 1)
	}

	function selectFlowStep(stepNumber : number) : void {
		if (!canSelectFlowStep(stepNumber)) return
		const currentStepNumber = normalizeStepNumber(session.value?.current_step, 1)
		const activeStep = currentStep.value
		const currentStepCanRender = Boolean(
			activeStep && normalizeStepNumber(activeStep.step_number) === currentStepNumber
		)
		if (!sessionFinished.value && stepNumber === currentStepNumber && currentStepCanRender) {
			selectedStepNumber.value = null
			return
		}
		selectedStepNumber.value = selectedStepNumber.value === stepNumber ? null : stepNumber
	}

	function isSelectedOption(option : string, index : number, selected : string) : boolean {
		return selected === optionLetter(index) || selected === option
	}

	function isDisplayOptionSelected(option : string, index : number) : boolean {
		const step = displayStep.value
		const selected = displayStepIsCurrent.value
			? selectedOption.value
			: selectedOptionForStep(step)
		return isSelectedOption(option, index, selected)
	}

	function selectOption(index : number) : void {
		selectedOption.value = optionLetter(index)
	}

	function handleOptionTap(index : number) : void {
		if (!displayStepIsCurrent.value) return
		selectOption(index)
	}

	function normalizeSelectedOption(value : string, options : string[] = []) : string {
		const selected = normalizeText(value).trim()
		if (!selected) return ''
		const upper = selected.toUpperCase()
		if (/^[A-Z]$/.test(upper)) return upper

		const matchedIndex = options.findIndex(option => option.trim() === selected)
		return matchedIndex >= 0 ? optionLetter(matchedIndex) : selected
	}

	function selectedOptionForStep(step : StepNodeData | null) : string {
		if (!step) return ''
		const stepNumber = normalizeStepNumber(step.step_number)
		return step.user_selected_option || stepSelectedOptions.value[String(stepNumber)] || ''
	}

	function rememberStepSelection(stepNumber : number, selected : string, options : string[] = []) : string {
		const normalizedSelected = normalizeSelectedOption(selected, options)
		if (!stepNumber || !normalizedSelected) return ''
		stepSelectedOptions.value = {
			...stepSelectedOptions.value,
			[String(stepNumber)]: normalizedSelected
		}
		return normalizedSelected
	}

	function normalizeErrorAnalysis(value : string) : string {
		const text = normalizeText(value).trim()
		return text === 'True' || text === 'true' ? '' : text
	}

	function normalizeStepNode(step : StepNodeData) : StepNodeData {
		const raw = step as unknown as Record<string, unknown>
		const options = normalizeStringList(raw.options)
		return {
			...step,
			step_number: normalizeStepNumber(raw.step_number),
			analysis: normalizeText(raw.analysis),
			operation_prefix: normalizeText(raw.operation_prefix),
			options,
			operation_summary_list: normalizeStringList(raw.operation_summary_list),
			user_selected_option: normalizeSelectedOption(normalizeText(raw.user_selected_option), options)
		}
	}

	function normalizeStepsInfo(value : unknown) : StepNodeData[] {
		if (!Array.isArray(value)) return []
		return value
			.map(item => normalizeStepNode(item as StepNodeData))
			.sort((left, right) => left.step_number - right.step_number)
	}

	function mergeRememberedSelections(
		steps : StepNodeData[],
		currentStepNumber : number,
		inSummary : boolean
	) : StepNodeData[] {
		const rememberedSelections = { ...stepSelectedOptions.value }

		const existingSteps = [
			...(session.value?.steps_info || []),
			...(session.value?.steps_history || [])
		]
		existingSteps.forEach(step => {
			const stepNumber = normalizeStepNumber(step.step_number)
			const selected = normalizeSelectedOption(step.user_selected_option, step.options)
			if (stepNumber && selected) rememberedSelections[String(stepNumber)] = selected
		})

		const mergedSteps = steps.map(step => {
			const stepNumber = normalizeStepNumber(step.step_number)
			const selected = normalizeSelectedOption(step.user_selected_option, step.options)
			if (stepNumber && selected) {
				rememberedSelections[String(stepNumber)] = selected
				return step
			}

			const rememberedSelected = rememberedSelections[String(stepNumber)]
			if (!rememberedSelected || (!inSummary && stepNumber >= currentStepNumber)) return step

			return normalizeStepNode({
				...step,
				user_selected_option: rememberedSelected
			})
		})

		stepSelectedOptions.value = rememberedSelections
		return mergedSteps
	}

	function markStepSelection(stepNumber : number, selected : string, options : string[] = []) : void {
		if (!session.value) return
		const normalizedSelected = rememberStepSelection(stepNumber, selected, options)
		if (!normalizedSelected) return

		const fromStepsInfo = session.value.steps_info.some(item => normalizeStepNumber(item.step_number) === stepNumber)
		const fromStepsHistory = (session.value.steps_history || []).some(
			item => normalizeStepNumber(item.step_number) === stepNumber
		)
		const stepToUpdate = fromStepsInfo
			? session.value.steps_info.find(item => normalizeStepNumber(item.step_number) === stepNumber)
			: (session.value.steps_history || []).find(item => normalizeStepNumber(item.step_number) === stepNumber)
		if (!stepToUpdate) return

		const updatedStep = normalizeStepNode({
			...stepToUpdate,
			user_selected_option: normalizedSelected
		})
		const updatedSession : ExplanationSessionData = {
			...session.value,
			steps_info: fromStepsInfo
				? [
					...session.value.steps_info.filter(item => normalizeStepNumber(item.step_number) !== stepNumber),
					updatedStep
				].sort((left, right) => left.step_number - right.step_number)
				: session.value.steps_info,
			steps_history: fromStepsHistory
				? [
					...(session.value.steps_history || []).filter(item => normalizeStepNumber(item.step_number) !== stepNumber),
					updatedStep
				].sort((left, right) => left.step_number - right.step_number)
				: session.value.steps_history || []
		}

		session.value = updatedSession
		if (currentStep.value && normalizeStepNumber(currentStep.value.step_number) === stepNumber) {
			currentStep.value = updatedStep
		}
		sessionStore.setExplanation(updatedSession)
	}

	function applySession(data : ExplanationSessionData, preserveLocalStep = false) : void {
		const payload = unwrapExplanationPayload(data)
		const existingCurrentStep = currentStep.value
		const currentStepNumber = normalizeStepNumber(payload.current_step, 1)
		const normalizedData : ExplanationSessionData = {
			...payload,
			session_id: sessionId.value,
			current_step: currentStepNumber,
			total_steps: normalizeStepNumber(payload.total_steps, currentStepNumber),
			wrong_answers: payload.wrong_answers || [],
			steps_info: normalizeStepsInfo(payload.steps_info),
			steps_history: normalizeStepsInfo(payload.steps_history)
		}
		normalizedData.steps_info = mergeRememberedSelections(
			normalizedData.steps_info,
			currentStepNumber,
			Boolean(normalizedData.in_summary)
		)
		normalizedData.steps_history = mergeRememberedSelections(
			normalizedData.steps_history || [],
			currentStepNumber,
			Boolean(normalizedData.in_summary)
		)

		// 若 getstep 在 sessionstate 请求期间完成，始终以本地刚生成的当前步骤为准，
		// 避免迟到的旧快照（包括同一步骤的旧版本）覆盖新结果。
		if (
			preserveLocalStep
			&& existingCurrentStep
			&& normalizeStepNumber(existingCurrentStep.step_number) === normalizedData.current_step
		) {
			const existingStep = normalizeStepNode(existingCurrentStep)
			normalizedData.steps_info = [
				...normalizedData.steps_info.filter(item => item.step_number !== existingStep.step_number),
				existingStep
			].sort((left, right) => left.step_number - right.step_number)
		}

		let activeStep = (normalizedData.in_summary
			? normalizedData.steps_history?.find(item => item.step_number === normalizedData.current_step)
			: normalizedData.steps_info.find(item => item.step_number === normalizedData.current_step)) || null
		if (!activeStep && !isSessionFinished(normalizedData) && existingCurrentStep
			&& normalizeStepNumber(existingCurrentStep.step_number) === normalizedData.current_step) {
			activeStep = normalizeStepNode(existingCurrentStep)
		}

		if (selectedStepNumber.value) {
			const hasSelectedStep = [
				...normalizedData.steps_info,
				...(normalizedData.steps_history || [])
			].some(
				item => normalizeStepNumber(item.step_number) === selectedStepNumber.value
			)
			const selectedIsCurrent = !isSessionFinished(normalizedData)
				&& selectedStepNumber.value === normalizedData.current_step
			if (!hasSelectedStep || (selectedIsCurrent && activeStep)) selectedStepNumber.value = null
		}

		session.value = normalizedData
		currentStep.value = activeStep
		selectedOption.value = currentStep.value?.user_selected_option || ''
		sessionStore.setExplanation(normalizedData)
	}

	function applyStep(step : StepNodeData, preserveErrorAnalysis = false) : void {
		if (!session.value) return
		const normalizedStep = normalizeStepNode(step)
		const preservedErrorAnalysis = preserveErrorAnalysis ? errorAnalysis.value : ''
		const storeAsHistoryStep = Boolean(session.value.in_summary && !sessionFinished.value)

		const updatedSession : ExplanationSessionData = {
			...session.value,
			session_id: sessionId.value,
			steps_info: storeAsHistoryStep
				? session.value.steps_info
				: [
					...session.value.steps_info.filter(item => normalizeStepNumber(item.step_number) !== normalizedStep.step_number),
					normalizedStep
				].sort((left, right) => left.step_number - right.step_number),
			steps_history: storeAsHistoryStep
				? [
					...(session.value.steps_history || []).filter(
						item => normalizeStepNumber(item.step_number) !== normalizedStep.step_number
					),
					normalizedStep
				].sort((left, right) => left.step_number - right.step_number)
				: session.value.steps_history || []
		}

		// 同一同步调用内一次性提交页面状态和 Pinia 状态，避免中间态被渲染或覆盖。
		stepStateRevision += 1
		session.value = updatedSession
		currentStep.value = normalizedStep
		selectedStepNumber.value = null
		selectedOption.value = normalizedStep.user_selected_option || ''
		errorAnalysis.value = preservedErrorAnalysis
		sessionStore.setExplanation(updatedSession)
	}

	async function fetchState(allowStoreFallback = false) : Promise<void> {
		const revisionAtRequest = stepStateRevision
		try {
			const response = await explanationApi.getState(sessionId.value)
			applySession({
				...response.data,
				session_id: sessionId.value
			}, stepStateRevision !== revisionAtRequest)
		} catch (error) {
			const cached = sessionStore.explanation
			if (allowStoreFallback && cached?.session_id === sessionId.value) {
				applySession('steps_info' in cached ? cached : withDefaults(cached))
				return
			}
			throw error
		}
	}

	async function loadCurrentStep(options : LoadCurrentStepOptions = {}) : Promise<void> {
		if (!session.value || sessionFinished.value || stepLoading.value) return
		stepLoading.value = true
		loadingText.value = options.loadingText || '正在生成当前步骤…'
		try {
			const response = await explanationApi.getStep(sessionId.value, { isCancelled: () => hidden.value })
			applyStep(response.data, Boolean(options.preserveErrorAnalysis))
		} catch (error) {
			if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) showApiError(error, '步骤加载失败')
		} finally {
			stepLoading.value = false
			pendingStore.restore()
		}
	}

	async function restoreInteraction() : Promise<void> {
		const pending = pendingStore.restore()
		if (!pending || pending.session_id !== sessionId.value || !pending.task_type.startsWith('EXPLANATION_')) return
		if (pending.task_type === 'EXPLANATION_INIT') return

		loadingText.value = '正在恢复未完成的讲解任务…'
		if (pending.task_type === 'EXPLANATION_GET_STEP') {
			const response = await resumePendingTask<StepNodeData>({ isCancelled: () => hidden.value })
			if (response) applyStep(response.data)
		} else {
			await resumePendingTask<ExplanationCheckData>({ isCancelled: () => hidden.value })
			await fetchState(true)
		}
	}

	async function initializeOnce() : Promise<void> {
		loading.value = true
		try {
			await fetchState(true)
			await restoreInteraction()
			if (session.value && !sessionFinished.value && !currentStep.value) await loadCurrentStep()
			initialized.value = Boolean(session.value && (sessionFinished.value || currentStep.value))
		} catch (error) {
			if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) {
				showApiError(error, '讲解会话加载失败')
				setTimeout(() => uni.navigateBack(), 800)
			}
		} finally {
			loading.value = false
			pendingStore.restore()
		}
	}

	function initialize() : Promise<void> {
		if (!sessionId.value || initialized.value) return Promise.resolve()
		if (initializePromise) return initializePromise

		initializePromise = initializeOnce().finally(() => {
			initializePromise = null
		})
		return initializePromise
	}

	async function submitAnswer() : Promise<void> {
		if (!session.value || !selectedOption.value || submitting.value || !displayStepIsCurrent.value) return
		const submittedOption = selectedOption.value
		const submittedStepNumber = normalizeStepNumber(displayStep.value?.step_number, session.value.current_step)
		const submittedStepOptions = displayStep.value?.options || []
		hidden.value = false
		submitting.value = true
		errorAnalysis.value = ''
		try {
			const response = await explanationApi.submitAnswer({
				session_id: sessionId.value,
				option: submittedOption
			}, { isCancelled: () => hidden.value })
			const result = response.data
			const nextErrorAnalysis = normalizeErrorAnalysis(result.error_analysis)
			const nextFinished = Boolean(result.in_summary && normalizeStepNumber(result.current_step, 0) <= 0)
			markStepSelection(submittedStepNumber, submittedOption, submittedStepOptions)
			session.value.current_step = result.current_step
			session.value.total_steps = result.total_steps
			session.value.in_summary = result.in_summary
			session.value.knowledge_points = result.knowledge_points || session.value.knowledge_points
			session.value.wrong_answers = result.wrong_answers || session.value.wrong_answers
			errorAnalysis.value = nextErrorAnalysis
			sessionStore.setExplanation(session.value)

			if (nextFinished) {
				await fetchState(true)
			} else if (!nextErrorAnalysis) {
				selectedOption.value = ''
				currentStep.value = null
				await loadCurrentStep()
			} else {
				selectedOption.value = ''
				currentStep.value = null
				await loadCurrentStep({
					preserveErrorAnalysis: true,
					loadingText: '选项正在加载中'
				})
			}

			await explanationApi.updateState({
				session_id: sessionId.value,
				imageCode: session.value.imageCode || [],
				current_step: session.value.current_step,
				in_summary: session.value.in_summary,
				wrong_answers: session.value.wrong_answers
			}).catch(() => undefined)
		} catch (error) {
			if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) showApiError(error, '答案提交失败')
		} finally {
			submitting.value = false
			pendingStore.restore()
		}
	}

	function backToQuestion() : void {
		uni.switchTab({ url: '/pages/question/index' })
	}

	onLoad((query ?: AnyObject) => {
		if (!requireAuthentication()) return
		sessionId.value = typeof query?.sessionId === 'string' ? decodeURIComponent(query.sessionId) : ''
		if (!sessionId.value) {
			uni.showToast({ title: '缺少会话 ID', icon: 'none' })
			setTimeout(() => uni.navigateBack(), 500)
			return
		}
		void initialize()
	})

	onShow(() => {
		hidden.value = false
		if (sessionId.value && !initialized.value) void initialize()
	})

	onHide(() => {
		hidden.value = true
	})
</script>

<style scoped lang="scss">
	.explanation-page {
		max-width: $content-max;
		padding-bottom: 68rpx;
		margin: 0 auto;
	}

	.question-card,
	.step-card,
	.summary-card {
		padding: 28rpx;
		margin-bottom: 22rpx;
	}

	.question-card {
		border-left: 5rpx solid $primary;
		background: linear-gradient(180deg, $surface 0%, #f6faf8 100%);
		box-shadow: $shadow-soft;
	}

	.step-card,
	.summary-card {
		box-shadow: $shadow-raised;
	}

	.step-card {
		overflow: hidden;
	}

	.card-label {
		display: block;
		margin-bottom: 16rpx;
		color: $primary;
		font-size: 22rpx;
		font-weight: 600;
		letter-spacing: 2rpx;
	}

	.question-plain {
		display: block;
		color: $text-primary;
		white-space: pre-wrap;
		word-break: break-word;
		line-height: 1.72;
	}

	.image-strip {
		width: 100%;
		margin-top: 20rpx;
		white-space: nowrap;
	}

	.image-strip image {
		width: 240rpx;
		height: 180rpx;
		margin-right: 16rpx;
		border-radius: 8rpx;
		background: $surface-muted;
	}

	.knowledge-card {
		padding: 0;
		margin-bottom: 24rpx;
		overflow: hidden;
		box-shadow: $shadow-soft;
	}

	.knowledge-card-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 20rpx;
		padding: 24rpx 26rpx;
	}

	.knowledge-card-header:active {
		background: $surface-muted;
	}

	.knowledge-title-wrap {
		display: flex;
		min-width: 0;
		flex-direction: column;
		gap: 5rpx;
	}

	.knowledge-title {
		color: $text-primary;
		font-size: 29rpx;
		font-weight: 700;
	}

	.knowledge-count {
		color: $text-muted;
		font-size: 21rpx;
	}

	.knowledge-toggle {
		flex: none;
		width: 18rpx;
		height: 18rpx;
		border-right: 3rpx solid $primary;
		border-bottom: 3rpx solid $primary;
		transform: rotate(45deg);
		transition: transform $motion-fast ease;
	}

	.knowledge-toggle.expanded {
		transform: rotate(225deg);
	}

	.knowledge-content {
		padding: 0 26rpx 24rpx;
		border-top: 1rpx solid $border;
	}

	.knowledge-row {
		display: flex;
		gap: 16rpx;
		padding: 22rpx 0;
		border-bottom: 1rpx solid $border;
	}

	.knowledge-row:last-child {
		padding-bottom: 0;
		border-bottom: 0;
	}

	.knowledge-index {
		flex: none;
		width: 42rpx;
		height: 42rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 8rpx;
		background: $primary-light;
		color: $primary;
		font-size: 22rpx;
		font-weight: 700;
	}

	.knowledge-copy {
		display: flex;
		flex: 1;
		min-width: 0;
		flex-direction: column;
		gap: 8rpx;
	}

	.knowledge-name {
		color: $primary-dark;
		font-weight: 700;
		line-height: 1.45;
	}

	.knowledge-copy :deep(.rich-shell) {
		color: $text-secondary;
		font-size: 22rpx;
		line-height: 1.55;
	}

	.flow-card {
		padding: 24rpx 0 26rpx;
		margin-bottom: 22rpx;
		overflow: hidden;
		box-shadow: $shadow-soft;
	}

	.flow-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 18rpx;
		padding: 0 26rpx 18rpx;
	}

	.flow-title {
		color: $text-primary;
		font-size: 29rpx;
		font-weight: 700;
	}

	.flow-meta {
		flex: none;
		color: $primary;
		font-size: 22rpx;
		font-weight: 600;
	}

	.flow-scroll {
		width: 100%;
		height: 176rpx;
		white-space: nowrap;
	}

	.flow-chain {
		display: inline-flex;
		align-items: stretch;
		min-width: 100%;
		box-sizing: border-box;
		padding: 0 26rpx 2rpx;
	}

	.flow-node {
		flex: none;
		width: 190rpx;
		min-height: 132rpx;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		gap: 10rpx;
		padding: 18rpx;
		border: 2rpx solid $border;
		border-radius: 10rpx;
		background: $surface;
		color: $text-secondary;
		white-space: normal;
		transition: border-color $motion-fast ease, background-color $motion-fast ease, box-shadow $motion-fast ease, transform $motion-fast ease;
	}

	.flow-node.clickable:active {
		transform: translateY(2rpx) scale(.99);
	}

	.flow-node.completed {
		border-color: $primary;
		background: $primary;
		color: $surface;
	}

	.flow-node.current {
		border-color: $primary;
		background: $primary;
		color: $surface;
		box-shadow: 0 12rpx 28rpx rgba(36, 95, 89, .18);
	}

	.flow-node.pending {
		border-color: $border;
		background: $surface-raised;
		color: $text-secondary;
	}

	.flow-node.active {
		box-shadow: 0 0 0 5rpx rgba(36, 95, 89, .12), 0 14rpx 30rpx rgba(36, 95, 89, .18);
	}

	.flow-node.completed.active,
	.flow-node.current.active {
		border-color: $primary-dark;
		background: $primary-dark;
	}

	.flow-node-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8rpx;
		min-height: 30rpx;
	}

	.flow-step-number {
		color: inherit;
		font-size: 23rpx;
		font-weight: 700;
	}

	.flow-badge {
		flex: none;
		padding: 3rpx 8rpx;
		border-radius: 7rpx;
		background: $surface;
		color: $primary;
		font-size: 18rpx;
		font-weight: 700;
	}

	.flow-node.pending .flow-badge {
		background: $surface-muted;
		color: $text-muted;
	}

	.flow-step-title {
		display: -webkit-box;
		overflow: hidden;
		color: inherit;
		font-size: 22rpx;
		line-height: 1.45;
		word-break: break-word;
		-webkit-line-clamp: 2;
		-webkit-box-orient: vertical;
	}

	.flow-connector {
		flex: none;
		width: 44rpx;
		height: 4rpx;
		margin: 64rpx 10rpx 0;
		border-radius: 4rpx;
		background: $primary;
	}

	.flow-connector.pending {
		background: $border-strong;
	}

	.flow-connector.active {
		background: $primary;
	}

	.section-title {
		display: block;
		margin-bottom: 22rpx;
		color: $text-primary;
		font-size: 31rpx;
		font-weight: 700;
		letter-spacing: -.5rpx;
	}

	.step-operation-heading {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 0;

		margin-bottom: 10rpx;
		padding: 4rpx 0 4rpx 22rpx;

		background: transparent;
	}

	.step-operation-heading::before {
		position: absolute;
		top: 4rpx;
		bottom: 4rpx;
		left: 0;

		width: 5rpx;
		border-radius: 4rpx;
		background: $primary;

		content: '';
	}

	.step-operation-label {
		color: $primary;
		font-size: 21rpx;
		font-weight: 600;
		line-height: 1.35;
		letter-spacing: 1rpx;
	}

	.step-operation-title {
		color: $text-primary;
	}

	.step-operation-title :deep(.rich-shell) {
		color: $text-primary;
	}

	.step-operation-title :deep(.rich-content) {
		font-size: 30rpx;
		font-weight: 700;
		line-height: 1.5;
	}

	.step-analysis-block {
		color: $text-secondary;
	}

	.step-analysis-block .section-title {
		margin-bottom: 16rpx;
		font-size: 29rpx;
	}

	.thinking-summary {
		margin: 24rpx 0 22rpx;
		padding: 22rpx;
		border: 1rpx solid rgba(36, 95, 89, .16);
		border-left: 5rpx solid $primary;
		border-radius: 8rpx;
		background: linear-gradient(180deg, $primary-light 0%, $surface 100%);
	}

	.thinking-summary-title {
		display: block;
		color: $primary-dark;
		font-size: 28rpx;
		font-weight: 700;
		line-height: 1.4;
	}

	.thinking-summary-intro {
		display: block;
		margin-top: 6rpx;
		color: $text-muted;
		font-size: 23rpx;
		line-height: 1.45;
	}

	.thinking-summary-list {
		display: flex;
		flex-direction: column;
		gap: 0;
		margin-top: 18rpx;
	}

	.thinking-summary-item {
		display: flex;
		flex-direction: column;
		gap: 0;
		padding: 16rpx 0 18rpx;
		border-bottom: 1rpx solid rgba(36, 95, 89, .12);
	}

	.thinking-summary-item:last-child {
		padding-bottom: 0;
		border-bottom: 0;
	}

	.thinking-summary-head {
		display: flex;
		align-items: center;
		gap: 14rpx;
	}

	.thinking-summary-index {
		flex: none;
		width: 36rpx;
		height: 36rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 8rpx;
		background: $primary;
		color: $surface;
		font-size: 20rpx;
		font-weight: 700;
		line-height: 36rpx;
	}

	.thinking-summary-step-title {
		flex: 1;
		min-width: 0;
		color: $text-primary;
	}

	.thinking-summary-step-title :deep(.rich-shell) {
		color: $text-primary;
		margin: 0;
		padding: 0;
	}

	.thinking-summary-step-title :deep(.rich-content) {
		font-size: 25rpx;
		font-weight: 700;
		line-height: 1.32;
		margin: 0;
		padding: 0;
	}

	.thinking-summary-analysis {
		margin-top: 0;
		margin-left: 0;
		padding: 0;
		border-left: 0;
		background: transparent;
		color: $text-secondary;
	}

	.thinking-summary-analysis :deep(.rich-shell) {
		margin: 0;
		padding: 0;
	}

	.thinking-summary-analysis :deep(.rich-content) {
		font-size: 23rpx;
		line-height: 1.42;
		margin: 0;
		padding: 0;
	}
	
	.thinking-summary-step-title :deep(.rich-shell),
	.thinking-summary-step-title :deep(.rich-content),
	.thinking-summary-analysis :deep(.rich-shell),
	.thinking-summary-analysis :deep(.rich-content) {
	    margin: 0;
	    padding: 0;
	}
	
	.options {
		display: flex;
		flex-direction: column;
		gap: 10rpx;
		margin: 20rpx 0 22rpx;
	}

	.option {
		display: flex;
		align-items: center;
		gap: 12rpx;
		padding: 10rpx 14rpx;
		border: 1rpx solid $border;
		border-radius: 8rpx;
		background: $surface;
		transition: border-color $motion-fast ease, background-color $motion-fast ease, transform $motion-fast ease;
	}

	.option:active {
		transform: translateY(1rpx) scale(.996);
	}

	.option.selected {
		border-color: $primary;
		background: $primary-light;
		box-shadow: inset 5rpx 0 0 $primary;
	}

	.option.readonly {
		background: $surface-raised;
		color: $text-secondary;
		box-shadow: none;
	}

	.option.readonly:active {
		transform: none;
	}

	.option.readonly.selected {
		border-color: $primary;
		background: $primary-light;
		box-shadow: inset 5rpx 0 0 $primary;
	}

	.option-letter {
		flex: none;
		min-width: 30rpx;
		height: 30rpx;
		padding: 0 7rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 6rpx;
		background: $surface-muted;
		color: $text-secondary;
		font-size: 20rpx;
		font-weight: 700;
		line-height: 30rpx;
	}

	.option.selected .option-letter {
		background: $primary;
		color: $surface;
	}

	.option :deep(.rich-shell) {
		flex: 1;
		min-width: 0;
	}

	.option :deep(.rich-content) {
		font-size: 24rpx;
		line-height: 1.5;
	}

	.history-step-note {
		margin-top: 18rpx;
		padding: 16rpx 18rpx;
		border-left: 5rpx solid $border-strong;
		border-radius: 8rpx;
		background: $surface-raised;
		color: $text-muted;
		font-size: 23rpx;
		line-height: 1.45;
	}

	.error-analysis {
		padding: 22rpx;
		margin: 0 0 24rpx;
		border-left: 5rpx solid $warning;
		background: $warning-light;
		color: #7d4528;
	}

	.error-title {
		display: block;
		margin-bottom: 12rpx;
		font-weight: 700;
	}

	.summary-step {
		display: grid;
		grid-template-columns: 54rpx 1fr;
		gap: 16rpx;
		padding: 24rpx 0;
		border-bottom: 1rpx solid $border;
	}

	.summary-index {
		width: 48rpx;
		height: 48rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 7rpx;
		background: $primary-light;
		color: $primary;
		font-weight: 700;
	}

	.wrong-summary {
		padding: 24rpx;
		margin: 26rpx 0;
		border-left: 5rpx solid $danger;
		background: $danger-light;
	}

	.wrong-item {
		padding: 16rpx 0;
		border-bottom: 1rpx solid #e9cfcc;
	}

	.wrong-item:last-child {
		border-bottom: none;
	}

	.missing-step {
		padding: 34rpx 28rpx 36rpx;
		text-align: center;
		box-shadow: $shadow-soft;
	}

	.option-loading-state,
	.missing-retry-state {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12rpx;
	}

	.option-loading-mark {
		width: 76rpx;
		height: 50rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 8rpx;
		border-radius: 10rpx;
		background: $primary-light;
		box-shadow: inset 0 0 0 1rpx rgba(36, 95, 89, .12);
	}

	.option-loading-dot {
		width: 9rpx;
		height: 9rpx;
		border-radius: 50%;
		background: $primary;
		animation: optionLoadingPulse 1.05s ease-in-out infinite;
	}

	.dot-two {
		animation-delay: .15s;
	}

	.dot-three {
		animation-delay: .3s;
	}

	.missing-title {
		color: $text-primary;
		font-size: 29rpx;
		font-weight: 700;
		line-height: 1.35;
	}

	.missing-hint {
		max-width: 520rpx;
		color: $text-muted;
		font-size: 23rpx;
		line-height: 1.5;
	}

	.option-skeleton-list {
		width: 100%;
		display: flex;
		flex-direction: column;
		gap: 10rpx;
		margin-top: 12rpx;
	}

	.option-skeleton {
		height: 54rpx;
		border-radius: 8rpx;
		background: linear-gradient(90deg, $surface-muted 0%, $surface-raised 46%, $surface-muted 100%);
		background-size: 220% 100%;
		animation: optionSkeleton 1.25s ease-in-out infinite;
	}

	.missing-error-analysis {
		box-sizing: border-box;
		width: 100%;
		margin: 24rpx 0 0;
		text-align: left;
	}

	.missing-step button {
		width: auto;
		height: 72rpx;
		margin-top: 24rpx;
		padding: 0 30rpx;
		border: 1rpx solid $border-strong;
		border-radius: 8rpx;
		background: $primary-light;
		color: $primary;
		line-height: 70rpx;
	}

	.missing-step button:active {
		transform: translateY(1rpx);
	}

	@keyframes optionLoadingPulse {
		0%,
		80%,
		100% {
			opacity: .36;
			transform: scale(.72);
		}

		40% {
			opacity: 1;
			transform: scale(1);
		}
	}

	@keyframes optionSkeleton {
		0% {
			background-position: 120% 0;
		}

		100% {
			background-position: -120% 0;
		}
	}
</style>
