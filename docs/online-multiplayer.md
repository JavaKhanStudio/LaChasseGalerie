# Going online: analysis and plan

**Question asked (r15):** we want online multiplayer with lobbies. Ideally *we* host the lobbies,
and once a game starts the **host player** carries the net traffic. Is that still feasible, with
clean UDP, sockets that firewalls do not eat, and so on?

**Short answer: yes, that topology is the right one and nothing in the code makes it impossible.**
The networking part — UDP, hole punching, a lobby service — is a known, bounded problem, and this
game is unusually easy on it: one screen, one shared camera, a world the size of a screen, at most a
few dozen bodies. What stands between here and there is **not** the network. It is that the game has
no line between *simulation* and *rendering*, no notion of a player who is not a local controller,
and a world whose coordinates come from the window size. Those have to be fixed first, and they are
worth fixing anyway.

Everything below is measured against the code as of `58d8c79`.

**Decisions settled on the board**, and written into the sections they change:

- **d5 → A (r22): a machine brings one player.** Online, one connection is one hero. The local
  co-op the game has today — a keyboard and any number of pads sharing one screen — stays an
  offline mode. So a `PlayerId` is a peer id and nothing more, a lobby seat is a machine, and the
  snapshot is sized by the number of connections (§1.4, §4, §6). The cheapest reading of A, left
  as an assumption for phase 1 rather than treated as a second decision: on a machine that is in
  an online game, whichever local device is touched drives that machine's one hero — a pad can be
  handed across the couch — instead of a second device being refused or spawning a second hero.
- **d7 → the browser is a client, not a demo (r26): "a way for people to join games if possible,
  as a player".** A tab is a player in somebody's game. A browser cannot open a UDP socket — that is
  the sandbox, not libGDX — so its transport is a WebRTC data channel, and two things follow for
  this plan: **phase 1 sends its packets through a transport interface**, not through
  `DatagramChannel` calls spread across the session code, and **the lobby service is the WebRTC
  signalling server as well** as the endpoint mirror it already had to be. `docs/browser-target.md`
  §6 carries the reasoning and the cost.
- **d6 is still open**: what happens when two players cannot punch through to each other (§3, §7).
  It is now also the browser question: the relay we would pay for is the same box that bridges a
  tab to a host that cannot speak WebRTC itself, so answering d6 with a relay makes d7 cheaper and
  answering it with "forward a port" leaves browser players with nothing.
  Simon answered it with questions rather than a letter — how often punching actually fails, how
  early we can detect it, whether a player can be warned *before* the game starts, and what it
  would cost in CPU to host the games ourselves. Those want numbers, not another proposal.

---

## 1. What the game is today, in the terms this decision needs

Seven findings, each one a constraint on the design.

**1.1 — One global world, never torn down.** `GVars_Game`, `GVars_Controller`, `Gvars_Physic.world`,
`GVars_Story`, `GVars_Interface`, `Index_Sprite` are static singletons with an `init()` and no
teardown. `Smoke_Run` says it out loud: *"Static game state is never reset, so this is one run per
JVM."* A lobby is a screen you come back to; `lobby → game → lobby → game` needs a reset path that
does not exist. This is needed for a plain "play again" button too.

**1.2 — Simulation and rendering are the same code.** `Vue_Game.update()` steps Box2D, runs the
story clock, moves sprites, picks animation states and scrolls the parallax in one pass; `Vue_Game.render()`
then draws. `PhysicSpriteHeroes.act()` reads the body and writes `position` (a drawing field) and the
animation state. `SpriteModel` advances `stateTime` from `Gdx.graphics.getDeltaTime()` directly.
There is no object today that answers "what does a second machine need to be told?". Defining that
boundary is most of the work.

**1.3 — The clock is frame-locked, not fixed-step.** `Gvars_Physic.act()` does `world.step(1/60, 6, 2)`
once per rendered frame, while everything else advances by the real frame delta (clamped to `1/30` in
`Main_Game.render`). It only behaves because `Utils_Launcher.basicConfig` pins vsync and
`setForegroundFPS(60)`. Any machine that drops frames runs its *world* slower, not just its picture.
Online, the sim clock has to be an accumulator that is independent of the render clock — this is the
prerequisite that costs the most, and it is a real single-player bug already.

**1.4 — A player *is* a `Controller` object.** `GVars_Controller.playerList` is
`HashMap<Controller, Player_Inputs>` with `null` meaning "the keyboard"; `GVars_Game.playerRegister`
keys score labels the same way; `PhysicSpriteHeroes.controller` holds the device. A remote player has
no `Controller`. Every one of these needs a `PlayerId` instead, with the `Controller` living only at
the local input edge (`IKM_Game_Keyboard`, `IKM_Game_XBoxController`). Under d5 → A that id is simply
the peer: online there is one hero per connection, and the `Controller` map survives as the offline
local-co-op path and as the way a machine's own devices reach its one hero.

