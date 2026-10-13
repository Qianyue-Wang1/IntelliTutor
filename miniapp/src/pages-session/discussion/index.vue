<template>
  <view class="discussion-page">
    <LoadingState v-if="loading" :text="loadingText" hint="会话可在切后台或重启后继续恢复" />

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
          <view
            v-for="(point, index) in session.knowledge_points"
            :key="`${point.title}-${index}`"
            class="knowledge-row"
          >
            <text class="knowledge-index">{{ index + 1 }}</text>
            <view class="knowledge-copy">
              <text class="knowledge-name">{{ point.title }}</text>
              <SafeRichContent :content="point.description" />
            </view>
          </view>
        </view>
      </view>

      <view class="tree-panel card">
        <view class="tree-title" @tap="treeExpanded = !treeExpanded">
          <text>解题步骤</text>
          <text>{{ treeExpanded ? '收起' : '展开' }}</text>
        </view>
        <view v-if="treeExpanded" class="tree-content">
          <view class="tree-debug">
            <text>tree count: {{ session.discussion_tree.length }}</text>
          </view>
          <DiscussionTree
            v-if="session.discussion_tree.length > 0"
            :nodes="session.discussion_tree"
          />
          <view v-else class="tree-empty">
            <text>当前 discussion_tree 为空</text>
          </view>
        </view>
      </view>

      <scroll-view class="message-scroll" scroll-y :scroll-into-view="scrollTarget">
        <view class="messages">
          <view
            v-for="(message, index) in session.messages_history"
            :id="`message-${index}`"
            :key="`${index}-${message.time}`"
            :class="['message-row', message.type === 'user' ? 'user' : 'assistant']"
          >
            <view class="message-bubble">
              <SafeRichContent v-if="message.type !== 'user'" :content="formatAssistantMessageContent(message.content)" />
              <text v-else selectable>{{ message.content }}</text>
              <text class="message-time">{{ formatTime(message.time) }}</text>
            </view>
          </view>

          <view v-if="sending" class="message-row assistant">
            <view class="message-bubble typing">正在思考…</view>
          </view>

          <view v-if="session.is_complete" class="complete-card">
            <text class="complete-title">研讨完成</text>
            <SafeRichContent :content="session.summary_text || '你已经完成了本次研讨。'" />
            <button class="primary-button" @tap="backToQuestion">继续提问</button>
          </view>
        </view>
      </scroll-view>

      <view v-if="!session.is_complete" class="input-bar">
        <textarea
          v-model="feedback"
          class="feedback-input"
          auto-height
          maxlength="2000"
          placeholder="写下你的思考或疑问…"
          @focus="track('input_focus')"
          @input="trackInput"
        />
        <button class="send-button" :disabled="sending || !feedback.trim()" @tap="submitFeedback">发送</button>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { onHide, onLoad, onShow } from '@dcloudio/uni-app'
import { discussionApi } from '@/api'
import { ApiError, showApiError } from '@/api/request'
import { resumePendingTask } from '@/api/task'
import type { DiscussionFeedbackData, DiscussionSessionData, DiscussionStep, DiscussionUpdateStateInput } from '@/api/types'
import DiscussionTree from '@/pages-session/components/DiscussionTree.vue'
import LoadingState from '@/components/LoadingState.vue'
import SafeRichContent from '@/pages-session/components/SafeRichContent.vue'
import { requireAuthentication } from '@/composables/useAuthGuard'
import { useAuthStore } from '@/stores/auth'
import { usePendingTaskStore } from '@/stores/pending-task'
import { useSessionStore } from '@/stores/session'
import { MiniBehaviorTracker } from '@/utils/behaviorTracker'
import { mergeDiscussionFeedback, normalizeDiscussionSession } from './state'

const authStore = useAuthStore()
const sessionStore = useSessionStore()
const pendingStore = usePendingTaskStore()
const sessionId = ref('')
const session = ref<DiscussionSessionData | null>(null)
const feedback = ref('')
const loading = ref(true)
const loadingText = ref('正在恢复研讨…')
const sending = ref(false)
const hidden = ref(false)
const initialized = ref(false)
const treeExpanded = ref(true)
const knowledgeExpanded = ref(true)
const scrollTarget = ref('')
let tracker: MiniBehaviorTracker | null = null
let inputEventCounter = 0

