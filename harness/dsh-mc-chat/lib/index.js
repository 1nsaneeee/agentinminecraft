/*! dsh-mc-chat — 把 mcagent 的 WebSocket 聊天转发成会话消息，唤醒一轮 */
//
// 数据流：
//   玩家在游戏里说话 → mcagent 推 {"type":"chat"} → 本插件 → agent.followup(...) → 起一轮
//   玩家在游戏里敲 /work /chat /new /session
//                   → mcagent 推 {"type":"agent_cmd"} → 本插件改模式/换会话
//                   → 本插件推回 {"type":"say"} 在游戏里回一行确认
//
// 会话语义只住在这边。游戏那边不知道自己有几个会话，它只负责把命令原样转过来。
//
// followup 是 schedule / 后台任务结束通知共用的入口，不是私有门。
// 插件本身不持有任何凭据来源的副本：token 优先从 mcagent 自己的配置文件读，
// 这样轮换 token 只需要改一处。

import { createRequire } from 'node:module'
import { existsSync, readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

// 插件目录下没有 node_modules，借用 profile 的依赖树解析 @deepseek-ai/*。
const anchor = createRequire(new URL('../../../profiles/desktop/package.json', import.meta.url))
const { pathToFileURL } = anchor('node:url')
const { createUserMessage } = await import(pathToFileURL(anchor.resolve('@deepseek-ai/dsh-llm')).href)

const RECONNECT_MS = 5000

// 每个会话的聊天风格。工作在 work，闲聊在 chat。
const CHAT_NOTE = [
  '',
  '',
  '[模式：聊天] 现在在闲聊，不在干活。',
  '- 一条一句。想多说就分开发：每次单独调用一次 mc_chat，不要把几句话堆进同一条。',
  '- 可以损他、取笑他、接梗、像小孩一样闹他。落点是可爱，不是刻薄。',
  '- 不编造事实：没做过的事不要说做过，不知道就说不知道。',
  '- 想动手就用身体（走路、挖、放、用、打）。直接改世界的工具在这个模式下会被拒绝，别硬试。',
].join('\n')

export const name = 'mc-chat'
export const inject = ['agents']

export function apply(ctx, config) {
  let socket
  let timer
  let closed = false
  let announced = false

  // 模式与会话是插件内存状态：DSH 重启就回到 work + 默认会话。
  const state = { mode: 'work', activeId: null }

  const log = (msg) => ctx.logger?.info?.(`[mc-chat] ${msg}`)
  // 取尾部而不是开头：harness 的会话 id 形如 session-<uuid>，前 8 位永远是
  // "session-"，两个会话在游戏里会显示成同一串。尾部才是唯一的。
  const short = (id) => String(id ?? '?').slice(-8)

  // 配置路径按优先级找，这样发布时不必在 profile patch 里写死绝对路径：
  //   1. 插件 config.configPath（显式指定）
  //   2. 环境变量 MCAGENT_CONFIG
  //   3. 当前工作目录下的 config/mcagent.json（DSH 从游戏实例目录启动时正好命中）
  function resolveConfigPath() {
    if (config?.configPath) return config.configPath
    if (process.env.MCAGENT_CONFIG) return process.env.MCAGENT_CONFIG
    // Current file name first, then the pre-rename one, so an install that has not
    // migrated yet still connects.
    for (const candidate of ['config/agentinminecraft.json', 'config/mcagent.json']) {
      if (existsSync(candidate)) return candidate
    }
    return 'config/agentinminecraft.json'
  }

  function endpoint() {
    const path = resolveConfigPath()
    if (path) {
      try {
        const cfg = JSON.parse(readFileSync(path, 'utf8'))
        const host = cfg.host || '127.0.0.1'
        // Only ever talk to loopback; a bumped host in the game config is not a
        // reason for the harness to start dialling remote machines.
        if (host !== '127.0.0.1' && host !== 'localhost') return null
        const port = cfg.port || 38121
        return `ws://${host}:${port}/ws?token=${encodeURIComponent(cfg.token)}`
      } catch {
        // fall through to the explicit url
      }
    }
    return config?.url || null
  }

  /** 回一行到游戏里。走 mod 的 WS 入站 say，不需要任何凭据。 */
  function say(text) {
    try {
      socket?.send(JSON.stringify({ type: 'say', text }))
    } catch (error) {
      log(`回话失败：${error}`)
    }
  }

  function idOf(agent) {
    return agent?.id ?? agent?.sessionId ?? agent?.session?.id ?? null
  }

  function agentList() {
    try {
      return ctx.agents.list()
    } catch {
      return []
    }
  }

  function activeAgent() {
    const agents = ctx.agents
    if (state.activeId) {
      const found = agents.get(state.activeId)
      if (found) return found
      // 会话被关掉了，别一直拿着死引用。
      state.activeId = null
    }
    if (config?.sessionId) {
      const configured = agents.get(config.sessionId)
      if (configured) return configured
    }
    return agents.roots().at(-1)
  }

  /** 发一个富文本组件到游戏里（mod 侧有 say_json 入站）。 */
  function sendComponent(component) {
    try {
      socket?.send(JSON.stringify({ type: 'say_json', component }))
    } catch (error) {
      log(`发组件失败：${error}`)
    }
  }

  /**
   * 会话列表做成可点的条目。run_command 的 value 不带斜杠，这是 MC 的约定；
   * 点一下等价于自己在聊天栏敲 /agent session N。
   */
  function saySessionList() {
    const list = agentList()
    if (list.length === 0) {
      say('没有活着的会话')
      return
    }
    const current = idOf(activeAgent())
    const extra = []
    list.forEach((a, i) => {
      const id = idOf(a)
      const isCurrent = id === current
      extra.push({ text: '  ' })
      extra.push({
        text: `[${i + 1}] ${short(id)}${isCurrent ? '*' : ''}`,
        color: isCurrent ? 'green' : 'aqua',
        // 必须带斜杠：客户端在 RUN_COMMAND 分支里对 value 做 substring(1)，
        // 不带的话第一个字母会被砍掉（agent -> gent），命令就静默失败了。
        clickEvent: { action: 'run_command', value: `/agent session ${i + 1}` },
        hoverEvent: { action: 'show_text', contents: { text: `切换到 ${short(id)}` } },
      })
    })
    sendComponent({ text: `会话（${state.mode}）: `, extra })
  }

  function resolveSession(arg) {
    const list = agentList()
    const raw = String(arg).trim()
    const index = Number(raw)
    if (Number.isInteger(index) && index >= 1 && index <= list.length) {
      return list[index - 1]
    }
    return list.find((a) => String(idOf(a) ?? '').startsWith(raw)) ?? null
  }

  async function handleCommand(frame) {
    const cmd = String(frame.cmd ?? '')
    const arg = frame.arg

    switch (cmd) {
      case 'work':
        state.mode = 'work'
        say('工作模式。')
        return

      case 'chat':
        state.mode = 'chat'
        say('聊天模式。我闭嘴干活，改贫嘴了。')
        return

      case 'session': {
        if (!arg) {
          saySessionList()
          return
        }
        const target = resolveSession(arg)
        if (!target) {
          say(`没有叫 ${String(arg).slice(0, 12)} 的会话`)
          return
        }
        state.activeId = idOf(target)
        say(`切到 ${short(state.activeId)}。`)
        return
      }

      case 'new': {
        // 真开一个新会话：新上下文，不是清空当前这个。
        // SessionId 只是编译期 brand，运行时就是字符串，所以这里不需要导入它。
        //
        // 必须把当前 agent 的 options 带过去。不带的话新 agent 没有模型，提示词里
        // {{model}} 解析不出来，每一轮都会以
        // 'prompt variable "{{model}}" has no value ... (section "deployment/persona-prefix")'
        // 失败 —— 看起来像"没反应"，其实是每轮都起不来。
        const source = activeAgent()
        const src = source?.options ?? {}
        const meta = { cwd: config?.cwd || process.cwd() }
        const preset = source?.session?.meta?.agentPreset
        if (preset) meta.agentPreset = preset

        const id = randomUUID()
        try {
          const handle = await ctx.agents.create({
            sessionId: id,
            meta,
            agentOptions: {
              provider: src.provider,
              model: src.model,
              ...(src.maxTokens ? { maxTokens: src.maxTokens } : {}),
            },
          })
          const created = handle?.agent
          if (!created || !created.options?.model) {
            say('新会话没有拿到模型，已撤销。')
            try { await handle?.dispose() } catch {}
            return
          }
          state.activeId = id
          say(`新会话 ${short(id)}（${created.options.provider}/${created.options.model}）。`)
        } catch (error) {
          say(`开新会话失败：${error?.message ?? error}`)
        }
        return
      }

      default:
        log(`未知命令：${cmd}`)
    }
  }

  function deliver(player, text) {
    const agent = activeAgent()
    if (!agent) {
      log('没有可投递的会话')
      return
    }
    const modeNote = state.mode === 'chat' ? CHAT_NOTE : ''
    const body =
      `Minecraft 玩家 ${player} 在游戏里说：「${text}」\n` +
      `（这是游戏内聊天，回复请用 mc_chat 工具发回游戏）${modeNote}`
    try {
      agent.followup(createUserMessage({
        content: [{ type: 'text', text: body }],
        // 玩家在游戏里打字就是用户输入，`kind: 'user'` 是唯一保证被 durable
        // session format 接受的 source kind。`kind: 'plugin'` 需要插件在 harness
        // 侧登记成 producer，否则起轮时会以
        // "format v4 message requires a producer-owned source kind" 失败，
        // 且该失败发生在 followup 之后，插件里的 try/catch 抓不到。
        source: { kind: 'user' },
      }))
    } catch (error) {
      log(`投递失败：${error}`)
    }
  }

  function connect() {
    if (closed) return
    const url = endpoint()
    if (!url) {
      log('没有可用端点（configPath 读不到且未配置 url）')
      timer = setTimeout(connect, RECONNECT_MS)
      return
    }
    try {
      socket = new WebSocket(url)
    } catch (error) {
      log(`连接创建失败：${error}`)
      timer = setTimeout(connect, RECONNECT_MS)
      return
    }

    socket.onopen = () => {
      if (!announced) {
        announced = true
        log(`已连上游戏内的聊天通道（${state.mode} 模式）`)
      }
    }
    socket.onmessage = (event) => {
      let frame
      try {
        frame = JSON.parse(event.data)
      } catch {
        return
      }
      if (frame?.type === 'agent_cmd') {
        handleCommand(frame).catch((error) => log(`命令处理失败：${error}`))
        return
      }
      if (frame?.type !== 'chat') return
      // system:true 是 agent 自己发的和 join/leave 提示，转发会造成自激。
      if (frame.system) return
      if (!frame.text) return
      deliver(frame.player || '?', frame.text)
    }
    socket.onclose = () => {
      if (closed) return
      announced = false
      timer = setTimeout(connect, RECONNECT_MS)
    }
    socket.onerror = () => {
      try {
        socket?.close()
      } catch {}
    }
  }

  ctx.effect(() => {
    connect()
    return () => {
      closed = true
      clearTimeout(timer)
      try {
        socket?.close()
      } catch {}
    }
  })
}
