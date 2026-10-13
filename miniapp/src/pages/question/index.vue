<template>
  <view class="page question-page">
    <view class="prompt-heading">
      <text class="title">今天想解决什么问题？</text>
    </view>

    <view class="composer-area">
      <view class="card composer">
        <view class="composer-head">
          <text class="composer-label">题目内容</text>
          <text class="character-count">{{ questionText.length }} / 4000</text>
        </view>
        <textarea
          v-model="questionText"
          class="question-input"
          :disabled="busy"
          :maxlength="4000"
          auto-height
          placeholder="请输入题目相关内容,或者上传题目相关的图片"
        />

        <view v-if="image" class="image-preview">
          <image :src="image.tempPath" mode="aspectFill" />
          <view class="image-meta">
            <text>图片已添加</text>
            <text>{{ Math.ceil(image.size / 1024) }} KB</text>
          </view>
          <view class="remove-image" @tap="removeImage">移除</view>
        </view>

        <view class="composer-toolbar">
          <button class="media-button" aria-label="上传图片" :disabled="busy" @tap="selectImage">
            <image class="toolbar-icon upload-icon" src="../../assets/camera.png" mode="aspectFit" />
          </button>
          <view class="mode-segment" aria-label="学习模式">
            <button
              :class="['mode-option', { active: mode === 'explanation' }]"
              :disabled="busy"
              @tap="mode = 'explanation'"
            >
              讲解模式
            </button>
            <button
              :class="['mode-option', 'discussion-mode', { active: mode === 'discussion' }]"
              :disabled="busy"
              @tap="mode = 'discussion'"
            >
              研讨模式
            </button>
          </view>
          <button
            :class="['personalized-button', { active: personalized }]"
            :aria-label="personalized ? '关闭个性化模式' : '开启个性化模式'"
            :aria-checked="personalized"
            :disabled="busy"
            role="switch"
            @tap="togglePersonalized"
          >
            <image class="toolbar-icon personalized-icon" src="../../assets/configuration.png" mode="aspectFit" />
            <text class="personalized-label">个性化开启</text>
          </button>
          <button
            class="send-button"
            aria-label="发送题目"
            :disabled="busy || (!questionText.trim() && !image)"
            @tap="submit"
          >
            <view v-if="busy" class="send-spinner"></view>
            <image v-else class="send-asset-icon" src="../../assets/send.png" mode="aspectFit" />
          </button>
        </view>
      </view>

      <text v-if="busy" class="pending-hint">{{ statusText }}，任务切到后台后会保留</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onHide, onShow } from '@dcloudio/uni-app'
import { discussionApi, explanationApi, toolApi } from '@/api'
import { ApiError, showApiError } from '@/api/request'
import { resumePendingTask } from '@/api/task'
import type {
  DiscussionInitData,
  DiscussionSessionData,
  DiscussionUpdateStateInput,
  ExplanationInitData,
  PendingTask
} from '@/api/types'
import { requireAuthentication } from '@/composables/useAuthGuard'
import { usePendingTaskStore } from '@/stores/pending-task'
import { useSessionStore } from '@/stores/session'
import { chooseAndEncodeImage, type EncodedImage } from '@/utils/image'

type StudyMode = 'explanation' | 'discussion'

const pendingStore = usePendingTaskStore()
const sessionStore = useSessionStore()
const questionText = ref('')
const image = ref<EncodedImage | null>(null)
const mode = ref<StudyMode>('explanation')
const personalized = ref(false)
const busy = ref(false)
const statusText = ref('正在准备…')
const hidden = ref(false)

function togglePersonalized(): void {
  if (!busy.value) personalized.value = !personalized.value
}

async function selectImage(): Promise<void> {
  try {
    image.value = await chooseAndEncodeImage()
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '图片选择失败', icon: 'none' })
  }
}

function removeImage(): void {
  if (!busy.value) image.value = null
}