interface NumberedDiscussionStep {
  number: string
  content: string
}

const safeImages = computed(() => (session.value?.images || []).filter(item =>
  item.url.startsWith('data:image/') || item.url.startsWith('wxfile://') || item.url.startsWith('blob:')
))

const numberedDiscussionSteps = computed<NumberedDiscussionStep[]>(() =>
  flattenDiscussionSteps(session.value?.discussion_tree || [])
)

const updatePayload = computed<DiscussionUpdateStateInput | null>(() => {
  if (!session.value) return null
  return {
    session_id: session.value.session_id,
    question: session.value.question,
    images: session.value.images || [],
    imageCode: session.value.imageCode || [],
    current_step: session.value.current_step,
    discussion_tree: session.value.discussion_tree,
    messages_history: session.value.messages_history,
    knowledge_points: session.value.knowledge_points,
    is_complete: session.value.is_complete,
    summary_text: session.value.summary_text || ''
  }
})

function isFullDiscussion(data: unknown): data is DiscussionSessionData {
  return typeof data === 'object' && data !== null && 'question' in data && 'current_step' in data
}

function applySession(data: DiscussionSessionData): void {
  const normalizedSession = normalizeDiscussionSession(data)
  session.value = normalizedSession
  sessionStore.setDiscussion(normalizedSession)
  tracker = normalizedSession.personalized_mode ? new MiniBehaviorTracker() : null
  scrollToBottom()
}

function scrollToBottom(): void {
  void nextTick(() => {
    const count = session.value?.messages_history.length || 0
    scrollTarget.value = count ? `message-${count - 1}` : ''
  })
}

async function fetchState(allowStoreFallback = false): Promise<void> {
  try {
    const response = await discussionApi.getState(sessionId.value)
    applySession(response.data)
  } catch (error) {
    const cached = sessionStore.discussion
    if (allowStoreFallback && cached?.session_id === sessionId.value && isFullDiscussion(cached)) {
      applySession(cached)
      return
    }
    throw error
  }
}

function applyFeedback(result: DiscussionFeedbackData): void {
  if (!session.value) return
  const nextSession = mergeDiscussionFeedback(session.value, result)
  session.value = nextSession
  sessionStore.setDiscussion(nextSession)
  scrollToBottom()
}

function formatAssistantMessageContent(content: string): string {
  const value = String(content || '').trim()
  if (!value || /^步骤\s*\d+(?:\.\d+)*\s*[:：]/.test(value)) return value

  const normalizedValue = normalizeStepContentForCompare(value)
  const matchedStep = numberedDiscussionSteps.value.find(
    step => normalizeStepContentForCompare(step.content) === normalizedValue
  )
  return matchedStep ? `步骤${matchedStep.number}：${value}` : value
}

function flattenDiscussionSteps(
  steps: DiscussionStep[],
  parentNumber = ''
): NumberedDiscussionStep[] {
  const result: NumberedDiscussionStep[] = []
  steps.forEach((step, index) => {
    const number = parentNumber ? `${parentNumber}.${index + 1}` : (normalizeStepId(step.id) || String(index + 1))
    const content = getDiscussionStepBody(step)
    if (content) result.push({ number, content })
    if (Array.isArray(step.subSteps) && step.subSteps.length) {
      result.push(...flattenDiscussionSteps(step.subSteps, number))
    }
  })
  return result
}

function getDiscussionStepBody(step: DiscussionStep): string {
  const description = stripStepPrefix(step.description || '')
  const content = stripStepPrefix(step.content || '')
  const title = String(step.title || '').trim().replace(/[:：]\s*$/, '')
  if (description) return description
  if (content) return title ? `${title}：${content}` : content
  return title
}

function normalizeStepId(id: DiscussionStep['id']): string {
  const value = String(id ?? '').trim().replace(/-/g, '.')
  return /^\d+(?:\.\d+)*$/.test(value) ? value : ''
}

function stripStepPrefix(value: string): string {
  return String(value || '').trim().replace(/^步骤\s*\d+(?:\.\d+)*\s*[:：]\s*/, '')
}

function normalizeStepContentForCompare(value: string): string {
  return stripStepPrefix(value)
    .replace(/\s+/g, '')
    .replace(/[。；;，,]/g, '')
}

