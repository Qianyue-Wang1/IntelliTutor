import { Link } from 'react-router'

export default function NotFound() {
  return <div className="empty-page"><span className="eyebrow">404</span><h1>这个页面走丢了</h1><p>回到首页，继续向 AI 导师提问吧。</p><Link className="button button--primary" to="/">返回首页</Link></div>
}

