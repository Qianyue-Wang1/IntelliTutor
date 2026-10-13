<template>
  <view class="page history-page">
    <view class="header-copy">
      <text class="title">学习记录</text>
      <text class="subtitle">回顾讲解与研讨过程</text>
    </view>

    <LoadingState v-if="loading && !sessions.length" text="正在加载历史…" />
    <view v-else-if="!sessions.length" class="card empty">
      <view class="empty-icon" aria-hidden="true">
        <view class="empty-line long"></view>
        <view class="empty-line"></view>
        <view class="empty-line short"></view>
      </view>
      <text class="empty-title">还没有学习记录</text>
      <text class="muted">完成一次提问后，会话会显示在这里</text>
      <button class="start-button" @tap="goQuestion">去提问</button>
    </view>
    <view v-else class="session-list">
      <view v-for="session in sessions" :key="session.session_id" class="card session-card" @tap="openSession(session)">
        <view :class="['type-badge', session.type]">{{ session.type === 'explanation' ? '讲解' : '研讨' }}</view>
        <view class="session-copy">
          <text class="question">{{ session.question || '未命名问题' }}</text>
          <text class="date">{{ formatTime(session.last_accessed || session.created_at) }}</text>
        </view>
        <button class="delete" @tap.stop="confirmDelete(session)">删除</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { sessionApi } from '@/api'
import { showApiError } from '@/api/request'
import type { SessionListItem } from '@/api/types'
import LoadingState from '@/components/LoadingState.vue'
import { requireAuthentication } from '@/composables/useAuthGuard'

const sessions = ref<SessionListItem[]>([])
const loading = ref(false)

async function loadSessions(showError = true): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    const response = await sessionApi.list()
    sessions.value = response.data || []
  } catch (error) {
    if (showError) showApiError(error, '历史记录加载失败')
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

function formatTime(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function openSession(session: SessionListItem): void {
  uni.navigateTo({
    url: `/pages-session/${session.type}/index?sessionId=${encodeURIComponent(session.session_id)}`
  })
}

function confirmDelete(session: SessionListItem): void {
  uni.showModal({
    title: '删除学习记录',
    content: '删除后无法恢复，确定继续吗？',
    confirmColor: '#a94f49',
    success: result => {
      if (!result.confirm) return
      void deleteSession(session.session_id)
    }
  })
}

async function deleteSession(sessionId: string): Promise<void> {
  try {
    await sessionApi.remove(sessionId)
    sessions.value = sessions.value.filter(item => item.session_id !== sessionId)
    uni.showToast({ title: '已删除', icon: 'success' })
  } catch (error) {
    showApiError(error, '删除失败')
  }
}

function goQuestion(): void {
  uni.switchTab({ url: '/pages/question/index' })
}

onShow(() => {
  if (requireAuthentication()) void loadSessions()
})

onPullDownRefresh(() => {
  if (!requireAuthentication()) return
  void loadSessions()
})
</script>

<style scoped lang="scss">
.history-page { max-width: $content-max; margin: 0 auto; }
.header-copy { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 2rpx 34rpx; }
.title { color: $text-primary; font-size: 42rpx; font-weight: 700; letter-spacing: -1rpx; line-height: 1.25; }
.subtitle { color: $text-secondary; font-size: 25rpx; }
.session-list { display: flex; flex-direction: column; overflow: hidden; border: 1rpx solid $border; border-radius: $radius-card; background: $surface; box-shadow: $shadow-soft; }
.session-card { display: flex; align-items: center; gap: 18rpx; padding: 26rpx 20rpx 26rpx 24rpx; border: 0; border-bottom: 1rpx solid $border; border-radius: 0; box-shadow: none; transition: background-color $motion-fast ease, transform $motion-fast ease; }
.session-card:last-child { border-bottom: 0; }
.session-card:active { background: $surface-raised; transform: translateX(2rpx); }
.type-badge { flex: none; min-width: 58rpx; padding: 7rpx 10rpx; border-radius: 5rpx; font-size: 20rpx; font-weight: 600; text-align: center; }
.type-badge.explanation { background: $primary-light; color: $primary; }
.type-badge.discussion { background: $accent-light; color: $accent; }
.session-copy { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 12rpx; }
.question { overflow: hidden; color: $text-primary; font-weight: 600; white-space: nowrap; text-overflow: ellipsis; }
.date { color: $text-muted; font-size: 21rpx; font-variant-numeric: tabular-nums; }
.delete { flex: none; width: auto; height: 64rpx; margin: 0; padding: 0 12rpx; border-radius: 6rpx; background: transparent; color: $danger; font-size: 22rpx; line-height: 64rpx; transition: background-color $motion-fast ease, transform $motion-fast ease; }
.delete:active { background: $danger-light; transform: translateY(1rpx); }
.empty { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 78rpx 30rpx 84rpx; border-style: dashed; text-align: center; box-shadow: none; }
.empty-icon { width: 96rpx; height: 82rpx; box-sizing: border-box; display: flex; flex-direction: column; gap: 10rpx; padding: 20rpx 18rpx; border: 3rpx solid $border-strong; border-radius: 9rpx; background: $surface-raised; }
.empty-line { width: 50rpx; height: 5rpx; border-radius: 2rpx; background: $border-strong; }
.empty-line.long { width: 58rpx; }
.empty-line.short { width: 34rpx; }
.empty-title { color: $text-primary; font-size: 32rpx; font-weight: 700; }
.start-button { height: 76rpx; margin-top: 14rpx; padding: 0 40rpx; border: 1rpx solid $border-strong; border-radius: 8rpx; background: $primary-light; color: $primary; font-size: 25rpx; line-height: 74rpx; transition: transform $motion-fast ease, background-color $motion-fast ease; }
.start-button:active { background: #d8e8e3; transform: translateY(1rpx); }
</style>
