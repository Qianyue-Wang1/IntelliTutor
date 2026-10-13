import { NavLink, Outlet, useNavigate } from 'react-router'
import { useCapabilities } from '@/hooks/useCapabilities'
import { useAuth } from '@/providers/AuthProvider'

const links = [
  { to: '/', label: '首页', end: true },
  { to: '/ai-tutor', label: 'AI 导师', end: false },
  { to: '/me', label: '我的', end: false },
]

export default function AppShell() {
  const { capabilities } = useCapabilities()
  const auth = useAuth()
  const navigate = useNavigate()
  const experiment = capabilities.experiment_mode_enabled
  const visibleLinks = experiment ? [] : links
  return <div className="app-frame">
    <a className="skip-link" href="#main-content">跳到主要内容</a>
    <header className="topbar">
      <div className="shell topbar__inner">
        <NavLink to="/" className="brand" aria-label="柠檬学习首页">
          <span className="brand__mark" aria-hidden="true">
            <img src="/favicon.svg" alt="" />
          </span>
          <span className="brand__copy">
            <strong className="brand__title">柠檬学习</strong>
            <small className="brand__tagline">心动 · 行动 · 交互</small>
          </span>
        </NavLink>
        <nav className="topbar__nav" aria-label="主导航">
          {visibleLinks.map(({ to, label, end }) => <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) => `topbar__link${isActive ? ' is-active' : ''}`}
          >{label}</NavLink>)}
          {experiment && auth.user?.roles?.includes('ADMIN') && <NavLink to="/admin/experiment" className={({ isActive }) => `topbar__link${isActive ? ' is-active' : ''}`}>实验控制台</NavLink>}
          {auth.isAuthenticated && <button type="button" className="topbar__link topbar__logout" onClick={() => {
            auth.logout()
            navigate('/login', { replace: true })
          }}>退出登录</button>}
        </nav>
      </div>
    </header>
    <main className="shell page" id="main-content"><Outlet /></main>
  </div>
}
