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

## Building a standalone jar

```sh
./gradlew dist
java -jar desktop/build/libs/LaChasseGalerie-1.0.jar
```

The jar bundles every dependency and asset, so it runs from any directory.

## Project layout

```
core/      game code (shared, backend independent)
  src/jks/tools2d/parallax/   the parallax background library
desktop/   LWJGL3 launcher and all game assets (desktop/assets)
```