**1.5 — World coordinates are window coordinates.** Nine places multiply `Gdx.graphics.getWidth()/getHeight()`
by `GVars_Camera.worldMutiplier` to get world positions: the camera ortho, hero spawn
(`PhysicSpriteHeroes:77`), the canoe (`PhysicSpriteCanoe:34-35`), all three spawners
(`GVars_Game:93-115`), the parallax, the star. A 1920×1080 host and a 1280×720 client do not have the
same world — the host would spawn monsters off the client's map and the canoe would be somewhere
else. The world needs a fixed size in world units, with the window as a viewport onto it.

**1.6 — Randomness is four static `Random`s.** In `GVars_Story`, `GVars_Game`, `Index_Sprite`,
`PhysicSpriteCanoe` — already enumerated by `Smoke_Run.seedRandoms()`. Under host authority only the
host rolls, so this is not fatal; but one seeded stream, sent to clients at start, is cheap and makes
desync visible in tests.

**1.7 — Box2D is not deterministic across machines.** libGDX uses the native Box2D build for each
platform; float behaviour differs. **Therefore lockstep / deterministic rollback is off the table**,
which is fine: it is exactly the design Simon did not ask for. Host authority it is.

The good news in the same breath: the game is *small*. `./gradlew smoke` at 3 players peaks around
13 bodies and 81 monsters killed in a minute. There is one camera, one screen, no level streaming,
no interest management, no persistence. That is a very forgiving target for a first netcode.

---

## 2. The topology

```
                 ┌──────────────────────┐
   lobby list,   │  Lobby service       │   we host this: small, always on,
   join codes,   │  (Simon's VPS)       │   no game logic, sees only endpoints
   punch setup   └──────────┬───────────┘
                     ▲      │      ▲
        UDP (also    │      │      │
        the STUN     │      │      │
        mirror) ─────┘      │      └──────────────┐
                            ▼                     │
   ┌────────────────────────────────┐      ┌──────┴─────────┐
   │ HOST player                    │◄────►│ CLIENT player  │   direct UDP once punched;
   │  owns the only Box2D world     │ UDP  │  renders only  │   relay only if punching fails
   │  applies everyone's inputs     │      │  sends inputs  │
   │  sends snapshots 20/s          │      └────────────────┘
   └────────────────────────────────┘
```

A browser player (d7) is a client like any other in this picture; only the line between it and the
host changes, from raw UDP to a WebRTC data channel, with the lobby doing the signalling.

**Host authority, dumb clients.** The host runs the one simulation. Clients send an input bitmask and
receive snapshots; **a client does not need a Box2D world at all** — it creates, moves and destroys
sprites from ids in the snapshot, interpolating between the last two. That removes an entire class of
desync bugs for v1, at the price of input latency (see §4). Prediction can come later, for the local
hero only, and only if it is missed.

The host can cheat. For a co-op game you play with friends, that is a correct trade.

---

## 3. UDP, NAT and firewalls — the part Simon asked about directly

**What actually blocks a connection** is not "a firewall" in general, it is a home router's NAT: an
outbound UDP packet creates a mapping and replies to it come back, but unsolicited inbound packets
have nowhere to go. So:

- **Do not require port forwarding.** Requiring it is what kills a friends-and-family game.
- **Hole punching** is the answer: both peers learn each other's public `ip:port` from the lobby
  service and start sending to it at the same time; each side's outbound packet opens the mapping the
  other side's packet needs. It works for the common NAT types (full-cone, restricted, port-restricted),
  which in practice is most residential pairs, and it fails on **symmetric NAT** and on **CGNAT**
  (mobile tethering, and a growing share of ISPs).
- **The trick worth designing in from day one:** talk to the lobby service over the *same* UDP socket
  the game will use. Then the public endpoint the service sees — free STUN — is exactly the mapping the
  game traffic will arrive on. Using a second socket for lobby chatter is the classic way to advertise
  an endpoint that does not work.
- **Keepalives.** UDP NAT mappings expire in roughly 30 s. Both the lobby connection and an idle game
  connection need a packet every 5–15 s, or a lobby that sat two minutes cannot be joined.
- **IPv6 first.** If both ends have IPv6, connect directly — no NAT at all, just a pinhole that the
  outbound packet opens. Order: IPv6 direct → IPv4 punch → relay.
- **Relay fallback (TURN).** For the pairs that cannot punch, traffic goes through a server we pay
  for. Budget: one relayed session is ~0.5 Mbit/s each way (§4), so a $5–10/mo VPS with a 1 TB
  allowance carries on the order of a hundred hours of relayed play a month. Without a relay, some
  percentage of friends simply cannot play, and the honest fallback message is "ask the host to
  forward a port".