function navigateToSession(type: StudyMode, sessionId: string): void {
  uni.navigateTo({ url: `/pages-session/${type}/index?sessionId=${encodeURIComponent(sessionId)}` })
}

function buildDiscussionSession(question: string, data: DiscussionInitData): DiscussionSessionData {
  return {
    session_id: data.session_id,
    question,
    personalized_mode: personalized.value,
    images: image.value ? [{ name: image.value.name, url: image.value.dataUrl }] : [],
    imageCode: [],
    current_step: data.initial_step,
    discussion_tree: data.discussion_tree,
    messages_history: data.messages_history,
    knowledge_points: data.knowledge_points,
    is_complete: false,
    summary_text: ''
  }
}

function toDiscussionUpdate(data: DiscussionSessionData): DiscussionUpdateStateInput {
  return {
    session_id: data.session_id,
    question: data.question,
    images: data.images || [],
    imageCode: data.imageCode || [],
    current_step: data.current_step,
    discussion_tree: data.discussion_tree,
    messages_history: data.messages_history,
    knowledge_points: data.knowledge_points,
    is_complete: data.is_complete,
    summary_text: data.summary_text || ''
  }
}

async function resumeInitTask(pending: PendingTask): Promise<void> {
  busy.value = true
  statusText.value = '正在恢复…'
  try {
    if (pending.task_type === 'EXPLANATION_INIT') {
      const response = await resumePendingTask<ExplanationInitData>({ isCancelled: () => hidden.value })
      if (!response) return
      sessionStore.setExplanation(response.data)
      navigateToSession('explanation', response.data.session_id)
    } else if (pending.task_type === 'DISCUSSION_INIT') {
      const response = await resumePendingTask<DiscussionInitData>({ isCancelled: () => hidden.value })
      if (!response) return
      let restored: DiscussionSessionData
      try {
        restored = (await discussionApi.getState(response.data.session_id)).data
      } catch {
        const question = questionText.value.trim() || '已恢复的问题'
        restored = buildDiscussionSession(question, response.data)
      }
      sessionStore.setDiscussion(restored)
      navigateToSession('discussion', response.data.session_id)
    }
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) showApiError(error, '恢复任务失败')
  } finally {
    busy.value = false
    pendingStore.restore()
  }
}

async function restorePending(): Promise<void> {
  if (busy.value) return
  const pending = pendingStore.restore()
  if (!pending) return
  if (pending.task_type === 'EXPLANATION_INIT' || pending.task_type === 'DISCUSSION_INIT') {
    await resumeInitTask(pending)
    return
  }
  navigateToSession(pending.task_type.startsWith('EXPLANATION_') ? 'explanation' : 'discussion', pending.session_id)
}

async function resolveQuestion(): Promise<string> {
  const supplement = questionText.value.trim()
  if (!image.value) {
    statusText.value = '正在检查题目…'
    const response = await toolApi.checkInput(supplement)
    return response.data.question || response.data.user_input || supplement
  }

  statusText.value = '正在识别图片…'
  const response = await toolApi.recognizeImage(image.value.dataUrl, supplement)
  const parts = [response.data.extracted_text]
  if (response.data.structured_description) parts.push(`题目图片内容：${response.data.structured_description}`)
  if (supplement) parts.push(`用户补充说明：${supplement}`)
  return parts.filter(Boolean).join('\n\n')
}

async function submit(): Promise<void> {
  if (busy.value || (!questionText.value.trim() && !image.value)) return
  if (!requireAuthentication()) return

  hidden.value = false
  busy.value = true
  try {
    const processedQuestion = await resolveQuestion()
    const images = image.value ? [{ name: image.value.name, url: image.value.dataUrl }] : []
    statusText.value = mode.value === 'explanation' ? '正在生成讲解…' : '正在生成研讨会话…'

    if (mode.value === 'explanation') {
      const response = await explanationApi.init({
        processedQuestion,
        userInput: questionText.value.trim(),
        personalizedMode: personalized.value,
        images
      }, { isCancelled: () => hidden.value })
      sessionStore.setExplanation(response.data)
      navigateToSession('explanation', response.data.session_id)
    } else {
      const response = await discussionApi.init({
        processedQuestion,
        images,
        personalizedMode: personalized.value
      }, { isCancelled: () => hidden.value })
      const discussion = buildDiscussionSession(processedQuestion, response.data)
      sessionStore.setDiscussion(discussion)
      await discussionApi.updateState(toDiscussionUpdate(discussion)).catch(() => undefined)
      navigateToSession('discussion', response.data.session_id)
    }
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 'TASK_CANCELLED')) showApiError(error, '创建会话失败')
  } finally {
    busy.value = false
    pendingStore.restore()
  }
}

