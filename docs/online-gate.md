# The real-internet gate (r74, phase 2.2)

What only three real networks can prove: ICE between two households, the relay for a phone
hotspot (CGNAT), and a network switch showing RECONNECTING instead of a stale "direct".
Everything one machine can prove is already green: `./gradlew netnat` and `./gradlew netonline`
(host and joiner through VPS_1's lobby and relay, both rows RELAYED).

The lobby service is on VPS_1 at `141.94.115.201:7770`, the relay at `141.94.115.201:3478`.
The game's default is VPS_1's since r78; passing `--lobby 141.94.115.201:7770` as below is harmless and
makes a machine running an older build use it too.

## Machines

- **A** — household 1 (this desktop, for example).
- **B** — household 2, another home's Wi-Fi.
- **C** — a laptop tethered to a phone on mobile data (the hotspot, the CGNAT case).

Each needs a JDK 17+ and either this checkout (`git pull`) or the jar from `./gradlew dist`
(`desktop/build/libs/LaChasseGalerie-1.0.jar`). `netnat` needs the checkout.

## Steps

1. On every machine, on the network it is testing, save the probe:

       ./gradlew -q netnat > netnat-<A|B|C>.txt

   Expected: A and B say `EASY` (or `OPEN`); C says `HARD` and an advice line naming a fix
   (for example "on mobile data, join over Wi-Fi").

2. Start the game on every machine:

       ./gradlew :desktop:run --args="--menu --lobby 141.94.115.201:7770"
       # or: java -jar LaChasseGalerie-1.0.jar --menu --lobby 141.94.115.201:7770

   Menu > Online play.

3. **A hosts, B joins** (by code or from the list). Screenshot both lobby screens.
   Expected: both rows `direct`.

4. **A hosts, C joins.** Screenshot both. Expected: `direct` or `relayed` — never
   `cannot connect`. A relayed row appears about 2 s after the join.

5. **The switch.** With C joined and its row usable, move C from mobile data to a Wi-Fi (or
   turn the hotspot off and on). Screenshot A's row within a few seconds.
   Expected: `reconnecting` (a direct route goes RECONNECTING after 3.5 s of silence), never a
   `direct` that no longer carries anything. Note what the row becomes afterwards.

6. Optional: press Start on step 3 or 4 and play a minute; note any stutter.

## The record

Attach to r74: the three `netnat-*.txt`, and the screenshots of steps 3-5 with the machine
named in each caption. Anything that is not the expected verdict is a defect in
`jks.lobby.Lobby_Ice` or the relay path: file it as a #network task with the screenshot.
