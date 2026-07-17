import { KeyRound, Search, X } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { credentials } from '../shared/api/client'
import { navigation } from './navigation'

export function AppShell() {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [apiKey, setApiKey] = useState(credentials.apiKey)
  const [tenant, setTenant] = useState(credentials.tenantId)
  const [actor, setActor] = useState(credentials.actorId)
  function save() {
    credentials.save(apiKey, tenant, actor)
    setSettingsOpen(false)
    window.dispatchEvent(new Event('credentials-changed'))
  }
  return <div className="shell"><aside className="sidebar"><div className="brand"><span>MC</span><strong>MiniClaude</strong></div><nav>{navigation.map(({ label, path, icon: Icon }) => <NavLink key={path} to={path} end={path === '/'}><Icon size={17} /><span>{label}</span></NavLink>)}</nav><button className="credential-button" onClick={() => setSettingsOpen(true)}><KeyRound size={16} /><span><strong>API 凭据</strong><small>{credentials.apiKey ? '已配置会话密钥' : '本机无密钥模式'}</small></span></button></aside><section className="workspace"><header className="topbar"><label><Search size={16} /><input aria-label="全局搜索" placeholder="搜索任务、智能体或资产" /></label><div><span className="health-dot" />{credentials.tenantId}<b>{credentials.actorId.slice(0, 2).toUpperCase()}</b></div></header><main><Outlet /></main></section>{settingsOpen && <div className="modal-backdrop"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="credential-title"><header><div><h2 id="credential-title">开发 API 凭据</h2><p>仅保存在当前标签页的 sessionStorage，不进入构建产物。</p></div><button aria-label="关闭" onClick={() => setSettingsOpen(false)}><X size={18} /></button></header><div className="form-grid"><label className="full">Platform API Key<input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="X-Platform-Api-Key" /></label><label>租户 ID<input value={tenant} onChange={(e) => setTenant(e.target.value)} /></label><label>操作者 ID<input value={actor} onChange={(e) => setActor(e.target.value)} /></label></div><footer><button className="button secondary" onClick={() => setSettingsOpen(false)}>取消</button><button className="button primary" onClick={save}>保存到当前会话</button></footer></section></div>}</div>
}
