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
- **d6 — no letter yet, but the four questions Simon asked instead are answered in §9 (r25)**, with
  numbers: punching fails for roughly one pair in ten, we can tell which pair in under a second and
  before anyone picks a hero, the lobby is where they get told, and hosting the games ourselves
  costs about 0.14% of a core per 8-player session — which makes it a bandwidth decision, not a CPU
  one. It is also the browser question now: the relay we would pay for is the same box that bridges
  a tab to a host that cannot speak WebRTC itself, so answering d6 with a relay makes d7 cheaper and
  answering it with "forward a port" leaves browser players with nothing.
- **d10 → A (r51): phase 0 finishes before any more netcode.** The order in §6 is not a suggestion
  and not a default to be optimised away. Phase 1.1 — the transport seam — is in (`99f9ac6`), and
  the next thing built is **0.2, 0.3, 0.4, 0.5 and 0.6**, not 1.2. The cost of A was named when it
  was asked: it is the longest wait before anything is visible online, and it puts the two changes
  with real risk to game feel (§8 risk 1) first. The gain is that the netcode is written once,
  against a simulation that has a fixed world, a `PlayerId`, a teardown and a seed — instead of
  against a game that still measures its world in window pixels, and then again afterwards.
  **A phase 1 ticket that starts before phase 0 is done is out of order, whatever else is free.**

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
`PhysicSpriteCanoe` — already enumerated by `Smoke_Run.seedRandoms()`. (Fixed by 0.5: one `GVars_Random`.) Under host authority only the
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
- **Relay fallback (TURN).** For the pairs that cannot punch — about one in ten, measured sources
  in §9 — traffic goes through a server we pay for. Budget: one relayed session is ~0.5 Mbit/s each way (§4), so a $5–10/mo VPS with a 1 TB
  allowance carries on the order of a hundred hours of relayed play a month. Without a relay, some
  percentage of friends simply cannot play, and the honest fallback message is "ask the host to
  forward a port".
- **UPnP / NAT-PMP** as best effort: nice when the router says yes, never relied on.
- **The Windows firewall prompt is a real UX problem, and it is a `#build` problem.** Today the game
  ships as `java -jar LaChasseGalerie-1.0.jar`, so the Defender dialog a host sees says *"java.exe
  wants to accept connections"* — and people click No. Two mitigations: bind an ephemeral port and rely
  only on mappings our own outbound packets created (outbound never prompts), and package the game
  with `jpackage` so the prompt at least carries the game's name and icon.
  **Both are done (r44).** `Transport_Udp` binds port 0 and never asks for an inbound mapping, and
  `./gradlew jpackage` builds a launcher named `LaChasseGalerie` carrying its own runtime, so the
  prompt names the game. It carries the icon too since d11 → B (r50): the double axe on the night
  sky, in `desktop/packaging/`, on the binary and on the window.
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

**Measured, not estimated (r37, `./gradlew netcensus`).** The layout `Net_Codec` actually uses
has a 24 B header, 6 B per player in the score table, 21 B per hero *with* its axe, 7 B per monster
and 6 B per potion. An 8-player headless run that encodes the real snapshot every tick (seeds 1–5
over 120 s, seed 2 over 600 s) never went past **442 B**. The worst of each count, even though no
single tick had them all at once (8 heroes, 26 monsters, 8 potions), comes to 470 B. Risk 4 was
real but small: monsters peak around 26, not far above the estimate. What does *not* stay bounded
is potions: every potion that misses the canoe falls forever and is never removed (n7), 109 of
them after ten minutes. The snapshot view has to leave out anything that has left the world, or
no layout fits — and since r38 it does (`Snapshot_View` drops fallen potions and heroes already
queued to die), so the same seed-1 run now peaks at 434 B.

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

Four phases, **in this order — d10 → A settled that the order is binding, not indicative.**
Everything in phase 0 is worth doing even if online is cancelled, and each phase is shippable and
testable on its own. `./gradlew smoke` is the safety net throughout: it already asserts the
invariants that a refactor of this shape breaks.

### Phase 0 — make the game a simulation (no networking in this phase)

