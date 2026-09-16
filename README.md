# La chasse-galerie

A local co-op arcade game built with [libGDX](https://libgdx.com/), inspired by the French-Canadian legend of the
flying canoe. Up to one keyboard player and any number of gamepad players share a canoe drifting down a river at
night. Swing your axe at the monsters, grab potions, and hold on when the canoe takes to the sky.

## Requirements

- JDK 17 or newer (tested with JDK 25)

Gradle is provided by the wrapper; nothing else needs to be installed.

## Running

```sh
./gradlew :desktop:run                          # 1280x720 window
./gradlew :desktop:run --args="--fullscreen"    # fullscreen on the primary monitor
```

Launcher options (combine as needed):

| Option         | Effect                                                   |
|----------------|----------------------------------------------------------|
| `--fullscreen` | Fullscreen at the monitor's native resolution, black bars if it is not 16:9 |
| `--mute`       | No music or river ambiance                               |
| `--debug`      | Box2D collision shapes, FPS counter, reduced asset load  |
| `--menu`       | Open on the start menu instead of starting a run         |

### When an agent runs it

When a board agent runs `:desktop:run` (`ATELIER_AGENT` is set), nobody is watching, so the
window goes offscreen. It runs inside [`cage`](https://www.hjdskes.nl/projects/cage/), a
headless wlroots compositor, and gets an invisible display of its own with GPU rendering intact.
The sound goes too: it plays into `desktop/build/audio/run.wav` instead of your speakers,
through OpenAL Soft's WAV writer (LWJGL ships it). The file is overwritten on each run and
grows about 10 MB a minute. If the game is killed rather than quit, the WAV header keeps its
early length, so read past it.

When you run it, or click the board's "Play it" button, it opens on your screen.
`ATELIER_OFFSCREEN=1` sends your run offscreen anyway, and `ATELIER_NO_OFFSCREEN=1` keeps an
agent's run on screen, sound included. Without cage it opens on the screen as before.
The mechanism is in `gradle/offscreen.gradle`, copied from the Atelier skill `run-offscreen`. Anything
started outside Gradle, such as the dist jar, needs the wrapper: `tools/offscreen.sh <command>`.

## Controls

Press any key or gamepad button to join. Press again after dying to jump back in.

| Action          | Keyboard          | Gamepad    |
|-----------------|-------------------|------------|
| Move            | ← →               | Left stick |
| Jump (double)   | Space or ↑        | A          |
| Swing axe       | D (left), Q (right) | B (left), X (right) |
| Lower river volume | E              |            |
| Quit            | Esc               |            |

Each hero has four hearts. Hitting a monster with your axe scores a point; a potion heals you, or scores two points
at full health. Falling into the river or losing every heart costs a death and halves your score.

## Smoke run

```sh
./gradlew smoke                          # a minute of game time, headless, in a few seconds
./gradlew smoke -Pseed=42 -Pseconds=120  # another run, or a longer one
```

There are no unit tests. `smoke` runs the real game loop with no window and no sound, and a stubbed GL.
A keyboard player and two fake gamepads join, move, jump and swing at random, and are killed on purpose.
It fails as soon as the game's bookkeeping is off: a monster chasing a removed hero, a Box2D body or joint
the game has lost track of, a body queued to be destroyed twice. Those are the bugs that otherwise crash the
JVM with only an `hs_err_pid*.log`, which lands in `smoke/build/smoke/`. The seed replays the same run.
It cannot see rendering bugs. `./gradlew build` compiles it but does not run it.

## Net gate

```sh
./gradlew nettest     # the seam, codec, mirror and session rules, then netsession and netprocs (~15 s)
./gradlew netsession  # the game headless behind a HostSession, three ClientSessions in the same JVM
./gradlew netprocs    # a host JVM and two client JVMs, over UDP on 127.0.0.1, in real time
./gradlew netmirror   # a headless host's snapshots applied every tick to clients with no world
```

`core/src/jks/net` is the seam the online plan is built on: the game sends bytes through a
`Net_Transport` and never touches a socket itself, because a desktop player is a UDP endpoint and a
browser player is a WebRTC data channel. `nettest` first holds it to its contract in a couple of
seconds with no window and no game — round trips, a host learning a peer it has never seen, the 1200
byte payload cap, seeded packet loss, latency, reordering, duplicates, and a peer that goes quiet being
reported lost once and forgotten — along with the session rules (`HostSession`, `ClientSession`) on a
toy world. Then it plays them for real: `netsession` runs the game headless behind a host that has no
hero of its own and fails when a client draws an entity away from where the host had it, and
`netprocs` does it across three processes over UDP. Nothing in the windowed game calls it yet (that
is phase 1.5); see `docs/online-multiplayer.md`.
`netmirror` plays 8 headless players and holds `core/src/jks/online` to the world: every entity the
host's snapshot names must be where its body is on a client that owns no physics.

## Building a standalone jar

```sh
./gradlew dist
java -jar desktop/build/libs/LaChasseGalerie-1.0.jar
```

The jar bundles every dependency and asset, so it runs from any directory.

## A native launcher

```sh
./gradlew jpackage                  # desktop/build/jpackage/LaChasseGalerie/bin/LaChasseGalerie
./gradlew jpackage -Ptype=deb       # or rpm, msi, dmg — an installer instead of a folder
./gradlew jpackage -Pruntime=/tmp/runtime -Pvendor="..."
```

Wraps the `dist` jar in a launcher that carries the game's name and its own Java runtime, so a
player needs no JDK — and so the Windows firewall prompt a host sees says `LaChasseGalerie`
instead of `java.exe`, which is the prompt people click No on
(`docs/online-multiplayer.md` §3). `jpackage` only builds for the machine it runs on: run it on
Windows for an `.exe`, on macOS for a `.app`.

The image carries the JDK Gradle is running on, whole, which is about 250 MB. `jpackage` would
normally `jlink` a small runtime itself, but it cannot on Fedora — the distro rewrites
`conf/security/java.security` after the build and `jlink` then refuses with *"has been
modified"*. Build one elsewhere and pass `-Pruntime=<dir>` for a smaller image.

The icon is `desktop/packaging/icon.png` (512×512) and `icon.ico` (Windows, 16 to 256 px): the
double axe on the night sky, made from the game's own art — `tools/double_axe.png` over a night
sky — which is what Simon picked on d11 because it is the one that still reads at 32 px. The same
picture is the window and taskbar icon, from `desktop/assets/ui/icon_*.png`. **macOS needs an
`icon.icns`** and there is none; nothing here can make a real one. On a Mac:

```sh
mkdir icon.iconset && for s in 16 32 128 256 512; do
  sips -z $s $s desktop/packaging/icon.png --out icon.iconset/icon_${s}x${s}.png; done
iconutil -c icns icon.iconset -o desktop/packaging/icon.icns
```

## Project layout

```
core/      game code (shared, backend independent)
  src/jks/parralax/           the night river scene, drawn with io.github.javakhanstudio:parallax-background
  src/jks/net/                the transport seam for online play (./gradlew nettest)
  src/jks/online/             snapshots, HostSession and ClientSession (./gradlew netmirror, netsession, netprocs)
desktop/   LWJGL3 launcher and all game assets (desktop/assets)
           packaging/ is where an icon goes for ./gradlew jpackage
headless/  the game with no window or sound: the loop a host with no screen runs (jks.headless.Headless_Runner)
smoke/     the headless gates (./gradlew smoke, nettest, netsession, netprocs, netmirror, netcensus); smoke drives headless/
docs/      design notes: online-multiplayer.md (the plan for going online),
           browser-target.md (whether this can run in a browser, and what it would cost)
tools/     browser-spike/ compiles the game to JavaScript and serves it (not part of the build)
```
