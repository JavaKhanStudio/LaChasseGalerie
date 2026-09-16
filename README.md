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
| `--fullscreen` | Fullscreen at the monitor's native resolution            |
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
./gradlew nettest    # the transport seam, over real UDP on loopback and an in-memory wire
```

`core/src/jks/net` is the seam the online plan is built on: the game sends bytes through a
`Net_Transport` and never touches a socket itself, because a desktop player is a UDP endpoint and a
browser player is a WebRTC data channel. `nettest` holds it to its contract in a couple of seconds
with no window and no game — round trips, a host learning a peer it has never seen, the 1200 byte
payload cap, seeded packet loss, reordering, duplicates, and a peer that goes quiet being reported
lost once and forgotten. Nothing in the game calls it yet; see `docs/online-multiplayer.md`.

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

Drop an icon at `desktop/packaging/icon.png` (`icon.ico` on Windows, `icon.icns` on macOS) and it
is picked up. There is none yet: the game has no icon art.

## Project layout

```
core/      game code (shared, backend independent)
  src/jks/parralax/           the night river scene, drawn with io.github.javakhanstudio:parallax-background
  src/jks/net/                the transport seam for online play (./gradlew nettest)
desktop/   LWJGL3 launcher and all game assets (desktop/assets)
           packaging/ is where an icon goes for ./gradlew jpackage
smoke/     the headless gates (./gradlew smoke, ./gradlew nettest)
docs/      design notes: online-multiplayer.md (the plan for going online),
           browser-target.md (whether this can run in a browser, and what it would cost)
tools/     browser-spike/ compiles the game to JavaScript and serves it (not part of the build)
```
