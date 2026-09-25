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

---

# A tab on another network (r83, phase 2.6)

What only another network can prove: a person opens the page on their phone or at their home and
plays in a desktop host's game at yours, and a phone on mobile data (CGNAT) comes through coturn.

The page is served from VPS_1: **http://141.94.115.201:8080/** (nginx, `deploy/web/deploy.sh`).
Plain http by ip, no domain (Simon, r83 1:A): the browser says "Not secure", which is expected. The
tab's lobby is VPS_1's WebSocket front (`141.94.115.201:7771`), its relay VPS_1's coturn.

What this machine already proved, through VPS_1 (2026-09-25, r83):

    ONLINE=1 tools/browser-gate/tabjoin.sh --no-build           # the served page, VPS_1's lobby
    ONLINE=1 RELAY=1 tools/browser-gate/tabjoin.sh --no-build   # ... and the tab relay-only: coturn

Both: the tab let in, player 2 walking right on the host, one JOINING in VPS_1's log.

## Machines

- **A** — the host, at home, the desktop game from this checkout (`git pull`, then
  `./gradlew :desktop:run --args="--menu"`, or the jar from `./gradlew dist`). The host needs the
  WebRTC natives this machine's build ships: a host row that says *cannot take tabs* is a dist
  built on another platform.
- **T1** — a phone or a laptop on **another household's** Wi-Fi, with Chrome, Firefox or Safari.
- **T2** — on **mobile data** (the CGNAT case): a laptop tethered to a phone's mobile data, or the
  phone itself (Wi-Fi off) with a Bluetooth pad or keyboard.

## Steps

1. A: Menu > Online play > Host a game. Note the code.
2. T1: open `http://141.94.115.201:8080/`, press a key or tap, choose **Online play**, type the code
   (or pick the game under Open games), join. Screenshot T1's lobby screen, then A's lobby screen.
   Expected on A: a row `browser : WebRTC data channel open` within a few seconds.
3. A: Start. T1: any key for a hero, walk. Screenshot T1 and A mid-run.
   Expected: T1 in the run, its hero moving on both screens; note any stutter.
4. Repeat 2-3 with **T2** (Wi-Fi off). Expected: the same row, it may take ~5 s; then play.
   If T2 cannot connect, retry once with `http://141.94.115.201:8080/?relay` (relay-only):
   a pass there and a fail without says the tab gathered badly, not that coturn is down.
5. Optional: T1 and T2 in the same game at once.

**A phone needs a pad or a keyboard.** The game reads no touch at all (no touchDown anywhere in
core/), so a bare phone cannot leave the menu. A Bluetooth pad (the page reads the Gamepad API)
picks the game under Open games; typing a code needs keys. That is why T2 is simplest as a laptop on
the phone's hotspot: the hotspot is the CGNAT, the laptop the keyboard.

## The record

Attach to r83 the screenshots of steps 2-4, each captioned with the machine and its network. Any
row other than *data channel open*, or a tab stuck on *joining*, is a defect: file it as a #network
task with the screenshot and the code, and the minute it happened (VPS_1's lobby log is kept:
`. deploy/vps.sh; vps "sudo docker logs --since 1h lachassegalerie-lobby"`).