onShow(() => {
  hidden.value = false
  if (!requireAuthentication()) return
  void restorePending()
})

onHide(() => {
  hidden.value = true
})
</script>

<style scoped lang="scss">
.question-page {
  display: flex;
  min-height: 100vh;
  min-height: 100dvh;
  max-width: $content-max;
  flex-direction: column;
  padding-top: 30rpx;
  padding-bottom: 68rpx;
  margin: 0 auto;
}

.prompt-heading {
  display: flex;
  width: 100%;
  min-height: 260rpx;
  flex: 1;
  align-items: center;
  justify-content: center;
  padding: 24rpx 4rpx 48rpx;
  text-align: center;
}

.title {
  display: block;
  max-width: 560rpx;
  color: $text-primary;
  font-size: 42rpx;
  font-weight: 700;
  letter-spacing: 0;
  line-height: 1.25;
}

.composer-area {
  width: 100%;
  margin-top: 0;
  padding-top: 0;
}

.composer {
  position: relative;
  overflow: hidden;
  padding: 28rpx;
  border-top: 5rpx solid $primary;
  box-shadow: $shadow-raised;
}

.composer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}

.composer-label {
  color: $text-primary;
  font-size: 24rpx;
  font-weight: 600;
}

.character-count {
  color: $text-muted;
  font-size: 21rpx;
  font-variant-numeric: tabular-nums;
}

.question-input {
  width: 100%;
  min-height: 248rpx;
  box-sizing: border-box;
  padding: 8rpx 0 18rpx;
  color: $text-primary;
  line-height: 1.68;
}

.image-preview {
  position: relative;
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 16rpx;
  border: 1rpx solid $border;
  border-radius: 9rpx;
  background: $surface-raised;
  color: $text-secondary;
  font-size: 23rpx;
}

.image-preview image {
  width: 132rpx;
  height: 104rpx;
  border-radius: 6rpx;
  background: $surface-muted;
}

.image-meta {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 6rpx;
}

.image-meta text:first-child {
  color: $text-primary;
  font-weight: 600;
}

.image-meta text:last-child {
  color: $text-muted;
  font-size: 21rpx;
  font-variant-numeric: tabular-nums;
}

.remove-image {
  padding: 14rpx 6rpx 14rpx 18rpx;
  color: $danger;
  font-size: 23rpx;
}

.remove-image:active {
  opacity: .62;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10rpx;
  padding-top: 20rpx;
  margin-top: 20rpx;
  border-top: 1rpx solid $border;
}

.media-button {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 58rpx;
  height: 56rpx;
  margin: 0;
  padding: 0;
  border: 1rpx solid $border-strong;
  border-radius: 8rpx;
  background: $primary-light;
  color: $primary;
  line-height: 1;
  transition: transform $motion-fast ease, background-color $motion-fast ease;
}

.toolbar-icon {
  display: block;
  width: 32rpx;
  height: 32rpx;
  opacity: .76;
  filter: brightness(0) saturate(100%) invert(31%) sepia(20%) saturate(998%) hue-rotate(125deg) brightness(91%) contrast(89%);
  transition: opacity $motion-fast ease, filter $motion-fast ease;
}

.upload-icon {
  width: 34rpx;
  height: 34rpx;
}

.media-button:not([disabled]):active {
  background: #d8e8e3;
  transform: translateY(1rpx);
}

