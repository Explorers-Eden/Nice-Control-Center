# Setting up games

How to set up every game on the server. Commands in `code` are typed in game (as an admin); everything else is
in the panel under **Games**. `<name>` is the game's one-word name.

## The basics (every game)

1. **Game hub spawn**: stand in your hub, `/game spawn set`. After any game, players land here.
2. **Create the game** where it should be: `/game create <type> <name>`. It answers with the next steps.
3. **Display name** (spaces allowed): `/game edit <name> title Sky Run Deluxe`, or the field on the game's card.
   Commands keep using the one-word name.
   **Colour**: the picker on the game's card (entrance title and particles, watch point, start title).
4. **Entrances** (where players get in, e.g. in the hub): `/game edit <name> entrance add` where you stand.
   Particles, the name and the top 3 float above it; stepping in for a second takes players to the game.
5. **Watch points** (to the spectator area): `/game edit <name> spectate [radius]` where players are put, or a box:
   `/game edit <name> spectate corner1` and `spectate corner2` (players may move anywhere inside it); then
   `/game edit <name> watch add` in the hub. `spectate clear` removes the area.
6. **Back points** (the way out of the spectator area): `/game edit <name> back add`.
7. Points take `remove` (the nearest within 5 blocks) and `clear`, e.g. `/game edit <name> entrance remove`.
   The panel lists them all and can take them away too.
8. **Start point / spawn**: where `create` was typed. Wave fights and quick rounds: that's the spawn, where players
   appear (`/game edit <name> spawn` moves it). Parkour, race, dropper, maze: the start point
   (`/game edit <name> start`).
9. **Points** for a game: on its card, "Points when their score counts" (fixed) and "+ points per …" (per
   score; timed games only get the fixed amount). Commands can do sums: `{score*2}`, `{score/10}`, `{jumps*3}`,
   `{wave+5}`, e.g. `points give {player} {score*2}`.
   **Rewards**: each game has commands (`{player}`, `{score}`, `{game}`). In **Rewards**, rules on
   "Finishes a game" / "Sets a personal best" (and the per-game triggers below) can give anything, including
   **Points** for the reward shops.
10. **Box** (`/game edit <name> corner1` and `corner2`, standing there or looking at the block; height included):
    the play area where a game uses one.
11. Try a game: `/game try <name>` (wave fights and quick rounds). Scores: `/game top <name>`.

Protect a build with a server claim when nobody is playing. Wave fights, quick rounds and dungeons put inventories
aside themselves; parkour, race, dropper and maze don't, so give them a game claim if you want that (Claims page:
game-area options, including the game mode inside).

## Solo games

**Parkour** (`parkour`): jumps appear ahead, every player in their own lane.
- Optional box: `corner1` / `corner2` (lanes share it, 9 blocks wide each; jumps turn at the walls). Without one,
  lanes go off along +X from the start point, jumps along +Z.
- Panel: block palette, difficulty preset (easy / normal / hard / extreme: gets harder in levels), longest gap,
  reward every … jumps (commands with `{jumps}`; Rewards trigger "Makes jumps in parkour").

**Race** (`race`): checkpoints in order, then the finish; running, boat or elytra.
- `/game edit <name> checkpoint add <radius>` at each checkpoint in order, then `/game edit <name> finish <radius>`.
- Panel: mode, time limit, commands at each checkpoint (`{checkpoint}`).

**Dropper** (`dropper`): fall from the top into the water at the bottom.
- `/game edit <name> top` where they drop from, `/game edit <name> finish <radius>` in the water.

**Maze** (`maze`): generated, rebuilt daily or after each run.
- Created at its corner; `/game edit <name> size <cells>` then `/game edit <name> build`. Panel: blocks, height.

## Wave fight (`waves`)
A party of 1–4 (`/party invite <player>`) fights waves of mobs.
- Create it where players should appear (the spawn; move it with `/game edit <name> spawn`), then `corner1` / `corner2` (the fight box),
  `mobspawn add` (several), optional `spectate [radius]`, `back add` (exit pads inside: 5 s on one leaves),
  `kit` (what you carry becomes the kit).
- Panel: waves to clear (0 = endless), mob pool, wave sizes, boss, toughness, hand-written waves, team lives,
  peaceful and neutral mobs attacking too (on by default), commands after each wave (`{wave}`).

## Quick rounds
Everyone who steps in during the join window (10 s) plays the same round, each for their own score. Nobody dies:
a deadly hit knocks them out. A spectator area also queues players for the next round.

**Ghast deflect** (`ghasts`): hit the fireballs back with snowballs.
- Created where players appear (the spawn). `corner1` / `corner2`, `mobspawn add` up high (several).

**Colour floor** (`colorfloor`): a 3×3 grid of coloured platforms; the called colour vanishes.
- Stand at the grid's corner: `/game edit <name> grid`. Panel: colours, platform size and gap, speed,
  refills, mobs (peaceful ones attack too unless switched off).

**Anvil rain** (`anvils`): dodge anvils falling from the box's top. Created at the spawn; `corner1` / `corner2`.

**Treasure grab** (`treasure`): glowing items appear on the box's floor. Created at the spawn; `corner1` / `corner2`;
panel: items and their points (`minecraft:diamond=5`).

**Fishing frenzy** (`fishing`): catches score points. Created at the spawn; `corner1` / `corner2` with water inside;
panel: points per catch.

## Head hunt (`heads`)
Heads hidden in the world; players right-click them.
- `/game edit <name> texture <skin url or value>`, then `/game edit <name> headitem [count]` and place them
  (each joins the hunt). `head add` / `head remove` for a head you look at. Break one in creative to take it out.
- Panel: reward every … heads (`{found}`, `{total}`), all found; where every head is.
- Rewards trigger "Finds hidden heads".

## Dungeons
Built anywhere, copied fresh for every party into the dungeon dimension.
- `/dungeon create <name>` at one corner, `/dungeon corner2` at the other, `/dungeon save <name>` (again after
  changes). Markers are data structure blocks: `start` (needed), `exit`, `checkpoint`, `mob:zombie x3`, `boss:…`.
- `/dungeon pad add <pool>` where parties start; `/dungeon pad wait <pool> [radius]` for a waiting area when every
  copy is in use. `/dungeon kit <name>` for its kit.
- Random rooms: `/dungeon room create|save`, spots and doors as jigsaw blocks (see the Dungeons tab).
- Panel: lives, time limit, peaceful and neutral mobs attacking too (on by default), rewards.

## Points and reward shops
- Points come from Rewards rules (the reward "Points") or `/points give <player> <n> [reason]`; own games built
  with command blocks use `/points give|take|check`. Name and abbreviation: Games → Points.
- A shop: `/points shop create <name>` where its NPC stands; `/points shop npc add <shop>` for more;
  hold an item, `/points shop item <shop> <price> <name>`. Skin, prices, limits, commands and other rewards:
  Games → Reward shops.
