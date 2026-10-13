<template>
  <view class="auth-page">
    <view class="hero">
      <text class="hero-title">注册账号</text>
      <text class="hero-desc">创建账户，保存你的每一次思考与进步</text>
    </view>

    <view class="auth-form">
      <view class="account-switch" aria-label="注册方式">
        <text :class="['switch-option', { active: accountType === 'email' }]" @tap="accountType = 'email'">
          邮箱注册
        </text>
        <text :class="['switch-option', { active: accountType === 'phone' }]" @tap="accountType = 'phone'">
          手机号注册
        </text>
      </view>

      <view class="field">
        <text class="label">{{ accountType === 'email' ? '邮箱' : '手机号' }}</text>
        <input v-model="account" class="input" :type="accountType === 'email' ? 'text' : 'number'"
          :maxlength="accountType === 'email' ? 80 : 11" placeholder="请输入账号" />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <view class="password-field">
          <input
            v-model="password"
            class="input password-input"
            :password="!showPassword"
            maxlength="64"
            placeholder="至少 6 位"
          />
          <button
            class="password-toggle"
            :class="{ visible: showPassword }"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            :aria-checked="showPassword"
            role="switch"
            @tap="showPassword = !showPassword"
          >
            <view class="eye-icon"><view class="eye-pupil"></view></view>
            <view v-if="!showPassword" class="eye-slash"></view>
          </button>
        </view>
      </view>
      <view class="field">
        <text class="label">确认密码</text>
        <view class="password-field">
          <input
            v-model="confirmPassword"
            class="input password-input"
            :password="!showConfirmPassword"
            maxlength="64"
            placeholder="再次输入密码"
          />
          <button
            class="password-toggle"
            :class="{ visible: showConfirmPassword }"
            :aria-label="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
            :aria-checked="showConfirmPassword"
            role="switch"
            @tap="showConfirmPassword = !showConfirmPassword"
          >
            <view class="eye-icon"><view class="eye-pupil"></view></view>
            <view v-if="!showConfirmPassword" class="eye-slash"></view>
          </button>
        </view>
      </view>
      <view class="field">
        <text class="label">验证码</text>
        <view class="code-row">
          <input v-model="verifyCode" class="input code-input" type="number" maxlength="8" placeholder="验证码" />
          <button class="code-button" :disabled="remaining > 0 || sendingCode" @tap="sendCode">
            {{ remaining > 0 ? `${remaining}s` : sendingCode ? '发送中' : '获取验证码' }}
          </button>
        </view>
      </view>

      <button class="primary-button submit" :disabled="submitting" @tap="submit">
        {{ submitting ? '注册中…' : '注册并登录' }}
      </button>
      <view class="switch-link" @tap="goBack">已有账号？<text>返回登录</text></view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { authApi } from '@/api'
import { showApiError } from '@/api/request'
import { useCountdown } from '@/composables/useCountdown'
import { useAuthStore } from '@/stores/auth'
import { validateAccount, validatePassword, validateVerifyCode, type AccountType } from '@/utils/validation'

const authStore = useAuthStore()
const accountType = ref<AccountType>('email')
const account = ref('')
const password = ref('')
const confirmPassword = ref('')
const showPassword = ref(false)
const showConfirmPassword = ref(false)
const verifyCode = ref('')
const sendingCode = ref(false)
const submitting = ref(false)
const { remaining, start } = useCountdown()

function validationError(includeCode = false): string | null {
  return validateAccount(accountType.value, account.value)
    || validatePassword(password.value)
    || (password.value !== confirmPassword.value ? '两次输入的密码不一致' : null)
    || (includeCode ? validateVerifyCode(verifyCode.value) : null)
}

async function sendCode(): Promise<void> {
  const error = validationError(false)
  if (error) {
    uni.showToast({ title: error, icon: 'none' })
    return
  }
  sendingCode.value = true
  try {
    if (accountType.value === 'email') {
      await authApi.sendRegisterEmailCode({ email: account.value.trim(), password: password.value })
    } else {
      await authApi.sendRegisterPhoneCode({ phone_number: account.value.trim(), password: password.value })
    }
    start(60)
    uni.showToast({ title: '验证码已发送', icon: 'success' })
  } catch (error) {
    showApiError(error, '验证码发送失败')
  } finally {
    sendingCode.value = false
  }
}

