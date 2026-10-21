# Changelog

## 0.3.2 — Following, handing things over, and floors that sit on the ground

### New tools (35 → 39)

- `mc_player_follow` / `mc_player_follow_stop` — keep the body tailing a player. Walking is
  one-shot; following re-plans whenever the gap grows past four blocks and stops at two and
  a half, so it neither crowds nor shuffles a step every time the target moves.
- `mc_player_give` — put items into the body's inventory. If nothing fits, the rest is
  dropped at its feet and the result says so instead of swallowing it.
- `mc_player_throw` — throw whatever it is holding at a player, aimed at their chest so the
  arc lands near their feet.

### New: a build skill

The "floors always end up one block too high" rule is now a skill document with six real
failures attached. Three rebuilds in a row made it clear that a reminder inside a chat is
not the same thing as a rule.

### Fixed

- **Jumping did nothing.** The probe showed `jump=true`, `onGround=true`, and vertical speed
  unchanged tick after tick: setting the jumping flag has no impulse for this body. Applying
  `jumpFromGround()` fixed climbing, gap jumps and idle hops at once.
- **Water stopped the body dead.** On land the ascend flag is meaningless, but in water it
  is the only way up and nothing was holding it, so the body sank and stood there.
- **Long routes found no path.** The A\* had a fixed 6000-node budget and a fixed 10-block
  margin; a 35-block route through village houses ran out of both. Now 30000 nodes, with the
  margin growing by distance.

## 0.3.1 — Three problems that only showed up in play

### Fixed

- **Diagonal stairs were unreachable.** Pathfinding only offered the four cardinal
  directions, so a staircase one block apart and one block up did not exist as a route: the
  body reported "no route found", fell back to steering, and pushed into the first step.
  The four diagonals are now available, with the game's own corner rule.
- **Entering water stopped movement** (the ascend flag, see 0.3.2 for the full fix).
- **Long routes gave up** (see 0.3.2).

### New

- Idle gestures: every 5–14 seconds the body turns to look at a nearby player, glances
  around, rolls its shoulders, crouches, or hops in place. Any command cancels it.
- A `step probe` log line while a step is being attempted: dy, distance, on-ground,
  collision, vertical speed and the jump flag.

## 0.3.0 — A body that can actually work

### New: a real player body

`mc_player_spawn` / `despawn` / `list` / `look` / `hold`, `mc_player_walk` / `halt`,
`mc_player_break` / `place` / `use` / `attack`, and a developer-only
`mc_player_teleport` that chat mode refuses. Local skins via `skinFile` / `skinPlayer` /
`skinModel`.

### New: two modes

`/agent work` and `/agent chat`. Chat mode is enforced at the tool entry: direct world
edits, commands and teleports are refused, and the agent cannot switch itself back.

### New: config management

`/agent config`, `/agent config set`, `/agent config reload`. `set` writes a single key;
blank values are refused; the token is only ever shown truncated.

### Fixed (external code review)

`mc_undo` was missing the mode and write checks; the WebSocket `exec` path bypassed chat
mode; `mc_fill` snapshots could disagree with the executed dimension; stopping a world left
fake players, undo history and watchers behind; breaking called `destroyBlock` directly,
skipping spawn protection and the `LeftClickBlock` hook; chunked request bodies could
exceed the 8 MiB limit; double ticking.
