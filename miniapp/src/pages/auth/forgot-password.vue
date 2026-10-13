<template>
  <view class="auth-page">
    <view class="hero">
      <text class="hero-title">找回密码</text>
      <text class="hero-desc">验证账号后设置新的登录密码</text>
    </view>

    <view class="auth-form">
      <view class="account-switch" aria-label="找回方式">
        <text :class="['switch-option', { active: accountType === 'email' }]" @tap="accountType = 'email'">
          邮箱找回
        </text>
        <text :class="['switch-option', { active: accountType === 'phone' }]" @tap="accountType = 'phone'">
          手机号找回
        </text>
      </view>

      <view class="field">
        <text class="label">{{ accountType === 'email' ? '邮箱' : '手机号' }}</text>
        <input
          v-model="account"
          class="input"
          :type="accountType === 'email' ? 'text' : 'number'"
          :maxlength="accountType === 'email' ? 80 : 11"
          :placeholder="accountType === 'email' ? 'name@example.com' : '请输入手机号'"
        />
      </view>

      <view class="field">
        <text class="label">验证码</text>
        <view class="code-row">
          <input v-model="verifyCode" class="input code-input" type="number" maxlength="8" placeholder="验证码" />
          <button class="code-button" :disabled="sendingCode" @tap="sendCode">
            {{ sendingCode ? '发送中' : '获取验证码' }}
          </button>
        </view>
      </view>

      <view class="field">
        <text class="label">新密码</text>
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
            :aria-label="showPassword ? '隐藏新密码' : '显示新密码'"
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
        <text class="label">确认新密码</text>
        <view class="password-field">
          <input
            v-model="confirmPassword"
            class="input password-input"
            :password="!showConfirmPassword"
            maxlength="64"
            placeholder="再次输入新密码"
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

      <button class="primary-button submit" :disabled="submitting" @tap="submit">
        {{ submitting ? '提交中' : '重置密码' }}
      </button>

      <view class="switch-link" @tap="goBack">想起密码了？<text>返回登录</text></view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { validateAccount, validatePassword, validateVerifyCode, type AccountType } from '@/utils/validation'

const accountType = ref<AccountType>('email')
const account = ref('')
const verifyCode = ref('')
const password = ref('')
const confirmPassword = ref('')
const showPassword = ref(false)
const showConfirmPassword = ref(false)
const sendingCode = ref(false)
const submitting = ref(false)

function passwordMatchError(): string | null {
  return password.value !== confirmPassword.value ? '两次输入的密码不一致' : null
}

function resetValidationError(includeCode = false): string | null {
  return validateAccount(accountType.value, account.value)
    || validatePassword(password.value)
    || passwordMatchError()
    || (includeCode ? validateVerifyCode(verifyCode.value) : null)
}

function sendCode(): void {
  const error = validateAccount(accountType.value, account.value)
  if (error) {
    uni.showToast({ title: error, icon: 'none' })
    return
  }
  uni.showToast({ title: '密码找回接口暂未接入', icon: 'none' })
}

function submit(): void {
  const error = resetValidationError(true)
  if (error) {
    uni.showToast({ title: error, icon: 'none' })
    return
  }
  uni.showToast({ title: '密码找回接口暂未接入', icon: 'none' })
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
  display: flex;
  width: 100%;
  max-width: 660rpx;
  flex-direction: column;
  gap: 10rpx;
  margin: 0 auto 38rpx;
}

.hero-title {
  display: block;
  color: $text-primary;
  font-size: 46rpx;
  font-weight: 700;
  line-height: 1.18;
}

.hero-desc {
  color: $text-secondary;
  font-size: 25rpx;
}

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

.switch-option:active {
  transform: translateY(1rpx);
}

.switch-option.active {
  color: $primary;
  font-weight: 600;
}

.switch-option.active::after {
  position: absolute;
  right: 28rpx;
  bottom: -2rpx;
  left: 28rpx;
  height: 4rpx;
  background: $primary;
  content: '';
}

.field {
  margin-bottom: 24rpx;
}

.label {
  display: block;
  margin-bottom: 10rpx;
  color: $text-primary;
  font-size: 24rpx;
  font-weight: 600;
}

.input {
  width: 100%;
  height: 84rpx;
  box-sizing: border-box;
  padding: 0 24rpx;
  border: 1rpx solid $border;
  border-radius: $radius-control;
  background: $surface;
  color: $text-primary;
  transition: border-color $motion-fast ease, background-color $motion-fast ease;
}

.input:focus {
  border-color: $primary;
  background: $surface-raised;
}

.code-row {
  display: flex;
  gap: 16rpx;
}

.code-input {
  flex: 1;
}

.code-button {
  width: 220rpx;
  height: 84rpx;
  padding: 0;
  border: 1rpx solid $border-strong;
  border-radius: $radius-control;
  background: $primary-light;
  color: $primary;
  font-size: 25rpx;
  line-height: 82rpx;
  transition: transform $motion-fast ease, background-color $motion-fast ease;
}

.code-button::after {
  border: 0;
}

.code-button:not([disabled]):active {
  background: #d8e8e3;
  transform: translateY(1rpx);
}

.code-button[disabled] {
  border-color: $border;
  background: $surface-muted;
  color: $text-muted;
}

.password-field {
  position: relative;
}

.password-input {
  width: 100%;
  padding-right: 112rpx;
}

.password-toggle {
  position: absolute;
  z-index: 1;
  top: 8rpx;
  right: 8rpx;
  display: flex;
  width: 68rpx;
  height: 68rpx;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 8rpx;
  background: transparent;
  line-height: 1;
}

.password-toggle::after {
  border: 0;
}

.password-toggle:active {
  background: $primary-light;
}

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

.password-toggle.visible .eye-icon {
  border-color: $primary;
}

.password-toggle.visible .eye-pupil {
  background: $primary;
}

.eye-slash {
  position: absolute;
  width: 42rpx;
  height: 4rpx;
  border-radius: 4rpx;
  background: $text-muted;
  transform: rotate(-45deg);
}

.submit {
  margin-top: 10rpx;
}

.switch-link {
  padding-top: 30rpx;
  color: $text-secondary;
  text-align: center;
}

.switch-link text {
  color: $primary;
  font-weight: 600;
}

.switch-link:active {
  opacity: .68;
}
</style>