| # | work | touches | how it is proven |
|---|---|---|---|
| 0.1 ✅ | **Done, `81fd446`.** Fixed-step sim clock: an accumulator in `Main_Game.render`, `simulate(1/60)` separate from `render()`, animation time fed by the sim clock, not `Gdx.graphics.getDeltaTime()` | `Main_Game`, `AVue_Model`, `Vue_Game`, `Gvars_Physic`, `SpriteModel` | smoke passes; the game plays the same at 60 fps and *keeps* playing the same when frames drop |
| 0.2 ✅ | **Done, r32.** A world of fixed size, window as viewport: `GVars_Camera.viewWidth/viewHeight` (1280×720, × `worldMutiplier`) and a `FitViewport`; the world sites read those, the HUD and the menu stay laid out on the window | `GVars_Camera` (+ a `Viewport`), the 9 sites listed in §1.5 | smoke seed 1 gives the identical report in a 1280×720 AND a 1920×1200 mock window (before: deaths 20 vs 16); a 1280×720 render is pixel-identical to before, a 2560×720 one is letterboxed instead of misplaced (renders on r32) |
| 0.3 ✅ | **Done, r33.** `PlayerId` instead of `Controller` as identity — a peer id online (d5 → A), the local device offline: `GVars_Controller.playerList` and `GVars_Game.playerRegister` are `TreeMap`s on `PlayerId`, `PhysicSpriteHeroes.player` replaces `.controller`, and a device reaches its player only through `GVars_Controller.identify/getLocalPlayer` (null still being the keyboard, there alone) | `GVars_Controller`, `GVars_Game`, `PhysicSpriteHeroes`, both `IKM_*`, `Menu_Picker`, `Smoke_Run` | smoke passes with keyboard + 2 pads and gives the identical seed-1 report; it now also fails if a hero's label is not its player's, or if rejoining mints a new player |
| 0.4 ✅ | **Done, r34.** Session teardown: `Vue_Game.dispose` (called by `GVars_Heart.changeVue`) disposes the world whole, then drops the run's lists, HUD Stage, batch and players; `GVars_Story.init` rewinds the timeline, `Index_Sprite` loads once and hands every colour back, `GVars_Parralax.setPages` rewinds the river. Atlases, skin and river stay loaded across runs | all `GVars_*`, `Vue_Game`, `GVars_Parralax` | `./gradlew smoke` now plays the seed twice in one JVM, going back to the menu between, and fails unless both runs report the same counts, player numbers and colours (`-Pruns=N`); on the real backend two runs of one seed drew pixel-identical frames at 40s |
| 0.5 ✅ | **Done, r35.** One seeded RNG stream, seed settable: `GVars_Random.random`, `GVars_Random.seed(long)`; the smoke seeds it directly instead of by reflection | the 4 `Random`s of §1.6 | `smoke -Pseed=42` twice gives identical reports; the seed-1 baseline moved to deaths=20, monstersKilled=76 because four streams became one |
| 0.6 ✅ | **Done, r36.** Extract the headless loop out of `Smoke_Run` into a runner the host can use with no window: a `headless` module, `Headless_Runner.launch/boot/step`; scripted players, checks and seeding stay in `smoke/` | `smoke/`, new `headless` module (core stays backend-free) | smoke runs through it and replays the identical seed-1 report |

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

### Where each of these lives on the board

