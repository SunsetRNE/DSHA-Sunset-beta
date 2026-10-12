/**
 * dsh-task-notifier — DSHA builtin server plugin.
 *
 * 精准任务完成通知：监听 session/event 的 turn/end（整轮对话结束 =
 * agent 任务完成），通过 DSHA 本实例桥发 App 通知栏提醒。
 * 取代 App 端 TaskNotifier 的「轮询会话文件」方案（不准）。
 *
 * 链路：turn/end → fetch http://127.0.0.1:<.bridge_port 选定端口>/app/notify?title=&text=，X-Token 请求头鉴权
 * App 收到后在后台发通知（App 前台时 TaskNotifier 抑制，这里插件无感知——
 * 由 App 端 /app/notify 处理前台判断）。
 */

import { openSync, readSync, closeSync, lstatSync, fstatSync, constants } from 'node:fs'
import { join } from 'node:path'

/** 无额外依赖（监听 session/event 是 cordis 核心事件，任何插件可用） */
export const inject = []
export const name = 'dsh-task-notifier'

/* 桥端点：端口文件与 .bridge_token 同数据根（DSH_HOME 优先），每次通知重读。
   契约文件是 ≤16 字节的普通文件（同原生 BridgeEndpointFiles.READ_LIMIT），内容只有 ASCII
   十进制 + 一个可选换行。只有真正缺目录项才回退旧默认 3090；0、坏格式、越界、非 ASCII、
   带垃圾尾巴、目录、符号链接（含悬空链接）或读不动都算「端点未知」——返回 null 让调用方
   静默跳过，绝不改试别的回环端口：同机可能有第二个 DSHA 实例，猜端口等于把本实例 token
   交给它的桥。 */
const LEGACY_BRIDGE_PORT = 3090
const BRIDGE_PORT_LIMIT = 16
function bridgeRoot() {
  const home = process.env.DSH_HOME
  return typeof home === 'string' && home.trim() && home.trim() !== '/'
    ? home.trim().replace(/\/+$/, '')
    : '/root/.dsh'
}
function readBridgePortFile(file) {
  // 'missing' 只有真正没有这个目录项；悬空符号链接的 ENOENT 也算已存在 → 'invalid'。
  let entry
  try { entry = lstatSync(file) }
  catch (error) { return error && error.code === 'ENOENT' ? 'missing' : 'invalid' }
  if (!entry.isFile()) return 'invalid'
  let descriptor
  try {
    descriptor = openSync(file, constants.O_RDONLY | (constants.O_NOFOLLOW || 0))
    const stats = fstatSync(descriptor)
    if (!stats.isFile() || stats.size < 1 || stats.size > BRIDGE_PORT_LIMIT) return 'invalid'
    const buffer = Buffer.alloc(stats.size)
    let filled = 0
    while (filled < stats.size) {
      const count = readSync(descriptor, buffer, filled, stats.size - filled, filled)
      if (count <= 0) break
      filled += count
    }
    if (filled !== stats.size) return 'invalid'
    // 整份内容必须就是端口：'3091\n' 后面再有空白/垃圾就不是契约文件；不做 Unicode trim。
    const text = buffer.toString('utf8')
    if (!/^[0-9]{1,5}\n?$/.test(text)) return 'invalid'
    const port = Number(text.replace(/\n$/, ''))
    return port >= 1 && port <= 65535 ? port : 'invalid'
  } catch {
    return 'invalid'
  } finally {
    try { if (descriptor !== undefined) closeSync(descriptor) } catch {}
  }
}
function bridgePort() {
  const value = readBridgePortFile(join(bridgeRoot(), '.bridge_port'))
  if (value === 'missing') return LEGACY_BRIDGE_PORT
  return value === 'invalid' ? null : value
}

/** 通知节流：同一 agent 30s 内只发一次（防连续 turn/end 轰炸） */
const THROTTLE_MS = 30_000
export function completionNotice(reason) {
  if (reason === 'completed') return ['DSHA · 任务完成', 'Agent 已完成一轮对话，点击查看结果']
  if (reason === 'max-tokens') return ['DSHA · 已达到输出上限', '请查看结果，按需继续对话']
  if (reason === 'aborted' || reason === 'interrupted') return ['DSHA · 任务已停止', '本轮任务未完成，点击查看状态']
  return ['DSHA · 任务需要处理', '本轮任务因错误或阻塞结束，请查看对话详情']
}

/** 通过本实例桥发 App 通知（token 鉴权；端点未知时静默跳过） */
async function notifyApp(title, text) {
  try {
    const fs = await import('node:fs/promises')
    let token = ''
    try {
      token = (await fs.readFile(join(bridgeRoot(), '.bridge_token'), 'utf-8')).trim()
    } catch {}
    if (!token) return
    const port = bridgePort()
    if (!port) return
    // 用 fetch（node 18+ 内置）调本实例桥 /app/notify
    const url = 'http://127.0.0.1:' + port + '/app/notify'
      + '?title=' + encodeURIComponent(title)
      + '&text=' + encodeURIComponent(text)

    const resp = await fetch(url, { headers: {'X-Token': token}, signal: AbortSignal.timeout(5000) })
    await resp.text()
  } catch {}
}

/**
 * Plugin entry.
 * @param {import('@deepseek-ai/cordis').Context} ctx
 */
export function apply(ctx) {
  const lastNotified = new Map()
  ctx.on('dispose', () => lastNotified.clear())
  ctx.on('session/event', (session, event) => {
    try {
      if (event.type !== 'turn/end') return
      // 整轮结束 = 任务完成（turnEnds.reason.kind: completed / blocked / max-tokens 等）
      const reason = event.data?.reason?.kind ?? 'unknown'
      // 正常完成
      const sessionId = session?.id ?? 'session'
      const now = Date.now()
      const last = lastNotified.get(sessionId) ?? 0
      if (now - last < THROTTLE_MS) return
      lastNotified.set(sessionId, now)
      if (lastNotified.size > 256) lastNotified.delete(lastNotified.keys().next().value)
      const [title, text] = completionNotice(reason)
      void notifyApp(title, text)
    } catch {}
  })
}
