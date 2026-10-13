<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '@/stores/auth'
import { usePendingTaskStore } from '@/stores/pending-task'

const authStore = useAuthStore()
const pendingTaskStore = usePendingTaskStore()

onLaunch(() => {
  authStore.restore()
  pendingTaskStore.restore()
})

onShow(() => {
  authStore.restore()
  pendingTaskStore.restore()
})
</script>

<style lang="scss">
page {
  min-height: 100%;
  background: $page-bg;
  color: $text-primary;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', sans-serif;
  font-size: 28rpx;
  font-weight: 400;
  line-height: 1.62;
  text-rendering: optimizeLegibility;
}

button::after {
  border: none;
}

button {
  box-sizing: border-box;
  font-family: inherit;
}

.page {
  min-height: 100vh;
  min-height: 100dvh;
  box-sizing: border-box;
  padding: 30rpx 30rpx calc(48rpx + env(safe-area-inset-bottom));
}

.card {
  border: 1rpx solid $border;
  border-radius: $radius-card;
  background: $surface;
  box-shadow: $shadow-soft;
}

.primary-button {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 92rpx;
  border-radius: $radius-control;
  background: $primary;
  color: $surface;
  font-size: 30rpx;
  font-weight: 600;
  letter-spacing: 1rpx;
  line-height: 1;
  transition: background-color $motion-fast ease, transform $motion-fast ease, opacity $motion-fast ease;
}

.primary-button[disabled] {
  background: $border-strong;
  color: $surface-raised;
  opacity: .82;
}

.primary-button:not([disabled]):active,
.primary-button:not([disabled]).button-hover {
  background: $primary-dark;
  transform: translateY(1rpx) scale(.992);
}

.muted {
  color: $text-secondary;
}

.numeric {
  font-variant-numeric: tabular-nums;
}
</style>