The phases above were a table in a document for a while, which is not somewhere work can be picked
up from. They are tickets now (r15's children), and the ticket is the copy to trust if the two ever
disagree:

| phase | ticket | tag |
|---|---|---|
| 0.1 fixed-step clock | **done, `81fd446`** | — |
| 0.2 fixed world size, window as viewport | r32 | `#background` `#gameplay` |
| 0.3 `PlayerId` instead of `Controller` | r33 | `#input` |
| 0.4 session teardown | r34 | `#gameplay` |
| 0.5 one seeded RNG | r35 | `#gameplay` |
| 0.6 headless runner out of `Smoke_Run` | r36 | `#build` |
| 1.1 the `Transport` seam + its gate | **done, this commit** | `#network` |
| 1.2 the wire protocol | **done, r37** | `#network` |
| 1.3 entity ids and snapshots | **done, r38** | `#network` `#gameplay` |
| 1.4 `HostSession` / `ClientSession` on loopback | **done, r39** | `#network` |
| 1.5 a client that renders what it does not simulate | **done, r40** | `#network` `#gameplay` |
| 2.1 the lobby service | **done, r41** | `#network` |
| 2.2 ICE: IPv6, punch, relay — and say which | r42 | `#network` |
| 2.3 the lobby screen | r43 | `#hud` `#network` |
| 2.4 `jpackage`, so the firewall prompt names the game | **done, r44** | `#build` |
| 2.5 decide and deploy the relay | r45, **blocked on d6** | `#network` |
| 2.6 a browser tab as a player | r46 | `#network` |
| 3 feel, measured first | r47 | `#network` |
| 3 disconnects, and the host leaving | r48 | `#network` |

Phase 0 items are **not** `#network` on purpose: each is a bug in the existing game, owned by the
seam that owns that code, and each is worth doing even if online is cancelled.

**Since d10 → A, the board carries that order as priority**: r32, r33 and r34 are p1, r35 and r36
p2, and every phase 1 ticket is p5 with a note saying which tickets have to land before it is
picked up. The five phase 0 tickets are the whole of what is claimable on this plan right now.

**Phase 1.1 is in the tree** (`core/src/jks/net`, `./gradlew nettest`): `Net_Transport` with
`Transport_Udp` behind it and an in-memory `Net_Loopback` with seeded loss, reordering and
duplicates for the tests that 1.2 and 1.4 will need. Nothing in the game calls it yet — that is 1.4.
The contract it fixes, and that everything above it may now assume: a packet arrives whole or not at
all, a payload past 1200 bytes is refused out loud, a host learns a peer from the packet that
punched its way in and can answer that address, and a peer that goes quiet past the timeout is
reported lost exactly once and forgotten.

**Phase 1.2 is in too** (`Net_Codec`, `Net_Message`, `Net_Input`, `Net_Snapshot`, `Net_Rejected`),
a pure codec with no socket: every packet is `version u8 | type u8 | body | CRC-32`, and the
layouts are written out in `Net_Codec`'s javadoc. Input carries the newest frame and the three
before it. The host takes each tick once, by its number, which is also what stops a copy from
jumping twice. Encoding a packet past 1200 B throws and names the counts. Decoding refuses, and
never half-reads, a packet that is truncated, corrupted, of another version, or whose checksum is
right but whose content no encoder writes. nettest holds all of that, plus the measured 8-player
peak, in 10 more checks (24 in all).

**Phase 1.3 is in** (`core/src/jks/online`, `./gradlew netmirror`). Every hero, monster and potion
gets an entity id when it is made, from one per-run counter (`GVars_Game.newEntityId`): one
namespace across kinds, 16 bits, never 0, and after a wrap an id still worn by a living entity is
skipped. A hero that dies and rejoins is a new id for the same player. The canoe has no id — there
is one per run and it travels as `canoeAngle` — and an axe rides on its hero. `Snapshot_View.read`
is the host's pass: it reads the world into a `Net_Snapshot` and changes nothing. It leaves out a
hero already queued to die and a potion below the world (n7), and it always sends monsters, because
one chasing a drowning hero dips under the water line and climbs back. `Snapshot_Mirror` is the
client's pass and imports no Box2D, no `GVars_*` and no asset: it creates what is new, moves what
exists (the same object, so a sprite hung on it lives as long as the entity) and destroys what
vanished, destroys before it creates, keeps the host's drawing order, ignores a snapshot no newer
than the last, and refuses one that names an id twice. A `Listener` hears every create and destroy,
which is where phase 1.5 hangs its sprites. The gate plays 8 headless players for 90 s, pushes the ids
past 0xFFFF half way, and every tick applies the snapshot to one client as it is, to one through the
codec, and to a third that joins late. All three must agree with the world, read straight off the
bodies, entity by entity. nettest holds the mirror's rules in 5 more checks (29 in all).

**Phase 1.4 is in** (`core/src/jks/online`, `./gradlew nettest`, which now ends by running
`netsession` and `netprocs`). `HostSession` owns nothing but seats: the world sits behind
`Host_Simulation` — mint a player, spawn, press, remove, read — with `Game_Simulation` as the real
game and a toy world in nettest, and the loop that owns the clock hands `tick` the step to run. **No
hero, device or screen is the host's**: the gates run a host with none, and a host that also plays
does it beside the session on the same numbering (`GVars_Controller.newPlayer`, which `identify` now
uses too), so a keyboard never takes a remote player's number. HELLO from an unknown peer is a new
`PlayerId` and a WELCOME (again the same one, if the WELCOME was lost); JOIN from a player with no
living hero is a hero, which is how a dead player comes back; LEAVE or the transport's timeout takes the
hero out with no death counted and removes the score row; a ninth seat, or a packet of another protocol
version, gets a LEAVE naming `HostSession.NOBODY` (0xFFFF), because the codec refuses a player 0.
Input frames are stamped with the client's guess at the host tick and applied **at the tick they
claim, or the first tick after it the host still has** — without prediction that is nearly always the
next tick — once per tick number; frames that land together keep the newest held buttons and fire
every one-shot once, so a burst of late packets neither drops nor repeats a jump; a claim more than
30 ticks ahead is a broken clock and lands now. The host reads and broadcasts the world every 3rd tick.
`ClientSession` owns no world: it resends HELLO and JOIN every half second until answered, sends its
buttons every tick (which is also its keepalive), and draws the run **6 ticks (100 ms) behind its
clock**, between the two snapshots around that moment, through a `Snapshot_Mirror` that creates and
destroys entities when the drawing time reaches them. Its clock catches up to a newer snapshot at once
and steps back a tick a second while every snapshot is older than it. `Net_Loopback` now has seeded
latency and jitter off its virtual clock. **Measured**: in `netsession` (60 s, 40±20 ms, 3% loss)
the picture ran 8 ticks behind the host, 11 at worst, with a mean position error of 1.9 cm (heroes
1.3 cm, axes 3.8 cm); each position is held to its entity's fastest step on the host times the gap
between the two snapshots it was drawn between, plus quantizing and 1 cm, which is what a straight
line can honestly promise. Across processes, both clients got 20.0 snapshots/s and their heroes
walked the way they pressed 269–272 ticks out of 272. nettest holds the session rules in 11 more
checks (40 in all), and mutating the interpolation, the tick a picture is drawn at, the dedupe of
copies, the wait for a future frame or the coalescing of presses each fails it.

**Phase 1.5 is in** (`core/src/jks/draw`, `Vue_Client`, `--host` / `--join`). The line between
simulation and rendering is now a class boundary: `Draw_Hero`, `Draw_Monster`, `Draw_Potion` and
`Draw_Canoe` hold everything that is drawn — animation, the blink, the axe, the hearts, the tilt — from
drawing fields, and read no body. `PhysicSpriteHeroes`, `PhysicSpriteEnnemy` and `PhysicSpriteCanoe`
extend them, and their `act()` copies the bodies into those fields; `Weapon_AXE` is a body and nothing
else. A deterministic host run (seed 1, one step per frame, scripted keys) drew pixel-identical frames
at seven moments before and after the split. `Vue_Client` is a view that initialises no
`Gvars_Physic`, `GVars_Game` or `GVars_Controller`: it hangs a `Draw_*` on every entity the mirror
creates, and moves every clock — animation, blink, river, sky, music cue, the water fading — by how far
the snapshot's story time moved (`GVars_Story.followHost`), never by its own. The river's horizontal
speed is not in the snapshot: `GVars_Story.riverSpeedAt` rebuilds it from the story time. The star
sits at a third of `skyScroll`. The host window wraps each step of a `Vue_Game` in `HostSession.tick`
(`Main_Game`), and its own keyboard still joins beside the peers. **Played**: a host JVM and a client
JVM on 127.0.0.1, both windowed, one hero each driven by scripted keys for 55 s through the take-off.
Captured at the same host story time (6, 16, 26, 39 and 50 s), the client's frames show the same
heroes, axes, monsters, hearts, scores, canoe tilt, river and sky as the host's (renders on r40).
The picture held 6 ticks behind the client's clock the whole run. Over loopback the round trip is
about zero, so that is the 100 ms buffer alone; nobody has felt a real RTT yet (phase 3, r47).

**Phase 2.1 is in** (`core/src/jks/net/Lobby_*`, `core/src/jks/lobby`, the `lobby` module,
`./gradlew netlobby`). The lobby protocol is its own pure codec beside the game's: `'L' | version |
type | body | CRC-32`, the `'L'` being what tells a lobby packet from a game packet on the one socket
they share (the game codec now refuses one as `NOT_OURS` instead of answering it with LEAVE VERSION).
`Lobby_Service` holds `{code, host candidates, players, seats, game version}` and nothing else: HOST
opens a lobby or refreshes it, BROWSE lists the ones of the asker's game version (newest first, 100 to
a packet), JOIN answers the joiner with the host's addresses and **mirrors** the joiner's to the host,
PING answers with the address the packet came from, CLOSE ends a lobby. A code is six letters from an
alphabet with no 0/O or 1/I. The version gate refuses a join across game versions and names the
lobby's version, and a game speaking another *lobby* version gets OUTDATED, the one layout promised
never to change. A lobby dies 20 s after its host's last HOST, or at once on CLOSE, and a host that
comes back asking for its old code gets it if it is free, so a lost refresh or a service restart does
not strand the friends who were told it. The service does not act on the transport's own timeout,
which differs between transports. Every address gets at most 20 answers a second, because answers are
bigger than questions and the socket takes forged sources. `Lobby_Client` is the player's side, and
**it is built on the game's transport**: it hands `HostSession` / `ClientSession` a `game()` view of
the same socket with the service's packets taken out, and whoever pumps, lobby packets from the service
go to it and everything else reaches the session in arrival order. It refreshes HOST, or PINGs, every
**3 s**, not 5: a transport forgets a peer after 10 s of silence, and at 5 s one lost answer was enough
to call the service lost. JOIN is repeated every 500 ms until the caller says the game connection
answered, because each copy also re-tells the host where to punch. The `lobby` module takes core's
classes without libGDX, and `netlobby` runs the service on exactly that classpath. **Measured**: in
`netlobby` a service JVM, a headless host JVM and two client JVMs that knew only the service's
address and the code played as `netprocs` does (20.0 snapshots/s each, heroes walking the way they
pressed 269-272 ticks out of 272). The host's session saw each player at exactly the address the
service had mirrored to it, and the service logged both joins and the host closing. nettest holds the
lobby's rules in 10 more checks (50 in all), and ten mutations of the service, the client and the
codec each fail at least one of them. **Not in 2.1**: gathering local and IPv6 candidates and the punch
itself (r42: `Lobby_Client.candidates` and `takeJoiners()` are where they plug in), the screen (r43),
and signalling for a tab (r46), which needs a message bigger than a UDP packet and a front other
than UDP beside the same lobbies. Nobody has run the service on a VPS yet.

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

---

## 9. d6, answered with numbers (r25)

Simon did not pick a letter on d6. He asked four questions, and they are the right ones. Everything
here was either measured on this machine on 2026-09-16 or taken from a named source.

### How often would punching actually fail?

The best public figure comes from Tailscale's *How NAT traversal works*, written by people running a
large fleet of exactly this: with the standard techniques — STUN, both sides punching at once, a
birthday-paradox port search against a symmetric peer — they put a **direct connection at "over 90%
of the time"**, with relays covering the rest. So plan on roughly **1 pair in 10** needing help, not
1 in 100 and not 1 in 3.

That 10% splits into two very different cases:

- **One side symmetric ("easy vs hard").** Recoverable by probing: their table puts 1024 random
  probes at ~98% success against a 256-port spread, and at a modest 100 probes/second *half the
  pairs are through in under two seconds*. Worth implementing before paying anyone.
- **Both sides symmetric, or CGNAT on both ends.** Effectively hopeless: ~170,000 probes for 99.9%,
  which is nine minutes of waiting at even odds. This is the case a relay exists for.

Two things move the number in our favour. **IPv6**: the same article puts world adoption around 33%,
and where both peers have it there is no NAT to punch at all — this machine has working IPv6 to the
internet (measured: `curl -6 https://ipv6.google.com` returned 200 in 1.4 s). And **the friends who
will actually play this**: on mobile tethering, CGNAT is the norm; on home fibre it is not. We cannot
know Simon's group from here — but we do not have to guess, because of the next answer.

### How early can we detect it?

**Before anyone picks a hero, in under a second.** Two stages, and neither needs the game to start:

1. **The NAT behaviour probe** — RFC 5780, *NAT Behavior Discovery Using STUN*. Send a STUN binding
   request to two or more servers *from the same local UDP socket* and compare the port each one
   reports. Same port = endpoint-independent mapping, punchable. Different ports = symmetric, and we
   know it before the player has even joined a lobby. Run here as a ~40-line probe, from this
   machine, against three public STUN servers:

   ```
   stun.l.google.com:19302      sees port 7910   (44 ms round trip)
   stun.cloudflare.com:3478     sees port 7910   (23 ms round trip)
   stun.nextcloud.com:3478      sees port 7910   (506 ms round trip)
   -> mapped port stable: mapping is ENDPOINT-INDEPENDENT (punchable)
   ```

   Two servers is enough, and the two fast ones answered in **67 ms together**. A CGNAT'd player is
   visible the same way: a private local address whose mapped address differs, and often an address
   from `100.64.0.0/10` (RFC 6598, the shared address space carriers use).
2. **The real answer: an ICE connectivity check between the two peers** (RFC 8445), run **when a
   player joins the lobby**, not when the host presses start. It is the only thing that can say "this
   pair can talk", because it tries. Roughly a second once both are in the lobby, and ice4j already
   implements all of it.

So the sequence is: probe on launch, check on join, and the lobby knows the truth while people are
still choosing colours.

### Can we warn the users before the game starts?

Yes, and it should be the visible part of this whole effort. Each row in the lobby carries its own
state — **direct**, **relayed**, or **cannot connect** — settled by the check above at the moment
that player joins. The host sees it before starting, and the message can be specific enough to act
on: *"you are on mobile data, which cannot be reached directly — join over Wi-Fi if you can"*, or
*"you and the host both sit behind carrier NAT; you will be relayed and may see a little more lag"*.

Two honest limits. A check that passed can still break later — a NAT mapping expires in about 30
seconds, which is what the keepalives are for, and switching from Wi-Fi to mobile mid-game changes
everything; ICE restarts handle that, and the lobby should say "reconnecting" rather than pretending.
And a warning is only useful if it names the fix: "NAT type: strict" tells a player nothing.

### What would hosting the games ourselves cost in CPU?

**Almost nothing. It is a bandwidth question, not a CPU one.** Measured on this machine — a 13th Gen
Intel Core i7-13650HX, one thread, the real game loop headless with no rendering, 120 seconds of game
time per run, players rejoining every second so the population stays up:

| players in the session | CPU per second of game time | 8-player sessions one core could carry in real time |
|---|---|---|
| 1 | 0.68 ms | — |
| 4 | 1.02 ms | — |
| 8 | **1.43 ms** | **~700** |

A session costs about **0.14% of one core**. For comparison the same 8-player run *with* draw calls
(which a server does not make) costs 3.69 ms/s, and the simulation's own heap is about 2 MB.

Three caveats, and they matter more than the number:

- **Today the game cannot host two sessions in one process.** `GVars_Game`, `Gvars_Physic.world`,
  `GVars_Story` and friends are static singletons with no teardown, so a server would need one JVM
  per session until phase 0.4 lands — and a JVM running the game currently sits at **269 MB RSS**,
  because `init()` still loads every texture even headless. A server build would not load them; the
  simulation itself is the 2 MB above.
- **Bandwidth is the real bill.** A hosted 8-player session sends 8 × 9 kB/s ≈ **0.6 Mbit/s**, or
  ~225 MB per session-hour. A 1 TB/month VPS allowance is therefore about **4,400 session-hours a
  month** — hundreds of CPU-cores' worth of headroom that we will never reach, because the network
  runs out first.
- **Relaying is cheaper than hosting, as long as it stays rare.** A TURN relay pays twice for every
  byte (in and out), so relaying an entire 8-player session costs roughly double what hosting it
  does — but we would only relay the ~10% of *players* who need it, at about 18 kB/s each, not whole
  sessions. Hosting everything means paying for 100% of the traffic to avoid a problem that affects
  one connection in ten.

### What this says, if a letter is still wanted

**A — pay for the relay — with the punching done properly first**, is what the numbers support: it
costs a $5–10 VPS plus traffic for roughly one pair in ten, it is the same box that d7 needs as a
WebRTC bridge for browser players, and it keeps "the host carries the traffic" true for the other
nine. **C — host the games ourselves — is not expensive in CPU** and is worth keeping reachable
(phase 0.6 gives us the headless simulation for free), but as a default it means paying for every
byte of every game to fix one connection in ten. **B — tell them to forward a port — is the only one
the numbers argue against**: it fails for exactly the players who cannot fix it, the ones on carrier
NAT, and they are the ones who will be told it is their fault.
