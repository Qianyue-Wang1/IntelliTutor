const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PHONE_PATTERN = /^1\d{10}$/

export type AccountType = 'email' | 'phone'

export function validateAccount(type: AccountType, value: string): string | null {
  const normalized = value.trim()
  if (!normalized) return type === 'email' ? '请输入邮箱' : '请输入手机号'
  if (type === 'email' && !EMAIL_PATTERN.test(normalized)) return '邮箱格式不正确'
  if (type === 'phone' && !PHONE_PATTERN.test(normalized)) return '手机号格式不正确'
  return null
}

export function validatePassword(password: string): string | null {
  if (!password) return '请输入密码'
  if (password.length < 6 || password.length > 64) return '密码长度应为 6～64 位'
  return null
}

export function validateVerifyCode(code: string): string | null {
  if (!/^\d{4,8}$/.test(code.trim())) return '请输入正确的验证码'
  return null
}

