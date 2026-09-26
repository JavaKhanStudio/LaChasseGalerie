#!/bin/sh
# The lexicon's first entries (r96, 2026-09-26), written from README.md, docs/*.md and
# .atelier/packs.toml. Re-running refuses the terms that already exist; it is kept as the record
# of what was added and from where, not as something to run again. --distinct marks the four
# words another board already has (onboard, parallax): sessions here are handed only this board's.
T() { atelier --project lachassegalerie term add "$@" --agent "${ATELIER_AGENT:-hal}" || true; }

T "vue" --distinct --tags gameplay,hud,network --aliases "Vue_Game,Vue_Menu,Vue_Lobby,Vue_Client" \
  --short "French for 'view': one whole screen of the game (the run, the menu, the lobby, a client's run); GVars_Heart.changeVue swaps them and disposes the old one." \
  --source "core/src/jks/vars/GVars_Heart.java"
T "personnage" --tags gameplay --short "French for 'character': the package of heroes, monsters, the axe and the score label (PhysicSpriteHeroes, PhysicSpriteEnnemy)." \
  --source "core/src/jks/personnage/"
T "Ennemy" --tags gameplay,physics --aliases "ennemy,ennemies" --short "The code's spelling of 'enemy': a monster, which chases a random hero and dies to one axe hit." \
  --source "core/src/jks/personnage/PhysicSpriteEnnemy.java"
T "Ficture" --tags physics --aliases "Enum_FictureType" --short "The code's spelling of Box2D 'fixture': Enum_FictureType types a contact by the fixture's userData, and an untyped one is silently ignored." \
  --source ".atelier/packs.toml [tags.physics]"
T "Gris" --tags assets,gameplay --short "French for 'grey': one of the six hero colours, and part of an atlas file name (perso/HeroGris.atlas)." \
  --source "core/src/jks/personnage/index/Index_Sprite.java"
T "parralax" --tags background --aliases "jks.parralax,GVars_Parralax" \
  --short "The game's misspelled package for its OWN night river scene; 'parallax' (correctly spelled) is the parallax-background library it draws with, which is not in this repo." \
  --source ".atelier/packs.toml [tags.background]"
T "parallax-background" --tags background,build --short "Simon's parallax library (io.github.javakhanstudio), a Maven dependency built on the Parallax board; html/ carries a patched 2.1.0 until 2.2.0 is published." \
  --source "gradle.properties parallaxVersion; docs/browser-target.md section 4"
T "ColdNight" --tags background --aliases "ColdNightModel,Enum_ColdNight,COLD_NIGHT,COLD_WATER" \
  --short "The night river scene, declared in Java: COLD_NIGHT is the background heart behind the heroes, COLD_WATER the foreground heart whose water passes in front of them." \
  --source "core/src/jks/parralax/ColdNightModel.java"
T "heart" --distinct --tags background --aliases "Parallax_Heart" --short "One parallax scene the library draws as a whole; the game has two (background and foreground), and moving a layer between them moves it across the characters." \
  --source ".atelier/packs.toml [tags.background]"
T "GVars" --distinct --tags gameplay,physics,hud,audio,background,network,input --aliases "GVars_,FVars_,Gvars_" \
  --short "The prefix of the classes holding the game's global static state (GVars_Game, GVars_Story); FVars_ hold its constants. A static a run changes must be reset when a run starts." \
  --source ".atelier/packs.toml [tags.gameplay]"
T "IKM" --tags input,hud --aliases "IKM_Game_Keyboard,IKM_Game_XBoxController,IKM_Menu_Keyboard,IKM_Menu_XBoxController" \
  --short "The prefix of an input listener: keyboard or pad, for the run or for a menu; 'XBoxController' means any gamepad." \
  --source "core/src/jks/input/"
T "run" --tags gameplay --short "One play from start to score screen: it lasts as long as its song, ends 5 s after the canoe lands, and is torn down whole before the next." \
  --source "README.md Controls; core/src/jks/story/GVars_Story.java"
T "take-off" --tags gameplay,background --short "The moment the canoe leaves the river for the sky, 37 s into a run (stable at 45 s); the landing near the song's end brings it back." \
  --source "core/src/jks/story/GVars_Story.java"
T "destroy queue" --tags physics,gameplay --aliases "toBeDestroy_Body,toBeDestroy_Jointure,toDie" \
  --short "Where a Box2D body or joint waits to be destroyed before the next world step; destroying one where it is found crashes the JVM natively." \
  --source ".atelier/packs.toml [tags.physics]"
T "the step" --tags physics,gameplay --aliases "FVars_Heart.step" --short "The world's fixed 1/60 s tick, run once per whole step of real time; speeds are tuned per step, so the world never reads the frame's delta." \
  --source ".atelier/packs.toml [tags.physics]"
T "smoke" --tags build,gameplay,physics --aliases "smoke run,./gradlew smoke,Smoke_Run" \
  --short "The headless gate: a minute of the real game loop with scripted players in about 1.5 s, failing on the first broken bookkeeping invariant; it cannot see rendering." \
  --source "README.md Smoke run"
T "seed 1" --tags build,gameplay --aliases "19/77" --short "The smoke's reference run: deaths=19, monstersKilled=77; a change that claims to leave the game alone must reproduce it." \
  --source ".atelier/packs.toml [tags.build]"
T "offscreen" --distinct --tags build,hud,audio --short "How an agent's game run stays off Simon's screen and speakers: the window in cage (a headless compositor), the sound in desktop/build/audio/run.wav." \
  --source "README.md 'When an agent runs it'; gradle/offscreen.gradle"