.media-button[disabled] {
  border-color: $border;
  background: $surface-muted;
  color: $text-muted;
}

.mode-segment {
  display: flex;
  flex: none;
  min-width: 0;
  width: 264rpx;
  padding: 4rpx;
  border: 1rpx solid $border;
  border-radius: 10rpx;
  background: $surface-muted;
}

.mode-option {
  display: flex;
  flex: 1;
  min-width: 0;
  height: 48rpx;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0 8rpx;
  border: 0;
  border-radius: 7rpx;
  background: transparent;
  color: $text-secondary;
  font-size: 21rpx;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  transition: background-color $motion-fast ease, color $motion-fast ease, transform $motion-fast ease;
}

.mode-option::after {
  border: 0;
}

.mode-option:not([disabled]):active {
  transform: translateY(1rpx);
}

.mode-option.active {
  background: $primary;
  color: $surface;
  box-shadow: 0 6rpx 16rpx rgba(36, 95, 89, .16);
}

.mode-option.discussion-mode.active {
  background: $primary;
  box-shadow: 0 6rpx 16rpx rgba(36, 95, 89, .16);
}

.mode-option[disabled] {
  color: $text-muted;
}

.personalized-button {
  display: flex;
  flex: none;
  width: 58rpx;
  height: 56rpx;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0;
  border: 1rpx solid $border-strong;
  border-radius: 8rpx;
  background: $surface-raised;
  color: $text-muted;
  line-height: 1;
  overflow: hidden;
  transition: width $motion-base ease, transform $motion-fast ease, background-color $motion-fast ease, border-color $motion-fast ease, color $motion-fast ease;
}

.personalized-button::after {
  border: 0;
}

.personalized-button:not([disabled]):active {
  transform: translateY(1rpx);
}

.personalized-button.active {
  width: 158rpx;
  border-color: $primary;
  background: $primary-light;
  color: $primary;
  box-shadow: 0 6rpx 16rpx rgba(36, 95, 89, .14);
}

.personalized-button.active .toolbar-icon {
  opacity: 1;
  filter: brightness(0) saturate(100%) invert(31%) sepia(20%) saturate(998%) hue-rotate(125deg) brightness(91%) contrast(89%);
}

.personalized-button[disabled] {
  border-color: $border;
  background: $surface-muted;
  color: $text-muted;
}

.personalized-icon {
  flex: none;
}

.personalized-label {
  display: block;
  max-width: 0;
  margin-left: 0;
  overflow: hidden;
  color: $primary;
  font-size: 20rpx;
  font-weight: 600;
  line-height: 1;
  opacity: 0;
  white-space: nowrap;
  transition: max-width $motion-base ease, margin-left $motion-base ease, opacity $motion-fast ease;
}

.personalized-button.active .personalized-label {
  max-width: 104rpx;
  margin-left: 8rpx;
  opacity: 1;
}

.send-button {
  position: relative;
  display: flex;
  flex: none;
  width: 58rpx;
  height: 56rpx;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 8rpx;
  background: $primary;
  color: $surface;
  line-height: 1;
  transition: transform $motion-fast ease, background-color $motion-fast ease, opacity $motion-fast ease;
}

.send-button::after {
  border: 0;
}

.send-button:not([disabled]):active {
  background: $primary-dark;
  transform: translateY(1rpx);
}

.send-button[disabled] {
  background: $border-strong;
  opacity: .78;
}

.send-asset-icon {
  display: block;
  width: 30rpx;
  height: 30rpx;
}

.send-spinner {
  width: 28rpx;
  height: 28rpx;
  box-sizing: border-box;
  border: 4rpx solid rgba(255, 254, 251, .42);
  border-top-color: $surface;
  border-radius: 50%;
  animation: spin 820ms linear infinite;
}

.pending-hint {
  display: block;
  padding: 18rpx 24rpx 0;
  color: $text-muted;
  font-size: 21rpx;
  line-height: 1.5;
  text-align: center;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
