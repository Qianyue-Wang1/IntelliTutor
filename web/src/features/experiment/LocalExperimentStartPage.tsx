import { useState } from 'react'
import { useNavigate } from 'react-router'
import { services } from '@/api/services'
import { useAuth } from '@/providers/AuthProvider'

export default function LocalExperimentStartPage() {
  const navigate = useNavigate()
  const { login, isAuthenticated } = useAuth()
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [error, setError] = useState('')
  const [working, setWorking] = useState(false)
  if (isAuthenticated) return <main className="experiment-narrow"><button className="button button--primary" onClick={() => navigate('/experiment')}>继续当前实验</button></main>
  const enter = async (route: '/experiment' | '/experiment/records') => {
    setError('')
    if (!name.trim() || !/^1[3-9]\d{9}$/.test(phone)) { setError('请填写姓名和有效手机号。'); return }
    setWorking(true)
    try { login(await services.startLocalExperiment(name.trim(), phone)); navigate(route, { replace: true }) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '无法识别参与者信息') }
    finally { setWorking(false) }
  }
  return <main className="experiment-narrow"><section className="card experiment-card"><p className="eyebrow">机器学习连续学习实验</p><h1>开始实验</h1><p>填写信息后将依次完成模式选择、五轮学习和后测。无需创建账号或设置密码。</p><label>姓名<input value={name} maxLength={120} onChange={event => setName(event.target.value)} /></label><label>手机号<input value={phone} inputMode="numeric" maxLength={11} onChange={event => setPhone(event.target.value)} /></label>{error && <p className="form-error">{error}</p>}<div className="experiment-actionbar"><button className="button button--primary" disabled={working} onClick={() => void enter('/experiment')}>{working ? '正在进入…' : '开始或继续实验'}</button><button className="button button--soft" disabled={working} onClick={() => void enter('/experiment/records')}>查看历史记录</button></div></section></main>
}