async function restoreFeedback(): Promise<void> {
  const pending = pendingStore.restore()
  if (!pending || pending.session_id !== sessionId.value || pending.task_type !== 'DISCUSSION_FEEDBACK') return
  loadingText.value = '正在恢复未完成的反馈…'
  const response = await resumePendingTask<DiscussionFeedbackData>({ isCancelled: () => hidden.value })
  if (response) applyFeedback(response.data)
}

async function initialize(): Promise<void> {
  if (!sessionId.value || initialized.value) return
  loading.value = true
  try {
    await fetchState(true)
    await restoreFeedback()
    initialized.value = true
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) {
      showApiError(error, '研讨会话加载失败')
      setTimeout(() => uni.navigateBack(), 800)
    }
  } finally {
    loading.value = false
    pendingStore.restore()
  }
}

function track(eventType: string, payload: Record<string, string | number | boolean> = {}): void {
  tracker?.record(eventType, payload)
}

function trackInput(): void {
  inputEventCounter += 1
  if (inputEventCounter % 5 === 0) track('input_change', { length: feedback.value.length })
}

async function submitFeedback(): Promise<void> {
  const value = feedback.value.trim()
  if (!value || !session.value || sending.value) return
  hidden.value = false
  sending.value = true
  track('feedback_tap', { current_step: session.value.current_step })
  try {
    const behaviorData = tracker && authStore.user?.id
      ? tracker.export(authStore.user.id, sessionId.value, session.value.current_step, value.length)
      : undefined
    const response = await discussionApi.feedback({
      session_id: sessionId.value,
      feedback: value,
      behavior_data: behaviorData
    }, { isCancelled: () => hidden.value })
    feedback.value = ''
    applyFeedback(response.data)
    tracker = session.value?.personalized_mode ? new MiniBehaviorTracker() : null
    // feedback 响应是本次提交的最新快照；不要在这里用 getState 的旧快照覆盖它。
    const nextUpdatePayload = updatePayload.value
    if (nextUpdatePayload) await discussionApi.updateState(nextUpdatePayload).catch(() => undefined)
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) showApiError(error, '反馈提交失败')
  } finally {
    sending.value = false
    pendingStore.restore()
  }
}

