# The real-internet gate (r74, phase 2.2)

What only three real networks can prove: ICE between two households, the relay for a phone
hotspot (CGNAT), and a network switch showing RECONNECTING instead of a stale "direct".
The machines need nothing but the download below: no checkout, no JDK, no terminal.
Everything one machine can prove is already green: `./gradlew netnat` and `./gradlew netonline`
(host and joiner through VPS_1's lobby and relay, both rows RELAYED).

The lobby service is on VPS_1 at `141.94.115.201:7770`, the relay at `141.94.115.201:3478`.
The game's default is VPS_1's since r78: nothing needs passing.

## What each machine needs: a browser, then a double-click (r89)

**http://141.94.115.201:8080/play/** has the game ready to put on a computer: a zip per system
(Windows, macOS Apple silicon and Intel, Linux), each with its own Java, so nothing is installed,
no checkout, no terminal. Unzip, double-click **PLAY**, Menu > Online play. (`tools/gate-kit/build.sh`
makes the zips, `deploy/web/kit.sh` puts them there.)

That build starts with `--report`: while Online play is open, it sends VPS_1's lobby log one line
for its NAT verdict (what `./gradlew netnat` printed: EASY / HARD / OPEN, carrier NAT, IPv6, the
advice) and one for every lobby row that changes route, named after the computer. **So nobody
collects netnat files or screenshots**: an agent reads the record on VPS_1.

**The phone is only the hotspot.** Machine C is a laptop tethered to a phone on mobile data. Nothing
runs on the phone: turn its hotspot on, join it from the laptop, that is the CGNAT case.

## Machines

- **A** — household 1 (this desktop, for example).
- **B** — household 2, another home's Wi-Fi.
- **C** — a laptop on a phone's hotspot (mobile data).

## Steps (Simon's part)

1. On A, B and C: download the zip from http://141.94.115.201:8080/play/, unzip, double-click PLAY,
   Menu > Online play. (A can also run this checkout: `./gradlew :desktop:run --args="--menu --report"`.)
2. **A hosts, B joins** (by code or from the list). Leave them a few seconds.
3. **C joins A's game too.** A relayed row appears about 2 s after the join.
4. **The switch.** With C joined, move C from the hotspot to a Wi-Fi (or turn the hotspot off
   and on). Wait ten seconds.
5. Optional: press Start and play a minute; note any stutter.
6. Tell the board it is done (resume r74), with the time you did it. Screenshots are welcome, never
   needed.

## The record (the agent's part)

    . deploy/vps.sh; vps "sudo docker logs --since 2h lachassegalerie-lobby" | grep -E "OPENED|JOINING|REPORT"

A line is `REPORT <code> from <public ip:port> : <computer> <what>`; `<what>` is `nat ...` or
`host|joiner row <the other end> <ROUTE> [via <address>] [in <ms> ms]`. Expected:

- A and B: `nat EASY` (or `OPEN`); C: `nat HARD` and an `advice:` naming a fix (join over Wi-Fi).
- A <-> B: both rows `DIRECT`.
- A <-> C: `DIRECT` or `RELAYED` — never `CANNOT_CONNECT`.
- The switch: A's row for C goes `RECONNECTING` (a direct route does after 3.5 s of silence), never
  a `DIRECT` that stays while carrying nothing; note what it becomes after.

Attach that grep to r74. Anything that is not the expected verdict is a defect in
`jks.lobby.Lobby_Ice` or the relay path: file it as a #network task with the log lines.
`nettest`'s `ice/gate-reports-say-the-rows` proves the same lines on the in-memory wire.

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
  `./gradlew :desktop:run --args="--menu"`), or the Linux zip from http://141.94.115.201:8080/play/. The host needs the
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
