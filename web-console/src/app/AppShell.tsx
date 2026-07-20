/**
 * 应用外壳：全局侧栏导航、顶栏上下文、路由 Outlet 与开发 API 凭据设置模态框。
 *
 * 组件职责：
 * - 渲染 navigation 静态菜单，通过 NavLink 驱动 react-router 切换
 * - 顶栏展示当前 tenantId 与 actorId 缩写（只读展示，可被用户篡改 sessionStorage 改变）
 * - 「API 凭据」按钮打开模态框，将 Key/租户/操作者写入 sessionStorage
 *
 * Hooks 副作用：
 * - settingsOpen：控制凭据模态框显隐
 * - apiKey/tenant/actor：本地受控表单状态，初始值从 credentials getter 读取 sessionStorage
 *
 * 安全说明：
 * - sessionStorage 仅降低持久化范围（关标签页即失），仍能被同源脚本读取，不等于安全存储
 * - 保存后 dispatch credentials-changed 事件，供未来扩展的同页监听者刷新（当前 client 每次请求现读 storage）
 * - 前端导航与控件可见性不是服务端权限边界
 */
import { KeyRound, Search, X } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { credentials } from '../shared/api/client'
import { navigation } from './navigation'

export function AppShell() {
  const [settingsOpen, setSettingsOpen] = useState(false)
  // 表单初始值与 sessionStorage 同步；打开模态框时不会自动 re-read，用户需取消再开才能看到外部变更。
  const [apiKey, setApiKey] = useState(credentials.apiKey)
  const [tenant, setTenant] = useState(credentials.tenantId)
  const [actor, setActor] = useState(credentials.actorId)

  /**
   * 将开发凭据写入当前标签页 sessionStorage，并通知同页监听者重新读取。
   * 空 API Key 会 removeItem，使后续请求不带 X-Platform-Api-Key（依赖后端是否允许无 Key 模式）。
   */
  function save() {
    credentials.save(apiKey, tenant, actor)
    setSettingsOpen(false)
    window.dispatchEvent(new Event('credentials-changed'))
  }

  return <div className="shell"><aside className="sidebar"><div className="brand"><span>MC</span><strong>MiniClaude</strong></div><nav>{navigation.map(({ label, path, icon: Icon }) => <NavLink key={path} to={path} end={path === '/'}><Icon size={17} /><span>{label}</span></NavLink>)}</nav><button className="credential-button" onClick={() => setSettingsOpen(true)}><KeyRound size={16} /><span><strong>API 凭据</strong><small>{credentials.apiKey ? '已配置会话密钥' : '本机无密钥模式'}</small></span></button></aside>{/* 主工作区：Outlet 渲染 App.tsx 中匹配的子路由页面。 */}<section className="workspace"><header className="topbar"><label><Search size={16} /><input aria-label="全局搜索" placeholder="搜索任务、智能体或资产" /></label><div><span className="health-dot" />{credentials.tenantId}<b>{credentials.actorId.slice(0, 2).toUpperCase()}</b></div></header><main><Outlet /></main></section>{/* 凭据模态框：password 类型输入避免 shoulder surfing；仍无法防止浏览器扩展读取。 */}{settingsOpen && <div className="modal-backdrop"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="credential-title"><header><div><h2 id="credential-title">开发 API 凭据</h2><p>仅保存在当前标签页的 sessionStorage，不进入构建产物。</p></div><button aria-label="关闭" onClick={() => setSettingsOpen(false)}><X size={18} /></button></header><div className="form-grid"><label className="full">Platform API Key<input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="X-Platform-Api-Key" /></label><label>租户 ID<input value={tenant} onChange={(e) => setTenant(e.target.value)} /></label><label>操作者 ID<input value={actor} onChange={(e) => setActor(e.target.value)} /></label></div><footer><button className="button secondary" onClick={() => setSettingsOpen(false)}>取消</button><button className="button primary" onClick={save}>保存到当前会话</button></footer></section></div>}</div>
}