- **UPnP / NAT-PMP** as best effort: nice when the router says yes, never relied on.
- **The Windows firewall prompt is a real UX problem, and it is a `#build` problem.** Today the game
  ships as `java -jar LaChasseGalerie-1.0.jar`, so the Defender dialog a host sees says *"java.exe
  wants to accept connections"* — and people click No. Two mitigations: bind an ephemeral port and rely
  only on mappings our own outbound packets created (outbound never prompts), and package the game
  with `jpackage` so the prompt at least carries the game's name and icon.
- **libGDX gives us nothing here.** Verified on the 1.14.2 jar: `com.badlogic.gdx.Net.Protocol` has a
  single constant, `TCP`. libGDX has no UDP. That is fine on desktop — `java.nio.channels.DatagramChannel`
  is all we need — but it does mean netplay could never run on a GWT/HTML backend. The project is
  LWJGL3-only, so nothing is lost.

---

## 4. Budgets: bandwidth and latency

Sizes from the real object set, quantized (positions as 16-bit fixed point, ids 16-bit):

| what | per entity | count at 8 connections | bytes |
|---|---|---|---|
| hero (pos, vel, hp, flags, anim, score) | ~13 B | 8 | 104 |
| axe (pos + angle) | ~6 B | 8 | 48 |
| monster (id, pos, type) | ~7 B | ~30 | 210 |
| potion (id, pos) | ~6 B | ~8 | 48 |
| canoe, story clock, parallax offset, header | — | — | ~20 |
| **snapshot** | | | **~430 B** |

Under d5 → A a hero is a machine, so those 8 heroes are 8 machines: the host plus 7 clients, which
is the busiest session worth planning for.

At 20 snapshots/s that is **~9 kB/s (70 kbit/s) per client**, so a host with 7 clients sends
**~0.5 Mbit/s upstream** — comfortable on any home connection, and one packet stays far under the
~1200 B that avoids IP fragmentation. Upstream from a client is one byte of buttons plus a header at
60 Hz, under 1 kB/s; send the last three input frames in every packet and no reliability layer is
needed for input at all. Delta-encoding against the last acknowledged snapshot would cut the
downstream several times over, but is not needed to ship.

Latency: friends on one continent are 20–60 ms RTT. With host authority and no prediction, your own
hero answers your button after one RTT plus the interpolation buffer (~100 ms). For an arcade brawler
that is noticeable but playable; the axe — a joint-driven physics body that shoves other players — is
the thing that will feel worst, and it is also the thing prediction would be hardest for. Plan to ship
without prediction, measure, then decide.

---

## 5. Stack, with what was actually checked

Checked against Maven Central / JitPack on 2026-09-16:

| option | state | verdict |
|---|---|---|
| `java.nio.channels.DatagramChannel` + our own tiny protocol | JDK | **recommended for the game traffic.** What we need — unreliable ordered-by-tick packets, drop the stale ones — is a few hundred lines, and every alternative hides the packet layout we want to control. |
| KryoNet (`com.esotericsoftware:kryonet`) | last release on Central `2.22.0-RC1`, **published 2014** | the libGDX classic; a maintained fork exists on JitPack (`com.github.crykn:kryonet`, up to 2.22.9). Fastest path if we want objects on the wire instead of bytes, but it would own our packet format. |
| Netty (`io.netty:netty-all` 4.2.18.Final, current) | healthy | overkill for the game socket; a reasonable choice for the **lobby service** if we want one. |
| ice4j (`org.jitsi:ice4j`, published 2026-09-14, Apache-2.0) | healthy, actively released | **recommended for phase 2**: a real ICE/STUN/TURN implementation in Java. Hole punching done properly is more corner cases than it looks, and this is the library that already has them. |
| weupnp (`org.bitlet:weupnp` 0.1.4, 2015) | stale but trivial and stable | fine for best-effort port mapping. |
| coturn | standard TURN server | what the relay VPS would run. |

Lobby service: plain Java (or anything), one small always-on process, no game logic — hold open
lobbies `{code, host endpoint candidates, player count, game version}`, list/join, mirror endpoints
between peers for the punch, gate on version, reap dead lobbies. Under ~500 lines and a $5 VPS.

---

## 6. The plan

Four phases. Everything in phase 0 is worth doing even if online is cancelled, and each phase is
shippable and testable on its own. `./gradlew smoke` is the safety net throughout: it already asserts
the invariants that a refactor of this shape breaks.

### Phase 0 — make the game a simulation (no networking in this phase)