T "publish gate" --tags build,coordinator --short "tools/publish_gate.sh: build, smoke, nettest and dist, which the board's worker runs on a commit before it pushes it." \
  --source "tools/publish_gate.sh"
T "d-number" --aliases "d3,d7,d12" --short "A doubt put to Simon on this board; 'd4 -> A' records the option he picked, and code comments cite it as the reason for a choice." \
  --source "projects/lachassegalerie.toml comments; .atelier/packs.toml"
T "phase" --tags network --aliases "phase 0,phase 1,phase 2,phase 1.5" \
  --short "A step of the online plan: 0 made the game a simulation, 1 netcode on localhost, 2 lobbies and the real internet, 3 feel if measured." \
  --source "docs/online-multiplayer.md section 6"
T "the seam" --tags network --aliases "Net_Transport" --short "Net_Transport, the only way the game sends bytes: a desktop peer is a UDP endpoint and a tab a WebRTC data channel, and the game never touches a socket itself." \
  --source "README.md Net gate; core/src/jks/net/Net_Transport.java"
T "host" --tags network --aliases "HostSession" --short "The player whose machine runs the one Box2D world, applies everyone's inputs and sends snapshots 20 times a second; clients only draw." \
  --source "docs/online-multiplayer.md section 2"
T "client" --tags network --aliases "ClientSession,joiner,Vue_Client" --short "A player who owns no physics: sends an input bitmask and draws the host's run from snapshots." \
  --source "docs/online-multiplayer.md section 2"
T "tab" --tags network,build --short "A browser player: the game compiled to JavaScript, joining a desktop host over an RTCDataChannel, with its lobby over WebSocket; it cannot host." \
  --source "docs/browser-target.md sections 11-12"
T "snapshot" --tags network --aliases "Net_Snapshot" --short "The host's picture of every entity at one tick, at most 1200 bytes, from which a client places sprites without a world." \
  --source "core/src/jks/net/Net_Snapshot.java"
T "mirror" --tags network --aliases "netmirror,Snapshot_Mirror,world-less client" \
  --short "Applying the host's snapshots to a client with no world; netmirror fails unless every entity is where the host's body is." \
  --source "README.md Net gate"
T "lobby service" --tags network,build --aliases "lobby,Lobby_Service" \
  --short "The small always-on process on VPS_1 (UDP 7770) that lists games, hands out six-letter codes and sets up the punch; it runs no game logic." \
  --source "README.md; core/src/jks/lobby/"
T "code" --tags network --short "The six letters a lobby service gives a hosted game; a joiner needs only that and the service's address." \
  --source "README.md Net gate"
T "punch" --tags network --aliases "hole punching" --short "Two players behind NAT reaching each other directly over UDP; when it fails the pair goes through the relay, and the lobby row says 'relayed'." \
  --source "docs/online-multiplayer.md section 3"
T "relay" --tags network --aliases "coturn,TURN" --short "coturn on VPS_1 (UDP 3478), which carries a pair's traffic when the punch fails; it refuses every private and loopback peer, and those lines stay." \
  --source "deploy/relay/; .atelier/packs.toml [tags.network]"
T "lobby row" --tags network,hud --aliases "direct,relayed,reconnecting,cannot connect" \
  --short "A peer's state on the lobby screen: direct (punched), relayed, reconnecting (a working route silent for 3.5 s) or cannot connect." \
  --source "core/src/jks/lobby/Lobby_Ice.java"
T "front" --tags network --aliases "WebSocket front,Transport_Ws" --short "The lobby service's second door, a WebSocket server on TCP 7771, through which a tab browses, joins and exchanges its WebRTC offer and answer." \
  --source "README.md; core/src/jks/lobby/Transport_Ws.java"
T "lobby VERSION" --tags network,build --short "The lobby protocol's number: a game and VPS_1's service must match, so any bump means redeploying deploy/lobby (and deploy/web with it)." \
  --source ".atelier/packs.toml [tags.network]"
T "VPS_1" --tags network,build --short "The OVH box at 141.94.115.201, shared with Fountain of Dreams, that runs the lobby service, the relay and the browser page (:8080)." \
  --source "deploy/vps.sh; docs/online-gate.md"
T "natives" --tags build,network --aliases "cannot take tabs,NO_TABS" \
  --short "libwebrtc's platform library; a dist carries only its build machine's, and a host without them still plays but refuses tabs (NO_TABS)." \
  --source ".atelier/packs.toml [tags.build]"
T "focus ring" --tags hud,input --aliases "Menu_Focus" --short "A menu's list of choices, moved and picked alike by the mouse, the keyboard and any pad; a pick runs at the view's next update, not in the callback." \
  --source "core/src/jks/vinterface/Menu_Focus.java"
T "picker" --tags hud,input --aliases "Menu_Picker" --short "Who made a menu pick (a pad, the keyboard, or a pointer that is nobody); a run opened from a menu joins that picker as player 1." \
  --source "core/src/jks/input/Menu_Picker.java"
T "join" --tags input,gameplay --short "A device's first press, which only adds its hero and does nothing else; pressing again after death rejoins as the same player." \
  --source "README.md Controls"
T "chasse-galerie" --tags gameplay --short "The French-Canadian legend of a flying canoe, which the game is named after: heroes share a canoe down a night river that takes to the sky." \
  --source "README.md"
