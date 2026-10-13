<template>
  <view class="page profile-page">
    <view class="profile-heading">
      <text class="heading-title">学习概览</text>
      <text class="heading-subtitle">查看你的学习积累与近期进度</text>
    </view>

    <view class="card profile-card">
      <view class="avatar-wrap" @tap="changeAvatar">
        <image v-if="displayAvatar" :src="displayAvatar" class="avatar-image" mode="aspectFill" />
        <view v-else class="avatar-fallback">{{ avatarText }}</view>
        <view class="camera">
          <text v-if="uploadingAvatar" class="uploading">···</text>
          <view v-else class="camera-shape"><view class="camera-dot"></view></view>
        </view>
      </view>
      <view class="identity">
        <view class="name-row">
          <text class="name">{{ displayName }}</text>
          <text class="edit-link" @tap="openNicknameEditor">编辑</text>
        </view>
        <text class="account">{{ accountText }}</text>
      </view>
    </view>

    <LoadingState v-if="loading && !info" text="正在读取个人信息…" />
    <view v-else class="card stats-grid">
      <view class="stat"><text class="stat-value">{{ info?.solved_problems || 0 }}</text><text>已解题目</text></view>
      <view class="stat"><text class="stat-value">{{ info?.study_days || 0 }}</text><text>学习天数</text></view>
      <view class="stat"><text class="stat-value">{{ info?.mastered_knowledge_points || 0 }}</text><text>掌握知识点</text></view>
      <view class="stat"><text class="stat-value">{{ accuracyText }}</text><text>正确率</text></view>
    </view>

    <text class="section-label">本周与账户</text>
    <view class="card detail-card">
      <view class="detail-row"><text>本周学习题目</text><text>{{ info?.weekly_study_questions || 0 }}</text></view>
      <view class="detail-row"><text>本周新增知识点</text><text>{{ info?.weekly_mastered_knowledge_points || 0 }}</text></view>
      <view class="detail-row"><text>上次登录</text><text>{{ formatDate(info?.last_login) }}</text></view>
    </view>

    <button class="logout-button" @tap="confirmLogout">退出登录</button>

    <view v-if="editingNickname" class="modal-mask" @tap="editingNickname = false">
      <view class="nickname-dialog" @tap.stop>
        <text class="dialog-title">修改昵称</text>
        <text class="dialog-desc">使用 2–24 个字符，便于在学习记录中识别。</text>
        <input v-model="nicknameDraft" class="nickname-input" maxlength="24" placeholder="请输入昵称" />
        <view class="dialog-actions">
          <button @tap="editingNickname = false">取消</button>
          <button class="save" :disabled="savingNickname" @tap="saveNickname">保存</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { userApi } from '@/api'
import { showApiError } from '@/api/request'
import type { UserInfo } from '@/api/types'
import LoadingState from '@/components/LoadingState.vue'
import { requireAuthentication } from '@/composables/useAuthGuard'
import { useAuthStore } from '@/stores/auth'
import { chooseAndEncodeImage } from '@/utils/image'

const authStore = useAuthStore()
const info = ref<UserInfo | null>(null)
const loading = ref(false)
const uploadingAvatar = ref(false)
const editingNickname = ref(false)
const nicknameDraft = ref('')
const savingNickname = ref(false)

const displayName = computed(() => info.value?.nickname || authStore.user?.nickname || 'IntelliTutor 用户')
const displayAvatar = computed(() => info.value?.avatar || authStore.user?.avatar || '')
const accountText = computed(() => info.value?.email || info.value?.phone_number || authStore.user?.email || authStore.user?.phone_number || '')
const avatarText = computed(() => displayName.value.trim().slice(0, 1).toUpperCase())
const accuracyText = computed(() => {
  const accuracy = info.value?.accuracy || 0
  return accuracy > 1 ? `${Math.round(accuracy)}%` : `${Math.round(accuracy * 100)}%`
})

async function loadProfile(): Promise<void> {
  const userId = authStore.user?.id
  if (!userId || loading.value) return
  loading.value = true
  try {
    const response = await userApi.getInfo(userId)
    info.value = response.data
    authStore.updateUser(response.data)
  } catch (error) {
    showApiError(error, '个人信息加载失败')
  } finally {
    loading.value = false
  }
}

async function changeAvatar(): Promise<void> {
  const userId = authStore.user?.id
  if (!userId || uploadingAvatar.value) return
  uploadingAvatar.value = true
  try {
    const selected = await chooseAndEncodeImage({ maxEncodedBytes: 512 * 1024 })
    const response = await userApi.updateAvatar(userId, selected.dataUrl)
    const avatar = response.data.avatar || selected.dataUrl
    if (info.value) info.value.avatar = avatar
    authStore.updateUser({ avatar })
    uni.showToast({ title: '头像已更新', icon: 'success' })
  } catch (error) {
    showApiError(error, '头像更新失败')
  } finally {
    uploadingAvatar.value = false
  }
}

function openNicknameEditor(): void {
  nicknameDraft.value = displayName.value
  editingNickname.value = true
}

async function saveNickname(): Promise<void> {
  const userId = authStore.user?.id
  const nickname = nicknameDraft.value.trim()
  if (!userId || savingNickname.value) return
  if (nickname.length < 2) {
    uni.showToast({ title: '昵称至少需要 2 个字符', icon: 'none' })
    return
  }
  savingNickname.value = true
  try {
    await userApi.updateNickname(userId, nickname)
    if (info.value) info.value.nickname = nickname
    authStore.updateUser({ nickname })
    editingNickname.value = false
    uni.showToast({ title: '昵称已更新', icon: 'success' })
  } catch (error) {
    showApiError(error, '昵称更新失败')
  } finally {
    savingNickname.value = false
  }
}

