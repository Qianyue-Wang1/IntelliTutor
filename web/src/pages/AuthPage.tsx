import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { services } from '@/api/services'
import { useAuth } from '@/providers/AuthProvider'
import { useNotice } from '@/providers/NoticeProvider'
import { useCapabilities } from '@/hooks/useCapabilities'

const schema = z.object({
  account: z.string().optional(),
  name: z.string().optional(),
  phone: z.string().optional(),
  password: z.string().min(8, '密码至少 8 位').max(64),
  code: z.string().max(32).optional(),
})
type FormData = z.infer<typeof schema>

export default function AuthPage({ kind }: { kind: 'login' | 'register' }) {
  const auth = useAuth()
  const notice = useNotice()
  const navigate = useNavigate()
  const location = useLocation()
  const [sending, setSending] = useState(false)
  const [methods, setMethods] = useState<Array<'SMS_VERIFIED' | 'INVITE_CODE'>>([])
  const [method, setMethod] = useState<'SMS_VERIFIED' | 'INVITE_CODE'>('SMS_VERIFIED')
  const { capabilities } = useCapabilities()
  const experimentMode = capabilities.experiment_mode_enabled
  const { register, handleSubmit, getValues, formState: { errors, isSubmitting } } = useForm<FormData>({ resolver: zodResolver(schema) })
  const experimentRegistration = experimentMode && kind === 'register'
  useEffect(() => {
    if (!experimentRegistration) return
    void services.experimentRegistrationOptions().then(result => {
      setMethods(result.methods)
      if (result.methods[0]) setMethod(result.methods[0])
    }).catch(error => notice(error instanceof Error ? error.message : '无法加载报名方式', 'error'))
  }, [experimentRegistration, notice])
  if (auth.isAuthenticated) return <Navigate to={auth.user?.roles?.includes('ADMIN') ? '/admin/experiment' : experimentMode ? '/experiment' : '/ai-tutor'} replace />
  const title = experimentRegistration ? '报名机器学习实验' : experimentMode ? '进入实验' : kind === 'login' ? '欢迎回来' : '创建学习账号'
  const onCode = async () => {
    const { account, phone, password } = getValues()
    if (experimentRegistration) {
      if (!phone || !/^1[3-9]\d{9}$/.test(phone)) return notice('请先填写有效手机号', 'error')
      setSending(true)
      try { await services.sendExperimentRegistrationSms(phone); notice('验证码已发送', 'success') }
      catch (error) { notice(error instanceof Error ? error.message : '验证码发送失败', 'error') }
      finally { setSending(false) }
      return
    }
    if (!account || password.length < 8) return notice('请先填写有效账号和至少 8 位密码', 'error')
    setSending(true)
    try { await services.sendAuthCode(kind, account, password); notice('验证码已发送', 'success') }
    catch (error) { notice(error instanceof Error ? error.message : '验证码发送失败', 'error') }
    finally { setSending(false) }
  }
  const onSubmit = handleSubmit(async data => {
    try {
      if (experimentRegistration) {
        if (!data.name?.trim() || !data.phone || !method || !data.code?.trim()) return notice('请完整填写报名信息', 'error')
        await services.registerExperiment({ name: data.name.trim(), phone_number: data.phone, password: data.password, method, proof: data.code.trim() })
        notice('报名成功，请使用手机号和密码登录', 'success')
        navigate('/login', { replace: true })
        return
      }
      if (!data.account || data.account.length < 5) return notice('请输入邮箱或手机号', 'error')
      if (!experimentMode && (!data.code || data.code.length < 4)) return notice('请输入验证码', 'error')
      const result = experimentMode
        ? await services.experimentLogin(data.account, data.password)
        : await services.authenticate(kind, data.account, data.password, data.code || '')
      auth.login(result)
      notice(kind === 'login' ? '登录成功' : '注册成功', 'success')
      const from = result.user.roles?.includes('ADMIN') ? '/admin/experiment'
        : experimentMode ? '/experiment'
        : (location.state as { from?: string } | null)?.from || '/ai-tutor'
      navigate(from, { replace: true })
    } catch (error) { notice(error instanceof Error ? error.message : '操作失败', 'error') }
  })
  return <div className="auth-page">
    <section className="card auth-card">
      <div className="auth-brand"><span className="brand__mark">L</span></div>
      <span className="eyebrow">柠檬学习</span><h1>{title}</h1>
      <p className="muted">{experimentRegistration ? '填写信息后即可参加当前机器学习实验。' : experimentMode ? '使用报名手机号和密码登录实验。' : '使用邮箱或中国大陆手机号完成验证。'}</p>
      <form className="form-stack" onSubmit={onSubmit}>
        {experimentRegistration ? <>
          <label><span>姓名</span><input {...register('name')} autoComplete="name" placeholder="请输入真实姓名" />{errors.name && <small>{errors.name.message}</small>}</label>
          <label><span>手机号</span><input {...register('phone')} inputMode="tel" autoComplete="tel" placeholder="请输入中国大陆手机号" />{errors.phone && <small>{errors.phone.message}</small>}</label>
          {methods.length > 1 && <fieldset className="auth-methods"><legend>验证方式</legend>{methods.map(next => <label key={next}><input type="radio" checked={method === next} onChange={() => setMethod(next)} />{next === 'SMS_VERIFIED' ? '短信验证码' : '实验邀请码'}</label>)}</fieldset>}
        </> : <label><span>{experimentMode ? '手机号' : '邮箱或手机号'}</span><input {...register('account')} autoComplete="username" placeholder={experimentMode ? '请输入报名手机号' : 'name@example.com / 138…'} />{errors.account && <small>{errors.account.message}</small>}</label>}
        <label><span>密码</span><input {...register('password')} type="password" autoComplete={kind === 'login' ? 'current-password' : 'new-password'} placeholder="至少 8 位" />{errors.password && <small>{errors.password.message}</small>}</label>
        {(!experimentMode || experimentRegistration) && <label><span>{experimentRegistration && method === 'INVITE_CODE' ? '实验邀请码' : '短信验证码'}</span><div className="inline-field"><input {...register('code')} inputMode="numeric" autoComplete="one-time-code" placeholder={experimentRegistration && method === 'INVITE_CODE' ? '邀请码' : '验证码'} />{(!experimentRegistration || method === 'SMS_VERIFIED') && <button type="button" className="button button--soft" onClick={onCode} disabled={sending}>{sending ? '发送中…' : '获取验证码'}</button>}</div>{errors.code && <small>{errors.code.message}</small>}</label>}
        <button className="button button--primary button--block" disabled={isSubmitting || (experimentRegistration && methods.length === 0)}>{isSubmitting ? '处理中…' : title}</button>
      </form>
      {(!experimentMode || experimentRegistration || kind === 'login') && <p className="auth-switch">{kind === 'login' ? (experimentMode ? '首次参加实验？' : '还没有账号？') : (experimentMode ? '已经报名？' : '已经有账号？')} <Link to={kind === 'login' ? '/register' : '/login'}>{kind === 'login' ? (experimentMode ? '去报名' : '立即注册') : '去登录'}</Link></p>}
    </section>
  </div>
}
