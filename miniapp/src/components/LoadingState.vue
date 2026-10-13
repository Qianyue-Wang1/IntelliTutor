<template>
  <view class="loading-state">
    <view class="thinking-mark" aria-hidden="true">
      <view class="thinking-line line-long" />
      <view class="thinking-line line-medium" />
      <view class="thinking-line line-short" />
    </view>
    <text class="loading-text">{{ text }}</text>
    <text v-if="hint" class="hint">{{ hint }}</text>
  </view>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ text?: string; hint?: string }>(), {
  text: '加载中…',
  hint: ''
})
</script>

<style scoped lang="scss">
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18rpx;
  min-height: 320rpx;
  padding: 72rpx 28rpx;
  color: $primary;
}

.thinking-mark {
  width: 72rpx;
  height: 62rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 9rpx;
  padding: 0 12rpx;
  box-sizing: border-box;
  border-left: 5rpx solid $accent;
  background: $primary-light;
}

.thinking-line {
  height: 5rpx;
  background: $primary;
  transform-origin: left center;
  animation: think 1.25s ease-in-out infinite;
}

.line-long { width: 44rpx; }
.line-medium { width: 34rpx; animation-delay: .14s; }
.line-short { width: 24rpx; animation-delay: .28s; }

.loading-text { font-size: 27rpx; font-weight: 600; letter-spacing: .5rpx; }

.hint {
  max-width: 520rpx;
  color: $text-muted;
  font-size: 23rpx;
  line-height: 1.55;
  text-align: center;
}

@keyframes think {
  0%, 100% { opacity: .32; transform: scaleX(.62); }
  50% { opacity: 1; transform: scaleX(1); }
}
</style>