function formatDate(value?: string): string {
  if (!value) return '暂无'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function confirmLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账号吗？',
    success: result => {
      if (result.confirm) authStore.logout()
    }
  })
}

onShow(() => {
  if (requireAuthentication()) void loadProfile()
})
</script>

<style scoped lang="scss">
.profile-page { max-width: $content-max; padding-bottom: 74rpx; margin: 0 auto; }
.profile-heading { display: flex; flex-direction: column; gap: 8rpx; padding: 12rpx 2rpx 32rpx; }
.heading-title { color: $text-primary; font-size: 42rpx; font-weight: 700; letter-spacing: -1rpx; line-height: 1.25; }
.heading-subtitle { color: $text-secondary; font-size: 25rpx; }
.profile-card { display: flex; align-items: center; gap: 26rpx; padding: 30rpx; border-left: 5rpx solid $primary; box-shadow: $shadow-raised; }
.avatar-wrap { position: relative; flex: none; }
.avatar-image, .avatar-fallback { width: 116rpx; height: 116rpx; border-radius: 18rpx; }
.avatar-image { background: $surface-muted; }
.avatar-fallback { display: flex; align-items: center; justify-content: center; background: $primary; color: $surface; font-size: 42rpx; font-weight: 700; }
.camera { position: absolute; right: -5rpx; bottom: -5rpx; width: 44rpx; height: 44rpx; display: flex; align-items: center; justify-content: center; border: 4rpx solid $surface; border-radius: 8rpx; background: $primary-light; color: $primary; }
.uploading { font-size: 18rpx; font-weight: 700; letter-spacing: -2rpx; }
.camera-shape { position: relative; width: 23rpx; height: 18rpx; box-sizing: border-box; border: 3rpx solid currentColor; border-radius: 3rpx; }
.camera-shape::before { position: absolute; top: -6rpx; left: 4rpx; width: 9rpx; height: 4rpx; border: 3rpx solid currentColor; border-bottom: 0; content: ''; }
.camera-dot { position: absolute; top: 50%; left: 50%; width: 5rpx; height: 5rpx; border: 2rpx solid currentColor; border-radius: 50%; transform: translate(-50%, -50%); }
.identity { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 12rpx; }
.name-row { display: flex; align-items: center; gap: 18rpx; }
.name { overflow: hidden; color: $text-primary; font-size: 33rpx; font-weight: 700; white-space: nowrap; text-overflow: ellipsis; }
.edit-link { padding: 8rpx 0; color: $primary; font-size: 23rpx; font-weight: 600; }
.edit-link:active { opacity: .62; }
.account { overflow: hidden; color: $text-secondary; font-size: 24rpx; white-space: nowrap; text-overflow: ellipsis; }
.stats-grid { display: grid; grid-template-columns: repeat(2, 1fr); margin-top: 20rpx; overflow: hidden; border: 0; background: $primary-light; box-shadow: none; }
.stat { position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 6rpx; padding: 28rpx; color: $text-secondary; font-size: 22rpx; }
.stat:nth-child(odd) { border-right: 1rpx solid rgba(36, 95, 89, .14); }
.stat:nth-child(-n+2) { border-bottom: 1rpx solid rgba(36, 95, 89, .14); }
.stat-value { color: $primary-dark; font-size: 38rpx; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -1rpx; }
.section-label { display: block; margin: 34rpx 2rpx 14rpx; color: $text-secondary; font-size: 23rpx; font-weight: 600; }
.detail-card { padding: 4rpx 26rpx; box-shadow: none; }
.detail-row { display: flex; justify-content: space-between; gap: 30rpx; padding: 25rpx 0; border-bottom: 1rpx solid $border; }
.detail-row:last-child { border-bottom: none; }
.detail-row text:first-child { color: $text-primary; }
.detail-row text:last-child { color: $text-secondary; text-align: right; font-variant-numeric: tabular-nums; }
.logout-button { height: 84rpx; margin-top: 32rpx; border: 1rpx solid #ddbbb7; border-radius: 8rpx; background: transparent; color: $danger; font-size: 27rpx; line-height: 82rpx; transition: transform $motion-fast ease, background-color $motion-fast ease; }
.logout-button:active { background: $danger-light; transform: translateY(1rpx); }
.modal-mask { position: fixed; z-index: 50; inset: 0; display: flex; align-items: center; justify-content: center; padding: 36rpx; background: rgba(23,33,38,.5); }
.nickname-dialog { width: 100%; max-width: 640rpx; padding: 32rpx; box-sizing: border-box; border-radius: $radius-card; background: $surface; box-shadow: $shadow-raised; }
.dialog-title { display: block; color: $text-primary; font-size: 31rpx; font-weight: 700; }
.dialog-desc { display: block; margin: 8rpx 0 24rpx; color: $text-secondary; font-size: 23rpx; }
.nickname-input { height: 88rpx; box-sizing: border-box; padding: 0 22rpx; border: 1rpx solid $border; border-radius: $radius-control; background: $surface-raised; }
.nickname-input:focus { border-color: $primary; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 18rpx; margin-top: 30rpx; }
.dialog-actions button { width: 150rpx; height: 76rpx; margin: 0; border: 1rpx solid $border; border-radius: 8rpx; background: $surface; color: $text-secondary; font-size: 25rpx; line-height: 74rpx; }
.dialog-actions button:active { transform: translateY(1rpx); }
.dialog-actions .save { border-color: $primary; background: $primary; color: $surface; }
.dialog-actions .save[disabled] { border-color: $border-strong; background: $border-strong; }
</style>
