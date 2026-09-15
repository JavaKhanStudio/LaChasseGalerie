# Can this game run in a browser? Analysis

**Question asked (r20):** libGDX used to run in a browser like Chrome. Does that still work, and
what would it take here?

**Short answer: yes, the browser backends are alive and current, and this game's code is unusually
well suited to one.** libGDX's own GWT backend ships at `1.14.2` — the exact version the game uses —
published 2026-06-05, and `core/` contains almost nothing that a browser compiler rejects. Three
things stand between that and a playable page, and only one of them is about libGDX:

1. **Our own parallax library blocks the GWT route today** (no GWT module descriptor, and Kryo and
   Jackson annotations on the classes the game actually uses). It is Simon's own artifact, so it is
   fixable — §4.
2. **32 MB of assets** must download before the first frame — §5.
3. **A browser cannot open a UDP socket, at all, ever.** That is not a libGDX limitation, it is the
   sandbox. It collides head-on with the online plan in `docs/online-multiplayer.md`, and *how* it
   collides depends entirely on what the browser build is for — §6. This is the question to settle
   before spending a day on the rest.

Measured against the code at `58d8c79`; every version below was read from Maven Central on
2026-09-16.

---

## 1. The two routes, and whether they are alive

| | GWT (libGDX's own) | TeaVM (xpenatan's) |
|---|---|---|
| backend artifact | `com.badlogicgames.gdx:gdx-backend-gwt` **1.14.2**, 2026-06-05 | `com.github.xpenatan.gdx-teavm:backend-teavm` **1.4.0**, 2025-11-04 |
| targets libGDX | 1.14.2 — the version this game is on | 1.14.0 |
| compiler | `org.gwtproject:gwt-user` 2.11.0 (the backend's dependency); GWT itself is at **2.13.1**, 2026-06-19 | TeaVM 0.13.0 |
| Box2D | `gdx-box2d-gwt` **1.14.2**, 2026-06-05, first-party | `gdx-box2d-teavm` **1.0.0-b6**, last published **2023-07-23** |
| gamepads | `gdx-controllers-gwt` **2.2.4** — the version this game is on | `gdx-controllers-teavm` 1.5.6, 2026-05-11 |
| Gradle plugin | `org.docstr.gwt` **2.2.9**, 2026-01-31 (the maintained fork; the old `org.wisepersist` one stopped at 1.1.19 in 2022) | `com.github.xpenatan.gdx-teavm` **1.6.2**, 2026-08-24 |
| what it eats | **Java source** — every dependency needs a sources jar *and* a `.gwt.xml` | **bytecode** — ordinary jars, no sources, no module descriptors |

Both are maintained. The split matters for this game specifically:

- **GWT is first-party and its Box2D is current.** This game is Box2D from end to end — the canoe, a
  body and a jointed axe per hero, every monster — so a stale Box2D is not a detail.
- **TeaVM needs no sources and no `.gwt.xml`**, which makes the parallax problem of §4 mostly vanish,
  but its Box2D binding has not been republished since 2023. Whether Box2D still works on a current
  TeaVM backend is the one thing I could not settle from Maven metadata alone, and it decides the
  route. It is a half-day spike, not a discussion.

---

## 2. What already works, in this game

`core/` was searched for what normally kills a browser build. It is remarkably clean:

- **No `java.io`, no `java.nio`, no threads, no reflection, no `ClassLoader`, no `System.exit`.** The
  only JDK call outside libGDX is `System.currentTimeMillis()` in `GlobalTimmer`, which both GWT and
  TeaVM emulate.
- **Assets are loaded by explicit path** (`new Texture("tools/heart.png")`, the atlases,
  `skin/freezing-ui.json`) — nothing lists a directory, which is the thing a browser cannot do.
- **The scene2d skin is already covered.** `GVars_Interface` builds a `Skin` from JSON, which uses
  reflection to instantiate style classes; the GWT backend's `GwtReflect.gwt.xml` already extends
  `gdx.reflect.include` with `com.badlogic.gdx.scenes.scene2d` and `BitmapFont`, and every class
  named in `freezing-ui.json` is under those. No extra reflection configuration expected.
- **Audio is two MP3s** through `Gdx.audio.newMusic` — the format with the fewest browser caveats.
- **Input**: the keyboard path works as-is. Gamepads go through the browser Gamepad API via
  `gdx-controllers-gwt`; note it cannot see a pad until the player presses a button on it, which
  happens to be exactly how this game joins players already.

---

## 3. What does not survive the crossing

None of this is hard; it is the second launcher nobody counts.

- `desktop/src/jks/launcher/`: `--fullscreen`, `--mute` and `--debug` are command-line arguments.
  A page has no command line — they become query parameters (`?mute&debug`), and a browser build
  needs its own small launcher class that sets `GVars_Audio.muted` and `GVars_Debug` the same way.
- `Utils_Launcher.preferX11OnLinux()`, the LWJGL 3.3.6 pin, `--enable-native-access`,
  `-XstartOnFirstThread`: all LWJGL3 desktop concerns with no meaning in a browser. They stay where
  they are; the html module simply does not use them.
- `gradle/offscreen.gradle` wraps `:desktop:run` into cage for board agents. A browser build's
  "run" is a dev server plus a browser, so an agent checking a browser change has **no offscreen
  story at all** — a new problem for whoever owns that, not a solved one.
- `./gradlew smoke` runs the real loop on the JVM with a stubbed GL. It tests `core/`, which is the
  shared code, so it keeps its value — but it can never see a GWT or TeaVM compilation failure, and
  there is no headless gate for the JS build short of driving a real browser.
- `Gdx.app.exit()` on ESC (`IKM_Game_Keyboard`) does nothing useful in a tab.

---

## 4. The blocker we own: `parallax-background`

The GWT compiler consumes Java **source**, so every dependency must publish a sources jar and a
`.gwt.xml` module descriptor. Checked on Central for `io.github.javakhanstudio:parallax-background:2.1.0`:

- **Sources jar: published.** ✅
- **`.gwt.xml`: absent** from both the jar and the sources jar. ❌ Without it GWT will not translate
  the library at all.
- **Its POM depends on `com.esotericsoftware:kryo:5.6.2` and `jackson-annotations` at compile
  scope**, and — the part that matters — the classes the *game* uses carry those annotations:
  `WholePage_Model`, `Parallax_Model`, `Page_Model` and `ParallaxLayer` import
  `com.esotericsoftware.kryo.DefaultSerializer` and `com.fasterxml.jackson.annotation.*`. Kryo is
  reflection and `Unsafe`; it has no browser story. A GWT translatable source path over this library
  therefore needs those annotation types out of the way, not just the serializer classes excluded.

This is good news disguised as bad: the artifact is published by **Simon's own Parallax board**, so
the fix is ours. Two moves, in the library not here: ship a `.gwt.xml` that excludes the
`*_Serializer` / `GVars_Serialization` / `Utils_Page` sources from the translatable path, and take
the Kryo and Jackson annotations off the model classes (the game's own vendored copy did exactly
that in `ac3da1d` before `ad38949` deleted it — so it is known to work without them).

**On TeaVM this problem is much smaller**: bytecode in, whole-program pruning, so unreachable Kryo
serializers are dropped and annotations are not type-checked. The game never calls
`Utils_Page.loadPage`, the only reachable Kryo entry point — it builds its pages in Java in
`ColdNightModel`.

---

## 5. What a player would download

`desktop/assets` is **32 MB** on disk today:

| | |
|---|---|
| `perso/` | 15 MB |
| `musics/pagayez.mp3` | 8.7 MB |
| `ambiance/courant1.mp3` | 3.7 MB |
| `tools/`, `stars/`, `skin/`, `ennemy/`, `parralax/`, `ui/` | ~4 MB together |

libGDX's GWT backend preloads assets before `create()` runs, behind a progress bar. 32 MB is a
30-second wait on a 10 Mbit/s line and an outright bounce on a phone — and the two MP3s are already
compressed, so the win is in `perso/` (15 MB of sprite sheets) and in loading the music *after* the
game starts rather than before it. Plan on an asset pass as part of the work, not after it.

---

## 6. The collision with the online plan — read this before deciding anything

`docs/online-multiplayer.md` plans host-authoritative play over raw UDP, and records that libGDX's
own `Net.Protocol` has a single constant, `TCP`. The sharper and more useful statement is this:

> **A browser tab cannot open a UDP socket or a raw TCP socket. Ever. It is the sandbox, not libGDX.**

What a page *can* do is a **WebSocket** (TCP, and TLS is effectively required from an `https://`
page) and a **WebRTC DataChannel** (SCTP over DTLS over UDP, and it can be told to be unreliable and
unordered — the closest thing to the transport the plan wants).

So the plan does not simply "not work in a browser". It changes shape, and the cost depends on what
the browser build is for:

- **A browser build as a demo — single player, or local co-op on one keyboard and pads.** No conflict
  at all. Everything in §1–§5 applies and nothing else.
- **A browser build as a way friends join an online game.** Then the desktop host must speak WebRTC:
  a Java host needs a real WebRTC stack (`dev.onvoid.webrtc:webrtc-java` 0.18.0, published
  2026-09-15, is maintained — but it is JNI with native libraries per platform, exactly the kind of
  dependency `#build` already pins for LWJGL), or every browser player is bridged through a server
  **we** run and pay for, which quietly undoes the whole point of "the host carries the traffic".
  The lobby service we already plan to host is the signalling server either way, so that part is free.
- **A browser build as the only client.** Then there is no desktop host to punch to, the answer is a
  server-side simulation, and the d6 question ("what happens when players cannot punch through")
  answers itself in the most expensive direction.

The `#network` pack currently says a browser build and the online seam are mutually exclusive and
that this is permanent. That is right for the transport as planned and wrong as a general statement:
WebRTC DataChannels exist precisely for this, and the real answer is that a browser client moves cost
from the player's machine to ours. It should be corrected to say that.

---

## 7. What it costs to keep a second backend alive

- A new `html` Gradle module: the backend dependency, the `.gwt.xml` for the game, an entry point
  class, the asset path configuration, the `org.docstr.gwt` plugin (note this project runs Gradle 9
  with `org.gradle.configuration-cache=true` — plugin compatibility with both is the first thing to
  find out, and is a known rough edge for GWT plugins).
- Sources jars for every dependency, forever: bumping libGDX means bumping the GWT backend *and*
  checking that each dependency still publishes sources. `parallax-background` becomes a two-repo
  release whenever it changes.
- A second launcher, a second set of "how do I run it" instructions in the README, and a dev-server
  workflow that `gradle/offscreen.gradle` knows nothing about.
- Compile times: a GWT production compile of a game this size is minutes, not seconds.
- No automated gate. `./gradlew build` will not tell anyone the browser build broke.

Two backends is roughly a permanent 20–30% tax on every build-touching change, which is why the
question in §6 decides whether it is worth paying.

---

## 8. Recommendation

1. **Settle §6 first** — what is the browser build *for*. It changes which of these paragraphs
   matter. It is a question for Simon, not an engineering finding (raised as a doubt on this task).
2. **Then spend one day on a spike, not a port.** A throwaway `html` module that compiles `core/`
   against `gdx-backend-gwt:1.14.2` and puts the river on a page with no sound and no gamepads,
   with the parallax library patched locally to see whether §4 is the only thing in the way. That
   turns every "should" in this document into a yes or a no, including the Gradle 9 question and
   the real compile time.
3. **Fix `parallax-background` on its own board** if the spike confirms §4 — a `.gwt.xml` and the
   removal of the Kryo and Jackson annotations, published as 2.2.0.
4. **Prefer GWT over TeaVM** for this game unless the spike says otherwise, because the Box2D
   binding is first-party and current there and three years stale on the other side.

## 9. What I could not verify from here

- Whether `gdx-box2d-teavm:1.0.0-b6` (2023) still works with `backend-teavm:1.4.0` (2025). Only a
  spike answers it.
- Whether `org.docstr.gwt:2.2.9` is happy with Gradle 9.7 and the configuration cache.
- Whether GWT actually refuses the parallax sources over the annotations, or merely over the missing
  `.gwt.xml`. The mechanism is certain; the exact error is not, and it does not change the fix.