function formatTime(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function backToQuestion(): void {
  uni.switchTab({ url: '/pages/question/index' })
}

onLoad((query?: AnyObject) => {
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
  track('page_show')
})

onHide(() => {
  hidden.value = true
  track('page_hide')
})
</script>

<style scoped lang="scss">
.discussion-page { min-height: 100vh; min-height: 100dvh; max-width: $content-max; padding: 24rpx 0 152rpx; margin: 0 auto; box-sizing: border-box; background: $page-bg; }
.question-card { padding: 28rpx; margin: 0 28rpx 22rpx; border-left: 5rpx solid $primary; background: linear-gradient(180deg, $surface 0%, #f6faf8 100%); box-shadow: $shadow-soft; }
.card-label { display: block; margin-bottom: 16rpx; color: $primary; font-size: 22rpx; font-weight: 600; letter-spacing: 2rpx; }
.question-plain { display: block; color: $text-primary; white-space: pre-wrap; word-break: break-word; line-height: 1.72; }
.image-strip { width: 100%; margin-top: 20rpx; white-space: nowrap; }
.image-strip image { width: 240rpx; height: 180rpx; margin-right: 16rpx; border-radius: 8rpx; background: $surface-muted; }
.knowledge-card { padding: 0; margin: 0 28rpx 24rpx; overflow: hidden; box-shadow: $shadow-soft; }
.knowledge-card-header { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; padding: 24rpx 26rpx; }
.knowledge-card-header:active { background: $surface-muted; }
.knowledge-title-wrap { display: flex; min-width: 0; flex-direction: column; gap: 5rpx; }
.knowledge-title { color: $text-primary; font-size: 29rpx; font-weight: 700; }
.knowledge-count { color: $text-muted; font-size: 21rpx; }
.knowledge-toggle { flex: none; width: 18rpx; height: 18rpx; border-right: 3rpx solid $primary; border-bottom: 3rpx solid $primary; transform: rotate(45deg); transition: transform $motion-fast ease; }
.knowledge-toggle.expanded { transform: rotate(225deg); }
.knowledge-content { padding: 0 26rpx 24rpx; border-top: 1rpx solid $border; }
.knowledge-row { display: flex; gap: 16rpx; padding: 22rpx 0; border-bottom: 1rpx solid $border; }
.knowledge-row:last-child { padding-bottom: 0; border-bottom: 0; }
.knowledge-index { flex: none; width: 42rpx; height: 42rpx; display: flex; align-items: center; justify-content: center; border-radius: 8rpx; background: $primary-light; color: $primary; font-size: 22rpx; font-weight: 700; }
.knowledge-copy { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 8rpx; }
.knowledge-name { color: $primary-dark; font-weight: 700; line-height: 1.45; }
.knowledge-copy :deep(.rich-shell) { color: $text-secondary; font-size: 22rpx; line-height: 1.55; }
.tree-panel { margin: 20rpx 28rpx 24rpx; padding: 18rpx; box-shadow: none; }
.tree-title { display: flex; justify-content: space-between; color: $text-primary; font-weight: 600; }
.tree-title:active { opacity: .68; }
.tree-title text:last-child { color: $primary; font-size: 23rpx; }
.tree-content { max-height: 440rpx; margin-top: 16rpx; overflow-y: auto; }
.tree-debug { margin-bottom: 10rpx; color: $text-muted; font-size: 20rpx; }
.tree-empty { padding: 20rpx; color: $text-muted; font-size: 24rpx; text-align: center; }
.message-scroll { height: calc(100vh - 480rpx); height: calc(100dvh - 480rpx); min-height: 460rpx; }
.messages { display: flex; flex-direction: column; gap: 20rpx; padding: 10rpx 28rpx 38rpx; }
.message-row { display: flex; }
.message-row.user { justify-content: flex-end; }
.message-bubble { max-width: 84%; padding: 20rpx 22rpx; border: 1rpx solid $border; border-radius: 5rpx 14rpx 14rpx 14rpx; background: $surface; box-shadow: $shadow-soft; line-height: 1.64; }
.message-row.user .message-bubble { border-color: $primary; border-radius: 14rpx 5rpx 14rpx 14rpx; background: $primary; color: $surface; }
.message-time { display: block; margin-top: 9rpx; color: $text-muted; font-size: 19rpx; text-align: right; font-variant-numeric: tabular-nums; }
.message-row.user .message-time { color: #cfe1dc; }
.typing { color: $text-secondary; animation: breathe 1.35s ease-in-out infinite; }
.complete-card { padding: 28rpx; border-left: 5rpx solid $success; background: $success-light; }
.complete-title { display: block; margin-bottom: 18rpx; color: $success; font-size: 32rpx; font-weight: 700; }
.complete-card .primary-button { margin-top: 24rpx; }
.input-bar { position: fixed; z-index: 20; right: 0; bottom: 0; left: 0; width: 100%; max-width: $content-max; margin: 0 auto; display: flex; align-items: flex-end; gap: 16rpx; box-sizing: border-box; padding: 18rpx 24rpx calc(18rpx + env(safe-area-inset-bottom)); border-top: 1rpx solid $border; background: $surface; box-shadow: 0 -10rpx 30rpx rgba(36, 59, 55, .07); }
.feedback-input { flex: 1; min-height: 72rpx; max-height: 220rpx; box-sizing: border-box; padding: 17rpx 20rpx; border: 1rpx solid $border; border-radius: 10rpx; background: $surface-raised; line-height: 1.48; transition: border-color $motion-fast ease, background-color $motion-fast ease; }
.feedback-input:focus { border-color: $primary; background: $surface; }
.send-button { flex: none; width: 118rpx; height: 72rpx; margin: 0; padding: 0; border-radius: 9rpx; background: $primary; color: $surface; font-size: 26rpx; font-weight: 600; line-height: 72rpx; transition: background-color $motion-fast ease, transform $motion-fast ease, opacity $motion-fast ease; }
.send-button:not([disabled]):active { background: $primary-dark; transform: translateY(1rpx); }
.send-button[disabled] { background: $border-strong; opacity: .76; }

@keyframes breathe {
  0%, 100% { opacity: .45; }
  50% { opacity: 1; }
}
</style>