async function submit(): Promise<void> {
  const error = validationError(true)
  if (error) {
    uni.showToast({ title: error, icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const response = await authApi.register({
      ...(accountType.value === 'email'
        ? { email: account.value.trim() }
        : { phone_number: account.value.trim() }),
      password: password.value,
      verify_code: verifyCode.value.trim()
    })
    authStore.applyAuth(response.data)
    uni.switchTab({ url: '/pages/question/index' })
  } catch (error) {
    showApiError(error, '注册失败')
  } finally {
    submitting.value = false
  }
}

function goBack(): void {
  uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/auth/login' }) })
}
</script>

<style scoped lang="scss">
.auth-page {
  min-height: 100vh;
  min-height: 100dvh;
  box-sizing: border-box;
  padding: 50rpx 48rpx calc(52rpx + env(safe-area-inset-bottom));
  background: $page-bg;
}

.hero {
  width: 100%;
  max-width: 660rpx;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  margin: 0 auto 38rpx;
}

.hero-title {
  display: block;
  color: $text-primary;
  font-size: 46rpx;
  font-weight: 700;
  letter-spacing: -1rpx;
  line-height: 1.18;
}

.hero-desc { color: $text-secondary; font-size: 25rpx; }

.auth-form {
  width: 100%;
  max-width: 660rpx;
  margin: 0 auto;
}

.account-switch {
  display: flex;
  align-items: center;
  margin-bottom: 34rpx;
  border-bottom: 1rpx solid $border;
  font-size: 29rpx;
}

.switch-option {
  position: relative;
  flex: 1;
  padding: 16rpx 8rpx 18rpx;
  color: $text-muted;
  font-weight: 400;
  text-align: center;
  transition: color $motion-fast ease, transform $motion-fast ease;
}

.switch-option:active { transform: translateY(1rpx); }

.switch-option.active {
  color: $primary;
  font-weight: 600;
}

.switch-option.active::after { position: absolute; right: 28rpx; bottom: -2rpx; left: 28rpx; height: 4rpx; background: $primary; content: ''; }

.field { margin-bottom: 24rpx; }
.label { display: block; margin-bottom: 10rpx; color: $text-primary; font-size: 24rpx; font-weight: 600; }
.input { width: 100%; height: 84rpx; box-sizing: border-box; padding: 0 24rpx; border: 1rpx solid $border; border-radius: $radius-control; background: $surface; color: $text-primary; transition: border-color $motion-fast ease, background-color $motion-fast ease; }
.input:focus { border-color: $primary; background: $surface-raised; }
.password-field { position: relative; }
.password-input { width: 100%; padding-right: 112rpx; }
.password-toggle {
  position: absolute;
  z-index: 1;
  top: 8rpx;
  right: 8rpx;
  width: 68rpx;
  height: 68rpx;
  margin: 0;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8rpx;
  background: transparent;
  line-height: 1;
}

.password-toggle::after { border: 0; }
.password-toggle:active { background: $primary-light; }

.eye-icon {
  position: relative;
  width: 34rpx;
  height: 22rpx;
  box-sizing: border-box;
  border: 4rpx solid $text-muted;
  border-radius: 70% 18% 70% 18%;
  transform: rotate(45deg);
}

.eye-pupil {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 10rpx;
  height: 10rpx;
  border-radius: 50%;
  background: $text-muted;
  transform: translate(-50%, -50%);
}

.password-toggle.visible .eye-icon { border-color: $primary; }
.password-toggle.visible .eye-pupil { background: $primary; }

.eye-slash {
  position: absolute;
  width: 42rpx;
  height: 4rpx;
  border-radius: 4rpx;
  background: $text-muted;
  transform: rotate(-45deg);
}

.code-row { display: flex; gap: 16rpx; }
.code-input { flex: 1; }
.code-button { width: 220rpx; height: 84rpx; padding: 0; border: 1rpx solid $border-strong; border-radius: $radius-control; background: $primary-light; color: $primary; font-size: 25rpx; line-height: 82rpx; transition: transform $motion-fast ease, background-color $motion-fast ease; }
.code-button:not([disabled]):active { background: #d8e8e3; transform: translateY(1rpx); }
.code-button[disabled] { border-color: $border; background: $surface-muted; color: $text-muted; }
.submit { margin-top: 10rpx; }
.switch-link { padding-top: 30rpx; text-align: center; color: $text-secondary; }
.switch-link text { color: $primary; font-weight: 600; }
.switch-link:active { opacity: .68; }
</style>
