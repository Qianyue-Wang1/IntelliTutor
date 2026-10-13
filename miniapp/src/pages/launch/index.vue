<template>
  <view class="launch-page">
    <view class="brand-mark">
      <view class="brand-rule"></view>
      <text>IT</text>
    </view>
    <view class="brand-copy">
      <text class="title">IntelliTutor</text>
      <text class="tagline">让每一步思考都有迹可循</text>
    </view>
    <view class="loading-row">
      <view class="loading-dot"></view>
      <text class="subtitle">正在恢复学习状态</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { useAuthStore } from '@/stores/auth'
import { readPendingTask } from '@/utils/storage'

onLoad(() => {
  const authStore = useAuthStore()
  const authenticated = authStore.restore()
  if (!authenticated) {
    uni.reLaunch({ url: '/pages/auth/login' })
    return
  }

  const pending = readPendingTask()
  if (pending?.task_type.startsWith('EXPLANATION_') && pending.task_type !== 'EXPLANATION_INIT') {
    uni.redirectTo({ url: `/pages-session/explanation/index?sessionId=${encodeURIComponent(pending.session_id)}` })
    return
  }
  if (pending?.task_type === 'DISCUSSION_FEEDBACK') {
    uni.redirectTo({ url: `/pages-session/discussion/index?sessionId=${encodeURIComponent(pending.session_id)}` })
    return
  }
  uni.switchTab({ url: '/pages/question/index' })
})
</script>

<style scoped lang="scss">
.launch-page {
  min-height: 100vh;
  min-height: 100dvh;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48rpx 48rpx calc(48rpx + env(safe-area-inset-bottom));
  background: $page-bg;
}

.brand-mark {
  position: relative;
  width: 116rpx;
  height: 116rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 14rpx;
  background: $primary;
  color: $surface;
  font-size: 46rpx;
  font-weight: 700;
  letter-spacing: -2rpx;
  box-shadow: $shadow-raised;
}

.brand-rule {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 8rpx;
  background: $accent;
}

.brand-copy { display: flex; flex-direction: column; align-items: center; gap: 8rpx; margin-top: 30rpx; }
.title { color: $text-primary; font-size: 44rpx; font-weight: 700; letter-spacing: -.5rpx; }
.tagline { color: $text-secondary; font-size: 25rpx; letter-spacing: .5rpx; }
.loading-row { display: flex; align-items: center; gap: 14rpx; margin-top: 64rpx; }
.loading-dot { width: 16rpx; height: 5rpx; background: $accent; animation: pulse 1.2s ease-in-out infinite; }
.subtitle { color: $text-muted; font-size: 23rpx; }

@keyframes pulse {
  0%, 100% { opacity: .35; transform: scale(.85); }
  50% { opacity: 1; transform: scale(1); }
}
</style>
