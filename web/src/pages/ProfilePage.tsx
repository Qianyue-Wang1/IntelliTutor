import { useState, type ChangeEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenCheck, KeyRound, LogOut, MessageCircleQuestion, Save, Trash2, Upload, UserRound } from 'lucide-react'
import { useNavigate } from 'react-router'
import { services } from '@/api/services'
import type { UserInfo } from '@/api/types'
import { useAuth } from '@/providers/AuthProvider'
import { useNotice } from '@/providers/NoticeProvider'

type Panel = 'profile' | 'contact' | 'password' | 'sessions'

export default function ProfilePage() {
  const auth = useAuth()
  const notify = useNotice()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [panel, setPanel] = useState<Panel>('profile')
  const [nickname, setNickname] = useState<string | null>(null)
  const [contactType, setContactType] = useState<'email' | 'phone'>('email')
  const [contact, setContact] = useState({ password: '', originalCode: '', value: '', newCode: '' })
  const [password, setPassword] = useState({ old: '', next: '', code: '', type: 'email' as 'email' | 'phone' })
  const userQuery = useQuery({ queryKey: ['user'], queryFn: services.userInfo })
  const sessionsQuery = useQuery({ queryKey: ['sessions'], queryFn: services.sessions })

  const updateUserCache = (user: Partial<UserInfo>) => {
    queryClient.setQueryData(['user'], (old: UserInfo | undefined) => ({ ...(old || userQuery.data), ...user }))
    auth.updateUser(user)
  }
  const saveNickname = async () => {
    try { const user = await services.updateUser({ new_nickname: (nickname ?? userQuery.data?.nickname ?? '').trim() }); updateUserCache(user); setNickname(user.nickname || ''); notify('昵称已保存', 'success') }
    catch (error) { notify(error instanceof Error ? error.message : '保存失败', 'error') }
  }
  const avatarChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (!file.type.startsWith('image/') || file.size > 2 * 1024 * 1024) return notify('请选择不超过 2MB 的图片', 'error')
    const reader = new FileReader()
    reader.onload = async () => {
      try { const result = await services.updateAvatar(String(reader.result)); updateUserCache({ avatar: result.avatar }); notify('头像已更新', 'success') }
      catch (error) { notify(error instanceof Error ? error.message : '头像更新失败', 'error') }
    }
    reader.readAsDataURL(file)
  }
  const sendOriginal = async () => {
    try { await services.verifyOriginal(contactType, contact.password); notify('原联系方式验证码已发送', 'success') }
    catch (error) { notify(error instanceof Error ? error.message : '验证码发送失败', 'error') }
  }
  const sendNew = async () => {
    try { await services.sendChangeContactCode(contactType, contact.value, contact.originalCode || undefined); notify('新联系方式验证码已发送', 'success') }
    catch (error) { notify(error instanceof Error ? error.message : '验证码发送失败', 'error') }
  }
  const saveContact = async () => {
    try {
      const body = contactType === 'email'
        ? { new_email: contact.value, original_verify_code: contact.originalCode || undefined, new_verify_code: contact.newCode }
        : { new_phone_number: contact.value, original_verify_code: contact.originalCode || undefined, new_verify_code: contact.newCode }
      const user = await services.updateUser(body); updateUserCache(user); setContact({ password: '', originalCode: '', value: '', newCode: '' }); notify('联系方式已更新', 'success')
    } catch (error) { notify(error instanceof Error ? error.message : '更新失败', 'error') }
  }
  const sendPasswordCode = async () => {
    try { await services.sendPasswordCode(password.old, password.type); notify('密码修改验证码已发送', 'success') }
    catch (error) { notify(error instanceof Error ? error.message : '验证码发送失败', 'error') }
  }
  const savePassword = async () => {
    try { await services.changePassword({ old_password: password.old || undefined, new_password: password.next, type: password.type, verify_code: password.code }); setPassword({ old: '', next: '', code: '', type: 'email' }); notify('密码已修改', 'success') }
    catch (error) { notify(error instanceof Error ? error.message : '密码修改失败', 'error') }
  }
  const deleteMutation = useMutation({
    mutationFn: services.deleteSession,
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['sessions'] }); notify('会话已删除', 'success') },
    onError: error => notify(error.message, 'error'),
  })
  const openSession = (id: string, type: 'explanation' | 'discussion') => navigate(
    `/ai-tutor/${type}/${encodeURIComponent(id)}${type === 'explanation' ? '?review=1' : ''}`,
  )
  const logout = () => { auth.logout(); queryClient.clear(); navigate('/'); notify('已退出登录', 'success') }

  const user = userQuery.data
  return <div className="profile-page">
    <header className="page-heading"><span className="eyebrow">我的</span><h1>账号与学习会话</h1><p>管理个人资料、安全设置和历史提问。</p></header>
    <div className="profile-layout">
      <aside className="card profile-nav">
        <div className="profile-person"><div className="avatar">{user?.avatar ? <img src={user.avatar} alt="用户头像" /> : <UserRound />}</div><div><strong>{user?.nickname || '学习者'}</strong><small>{user?.email || user?.phone_number || ''}</small></div></div>
        {[['profile', '个人资料'], ['contact', '联系方式'], ['password', '修改密码'], ['sessions', '学习会话']].map(([id, label]) => <button key={id} className={panel === id ? 'is-active' : ''} onClick={() => setPanel(id as Panel)}>{label}</button>)}
        <button className="profile-nav__logout" onClick={logout}><LogOut size={17} />退出登录</button>
      </aside>
      <section className="card profile-content">
        {userQuery.isLoading ? <p>正在加载资料…</p> : panel === 'profile' ? <>
          <div className="section-heading"><div><span className="eyebrow">个人资料</span><h2>头像和昵称</h2></div></div>
          <div className="avatar-editor"><div className="avatar avatar--large">{user?.avatar ? <img src={user.avatar} alt="用户头像" /> : <UserRound />}</div><label className="button button--soft"><Upload size={17} />更换头像<input hidden type="file" accept="image/*" onChange={avatarChange} /></label></div>
          <div className="form-stack compact-form"><label><span>昵称</span><input value={nickname ?? user?.nickname ?? ''} maxLength={32} onChange={event => setNickname(event.target.value)} /></label><button className="button button--primary align-start" onClick={() => void saveNickname()}><Save size={17} />保存资料</button></div>
        </> : panel === 'contact' ? <>
          <div className="section-heading"><div><span className="eyebrow">联系方式</span><h2>修改邮箱或手机号</h2></div></div>
          <div className="segmented"><button className={contactType === 'email' ? 'is-active' : ''} onClick={() => setContactType('email')}>邮箱</button><button className={contactType === 'phone' ? 'is-active' : ''} onClick={() => setContactType('phone')}>手机号</button></div>
          <div className="form-stack compact-form">
            <label><span>当前密码</span><div className="inline-field"><input type="password" value={contact.password} onChange={event => setContact({ ...contact, password: event.target.value })} /><button className="button button--soft" onClick={() => void sendOriginal()}>验证原账号</button></div></label>
            <label><span>原账号验证码（首次绑定可留空）</span><input value={contact.originalCode} onChange={event => setContact({ ...contact, originalCode: event.target.value })} /></label>
            <label><span>新{contactType === 'email' ? '邮箱' : '手机号'}</span><div className="inline-field"><input value={contact.value} onChange={event => setContact({ ...contact, value: event.target.value })} /><button className="button button--soft" onClick={() => void sendNew()}>发送新验证码</button></div></label>
            <label><span>新账号验证码</span><input value={contact.newCode} onChange={event => setContact({ ...contact, newCode: event.target.value })} /></label>
            <button className="button button--primary align-start" onClick={() => void saveContact()}><Save size={17} />确认修改</button>
          </div>
        </> : panel === 'password' ? <>
          <div className="section-heading"><div><span className="eyebrow">安全设置</span><h2>修改密码</h2></div></div>
          <div className="form-stack compact-form">
            <label><span>当前密码</span><input type="password" value={password.old} onChange={event => setPassword({ ...password, old: event.target.value })} /></label>
            <label><span>验证码渠道</span><select value={password.type} onChange={event => setPassword({ ...password, type: event.target.value as 'email' | 'phone' })}><option value="email">邮箱</option><option value="phone">手机号</option></select></label>
            <button className="button button--soft align-start" onClick={() => void sendPasswordCode()}><KeyRound size={17} />发送验证码</button>
            <label><span>验证码</span><input value={password.code} onChange={event => setPassword({ ...password, code: event.target.value })} /></label>
            <label><span>新密码</span><input type="password" minLength={8} value={password.next} onChange={event => setPassword({ ...password, next: event.target.value })} /></label>
            <button className="button button--primary align-start" onClick={() => void savePassword()}><Save size={17} />更新密码</button>
          </div>
        </> : <>
          <div className="section-heading"><div><span className="eyebrow">历史记录</span><h2>讲解与研讨会话</h2></div><span className="muted">{sessionsQuery.data?.length || 0} 条</span></div>
          {sessionsQuery.isLoading ? <p>正在加载会话…</p> : sessionsQuery.data?.length ? <div className="session-list">{sessionsQuery.data.map(item => <article key={item.session_id} className="session-item">
            <button className="session-item__main" onClick={() => openSession(item.session_id, item.type)}>{item.type === 'explanation' ? <BookOpenCheck /> : <MessageCircleQuestion />}<span><strong>{item.question || '未命名问题'}</strong><small>{item.type === 'explanation' ? '逐步讲解' : '启发研讨'} · {new Date(item.last_accessed || item.created_at).toLocaleString('zh-CN')}</small></span></button>
            <button className="icon-button" aria-label="删除会话" disabled={deleteMutation.isPending} onClick={() => { if (window.confirm('确认删除这条会话？删除后无法恢复。')) deleteMutation.mutate(item.session_id) }}><Trash2 size={17} /></button>
          </article>)}</div> : <div className="empty-inline"><p>还没有学习会话。</p><button className="button button--primary" onClick={() => navigate('/ai-tutor')}>开始第一次提问</button></div>}
        </>}
      </section>
    </div>
  </div>
}
