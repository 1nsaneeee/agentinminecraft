#!/usr/bin/env node
// Live channel client for the mcagent bridge.
//
// Prints one JSON line per game event. Two modes matter for agent use:
//   --exit-on chat   print events until a player chat line arrives, then exit
//                    (a finished process is what wakes the agent up)
//   --follow         never exit on its own; use for a persistent monitor
//
// Usage:
//   node ws-watch.mjs --token <t> [--port 38121] [--exit-on chat|never]
//                     [--timeout 900000] [--filter regex] [--say "text"]

const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
}
const port = Number(arg('port', '38121'));
const token = arg('token', process.env.MCAGENT_TOKEN || '');
const exitOn = arg('exit-on', 'chat'); // chat | never
const timeoutMs = Number(arg('timeout', '900000'));
const filter = arg('filter', null);
const say = arg('say', null);
// Extra topics beyond the defaults (chat, events). "blocks" gives machine
// state changes and block place/break events.
const topics = (arg('topics', '') || '').split(',').map((t) => t.trim()).filter(Boolean);

if (!token) {
  console.error('missing --token');
  process.exit(2);
}

const url = `ws://127.0.0.1:${port}/ws?token=${encodeURIComponent(token)}`;
const re = filter ? new RegExp(filter, 'i') : null;
let done = false;

function finish(code) {
  if (done) return;
  done = true;
  try { ws.close(); } catch {}
  process.exit(code);
}

const ws = new WebSocket(url);

ws.addEventListener('open', () => {
  console.log(JSON.stringify({ type: 'connected', url: url.replace(token, '<token>'), port }));
  if (topics.length) ws.send(JSON.stringify({ type: 'sub', topics }));
  if (say) ws.send(JSON.stringify({ type: 'say', text: say }));
});

ws.addEventListener('message', (ev) => {
  let msg;
  try {
    msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data));
  } catch {
    console.log(JSON.stringify({ type: 'raw', data: String(ev.data) }));
    return;
  }
  if (msg.type === 'hello') {
    console.log(JSON.stringify({ type: 'hello', state: msg.state }));
    return;
  }
  if (msg.type === 'chat') {
    if (re && !re.test(msg.text || '')) return;
    console.log(JSON.stringify(msg));
    if (exitOn === 'chat') finish(0);
    return;
  }
  if (msg.type === 'event') {
    // --exit-on event:player_join wakes the agent the moment the world loads.
    console.log(JSON.stringify(msg));
    if (exitOn === 'event:' + msg.kind) finish(0);
    return;
  }
  if (msg.type === 'watcher' || msg.type === 'block_place' || msg.type === 'block_break') {
    // "blocks" topic frames: machine state changes and block-entity place/break.
    console.log(JSON.stringify(msg));
    if (exitOn === msg.type) finish(0);
    return;
  }
  if (msg.type === 'pong') return;
  console.log(JSON.stringify(msg));
});

ws.addEventListener('error', () => {
  console.error('ws error');
  finish(1);
});

ws.addEventListener('close', () => {
  if (!done) finish(0);
});

setTimeout(() => {
  console.log(JSON.stringify({ type: 'timeout', afterMs: timeoutMs }));
  finish(3);
}, timeoutMs).unref?.();