| # | work | touches | how it is proven |
|---|---|---|---|
| 0.1 | Fixed-step sim clock: an accumulator in `Main_Game.render`, `simulate(1/60)` separate from `render()`, animation time fed by the sim clock, not `Gdx.graphics.getDeltaTime()` | `Main_Game`, `AVue_Model`, `Vue_Game`, `Gvars_Physic`, `SpriteModel` | smoke passes; the game plays the same at 60 fps and *keeps* playing the same when frames drop |
| 0.2 | A world of fixed size, window as viewport | `GVars_Camera` (+ a `Viewport`), the 9 sites listed in §1.5 | smoke passes; run at 1280×720 and fullscreen and compare |
| 0.3 | `PlayerId` instead of `Controller` as identity — a peer id online (d5 → A), the local device offline | `GVars_Controller`, `GVars_Game`, `PhysicSpriteHeroes`, both `IKM_*`, `Smoke_Run` | smoke passes with keyboard + 2 pads |
| 0.4 | Session teardown: dispose the world and clear every `GVars_*`, so a second run starts clean | all `GVars_*` | a smoke variant that plays two runs in one JVM |
| 0.5 | One seeded RNG stream, seed settable | the 4 `Random`s of §1.6 | `smoke -Pseed=42` twice gives identical reports |
| 0.6 | Extract the headless loop out of `Smoke_Run` into a runner the host can use with no window | `smoke/`, new `core` entry point | smoke still runs through it |

This is the bulk of the effort and the only part with real risk to game feel (0.1 and 0.2 change how
velocities and spawns land). Do them one ticket at a time, each with a smoke run before and after.

### Phase 1 — netcode on localhost

A `Transport` interface first — send bytes to a peer, hand back what arrives, say when a peer is
gone — with one UDP implementation behind it. That is the whole cost of keeping browser players
possible (d7), and it is an afternoon now against a phase later. Then the snapshot/input protocol,
`HostSession` (owns the sim, applies remote inputs, broadcasts at 20 Hz),
`ClientSession` (sends inputs at 60 Hz, interpolates snapshots, owns no physics), entity ids, join and
leave as network events — the game already lets people join and rejoin mid-run, which maps straight
onto it, one hero per connection (d5 → A). Everything over loopback, two JVMs on one machine, plus
an automated host+2-clients test in one process that asserts the clients' positions track the host's
within tolerance. **No lobby, no NAT,
no internet in this phase.**

### Phase 2 — lobbies and the real internet

Lobby service on a VPS, doubling as the WebRTC signalling server for browser players (d7); the
shared-socket STUN trick; ICE via ice4j with a coturn relay behind it;
keepalives; IPv6-first ordering; version gating; a join-by-code screen and a lobby screen in scene2d
(the skin and `Stage` are already there); `jpackage` so the firewall prompt names the game; UPnP as a
bonus. Tested by actually playing across two households and one phone hotspot — the hotspot is the
CGNAT case and it is the one that proves the relay.

### Phase 3 — feel, and only if measured to be needed

Delta-compressed snapshots, client prediction + reconciliation for the local hero, lag compensation on
axe hits, disconnect and reconnect handling. **Recommendation: no host migration** — if the host quits,
the run ends and everyone returns to the lobby. Host migration for a physics game costs more than it
is worth here.

### A door left open

Phase 0.6 gives a headless authoritative simulation. That is the same binary a *dedicated* server
would run. So if hosting by players turns out to be a NAT nightmare for Simon's actual friends, the
fallback — we host the game too, and nobody has to punch anything — is a deployment decision, not a
rewrite. Deciding it now is not necessary; keeping phase 1 free of "the host is also a player"
assumptions is.

---

## 7. What it costs to run

- Lobby service: one small VPS, $5/mo, essentially no bandwidth.
- Relay: the same box running coturn, or a second one; bandwidth is the variable cost, roughly
  0.5 Mbit/s per relayed session in each direction.
- A domain and a TLS certificate if the lobby list is served over HTTPS.

Nothing here needs a cloud account or a managed service at this scale.

---

## 8. Risks, honestly

1. **Phase 0 is a refactor of a game that has no unit tests.** `smoke` covers bookkeeping invariants,
   not feel. Expect to re-tune `speed_*`, `jump_strenght` and the spawn rates after 0.1 and 0.2.
2. **CGNAT.** Some friends will need the relay. There is no way around paying for it, short of
   telling them to port forward.
3. **The axe.** Physics interactions between players are the least latency-tolerant thing in the game
   and the hardest to predict. If it feels bad at 60 ms, the options are prediction (expensive) or a
   design change (the axe hits on the host's word, with a local animation that fires immediately).
4. **Untested at scale.** Nobody has played this with 8 players even locally, and under d5 → A
   8 online heroes means 8 machines, which is a bigger party than this game has ever had. Monster spawn counts
   scale with player count (`getBaseNumber()` returns `heroes.size()`), so 8 players means far more
   bodies than the §4 estimate assumes. Worth a local 8-pad smoke run before sizing the protocol.
5. **Cheating.** Host-authoritative means the host is trusted. Fine for friends, not for public lobbies.
