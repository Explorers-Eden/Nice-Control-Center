# Setting up games

How to set up every game on the server. Commands in `code` are typed in game (as an admin); everything else is
in the panel under **Games**. `<name>` is the game's one-word name.

## The basics (every game)

1. **Game hub spawn**: stand in your hub, `/game spawn set`. After any game, players land here.
2. **Create the game** where it should be: `/game create <type> <name>`. It answers with the next steps.
3. **Name**: the one-word name can be changed on the game's card (reward rules follow).
   **Display name** (spaces allowed): `/game edit <name> title Sky Run Deluxe`, or the field on the game's card.
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
   appear (`/game edit <name> spawn` moves it). Parkour, race, maze: the start point
   (`/game edit <name> start`).
9. **Points** for a game: on its card, "Points when their score counts" (fixed) and "+ points per …" (per
   score; timed games only get the fixed amount). Commands can do sums: `{score*2}`, `{score/10}`, `{jumps*3}`,
   `{wave+5}`, `{score*1.5}` (rounded to a whole number), e.g. `points give {player} {score*2}`.
   **Rewards**: each game has commands (`{player}`, `{score}`, `{game}`). In **Rewards**, rules on
   "Finishes a game" / "Sets a personal best" (and the per-game triggers below) can give anything, including
   **Points** for the reward shops.
10. **Box** (`/game edit <name> corner1` and `corner2`, standing there or looking at the block; height included):
    the play area where a game uses one.
11. **Music** (optional, every game but the head hunt): pick a jukebox song from the list (vanilla and datapack
    songs, with their length) and a volume; it loops for everyone in the game, only for them, on their
    Jukebox/Note Blocks slider. A second song can play in the **spectator area** (the queue, players who are out,
    visitors from a watch point). Leaving the game or the area stops it at once.
12. **Mobs** (wave fights, ghast deflect, colour floor, snowball gallery, maze): one mob pool, the same everywhere, one line per mob
    like `/summon`: `zombie`, `skeleton x2`, `husk {equipment:{mainhand:{id:"minecraft:iron_sword",count:1}}}`, or an NPC
    `npc:knight`; plus how many at once and whether peaceful mobs fight.
13. Try a game: `/game try <name>` (wave fights and quick rounds). Scores: `/game top <name>`.

Block fields (parkour blocks, maze walls and floors, colour floor colours, claim border markers) take block ids,
tags (`#minecraft:wool`) and comma-separated lists, mixed at random.

Every game starts with full health and the hunger bar at 17 without saturation (one short of where health comes
back by itself): players heal by eating, not just by having a full bar.

Protect a build with a server claim when nobody is playing. Wave fights, quick rounds and dungeons put inventories
aside themselves; parkour, race and maze don't, so give them a game claim if you want that (Claims page:
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

**Maze** (`maze`): generated, rebuilt daily or after each run.
- Created at its corner; `/game edit <name> size <cells>` (4–256) then `/game edit <name> build` (big ones take a
  few seconds). Panel: path width (1–5), wall, floor and ceiling blocks, height, light level of the paths, the blocks
  under the goal, chests in dead ends (share and loot table), mobs roaming it while someone runs it (the mob pool
  and how many at once; they come out 12–32 blocks away and go when nobody runs it). Build again after changing the layout.
- The goal has an exit ring and an "Exit" label in the game's colour.
- Layout: corridors turn more often than they go straight and never run more than 3 cells straight while they could
  turn; crossroads (panel, 12 % by default, up to 50 %) knock out extra inner walls (that % of half the cells), so ways
  meet and loop and there's more than one way through. Crossings first open dead ends into a passing corridor (a T on
  one side only), then join two corridors, but never next to another junction. A crossing never opens a cell up on all
  sides, leaves a wall corner standing alone or a short piece of wall standing free, so there are no open spaces to
  look across. Past about 30 % there's little room left for more.
- Building it clears the mobs, vehicles and items inside (not players, NPCs, pets, named mobs, armor stands, frames
  or labels); players inside go to its start.

## Wave fight (`waves`)
A party of 1–4 (see Parties) fights waves of mobs.
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
  Panel: anvils a second and how fast that grows, the share dropped right above a player (70 % by default).
  The anvils land without a sound.

**Treasure grab** (`treasure`): glowing items appear on the box's floor. Created at the spawn; `corner1` / `corner2`;
panel: items and their points (`minecraft:diamond=5`).

**Fishing frenzy** (`fishing`): catches score points. Created at the spawn; `corner1` / `corner2` with water inside;
panel: points per catch. Fish bite within a few seconds; bubbling spots come and go on the water, and a hook in one
gets a bite almost at once.

**Whack-a-mole** (`moles`): heads pop out of a grid of holes for a moment; hitting or right-clicking one scores it.
- Created at the spawn. Stand where the grid's corner goes: `/game edit <name> grid` (lays the hole blocks toward
  +X and +Z; again after changing the size). No box needed (it's around the grid).
- Panel: holes per side, gap, hole block, how long a head stays up (down to a third by the end), heads at once,
  and the heads with their points (`minecraft:zombie_head=1`, a skin URL/hash/base64 for a player head;
  below 0 costs points, e.g. `minecraft:wither_skeleton_skull=-3`).

**Target practice** (`targets`): target blocks appear in the box; shoot them before they vanish.
- Created at the spawn (where they shoot from); `corner1` / `corner2`. Players get a bow and arrows (refilled).
- A hit: 1 + a point per 10 blocks from the spawn, twice that for a bullseye. Panel: on the walls or floating
  anywhere, seconds a target stays, targets at once. Targets only go where there's air.

**Snowball gallery** (`gallery`): mobs walk past on lanes; hit them with snowballs.
- Created at the spawn; `corner1` / `corner2` (where the players stay); lanes: `/game edit <name> lane add` at a
  lane's start, `lane end` at its end (several; `lane clear`).
- Panel: the mobs (the mob pool, not NPCs), how many walk at once, the walking speed (15 % faster every 15 s), and
  points per mob (`minecraft:villager=-5`; mobs not listed give 1). They can't be hurt otherwise and don't fight.

**Cauldron chef** (`chef`): the cook NPC orders food; gather and cook it, then right-click the cook to serve.
- Created at the spawn; `corner1` / `corner2` (the kitchen). Fill chests with ingredients and
  `/game edit <name> station add` looking at each (`station remove`, `station clear`): they're filled with that
  again at each round and every 30 seconds. Put crafting tables, furnaces, smokers in the kitchen; players may use
  everything in it whatever the claim says.
- Panel: the cook (an NPC from Community → NPCs), the orders (`bread x2, cookie x4 = 5`), the wait between two.
  Served within 20 seconds: +1. Their own things are always put aside.

**Rising lava** (`lava`): lava fills the box from its floor up, a layer at a time and faster each layer.
- Created at the spawn (at the bottom); `corner1` / `corner2` around the tower or cave. Touching the lava is out; when
  it reaches the top, everyone still up made it. The score is how long they lasted.
- The lava only goes where there's air and doesn't flow. Afterwards all lava and fire in and near the box go (also
  after a crash), so build with blocks that don't burn and keep your own lava away from it.
- Panel: seconds before the next layer at the start, how much faster each layer, the fastest.

## Head hunt (`heads`)
Heads hidden in the world; players right-click them.
- `/game edit <name> texture <skin url or value>`, then `/game edit <name> headitem [count]` and place them
  (each joins the hunt). `head add` / `head remove` for a head you look at. Break one in creative to take it out.
- Panel: reward every … heads (`{found}`, `{total}`), all found; where every head is.
- Rewards trigger "Finds hidden heads".

## Parties
Players who play together, in Flying Fair and the dungeons (and the game hub's dimension).
- `/party` opens the party menu (create, invite someone from a list, remove players, leave, disband). The commands
  work too: `/party create`, `/party invite <player>`, `/party accept`, `/party leave`, `/party disband`,
  `/party kick <player>`, `/party list`.
- The leader makes it (create, or just invite someone); up to 8 players. It stays, even with only the leader, until
  the leader disbands it, logs out or leaves Flying Fair and the dungeons. Others can leave any time; going to
  another dimension or logging out leaves too.
- Dungeons and wave fights start for the party from the leader on the pad (party members within 16 blocks come
  along); game entrances bring the party with the leader, quick rounds included.
- Too many for a game: everyone is told who plays now and who next. Wave fights: the first ones play, the rest
  queue in the spectator area for the next game. Quick rounds: once the round is full, the rest wait in the spectator
  area and play the next round (without a spectator area they're told it's full). Dungeons: the party goes in
  groups, each in a copy of its own (or the queue when every copy is in use).
- Admins with claim bypass on (`/claim admin bypass`) are left alone by every pad (entrances, start points, dungeon
  pads, exit pads) and aren't brought along with a party, so they can walk around the games while building.

## Dungeons
Built anywhere in the world, copied fresh for every party into the dungeon dimension and wiped again when they're
done. The full setup guide (markers, pads and parties, gear, random rooms, all commands) is in
[DUNGEONS.md](DUNGEONS.md).

## Game worlds
Performance → per-dimension tuning (the Flying Fair preset sets these): no pet perks, and **random looks**: mobs
there get random colours and kinds once (llamas, parrots, horses, rabbits, sheep…; villagers a random job; named
mobs stay as they are), and villagers and wandering traders open no trading screen. Both are always on in the
dungeon dimension.

## NPCs
Panel: Community → NPCs; in game `/npc`.
- Skins: a player name, a textures.minecraft.net link, or a link to any skin picture (e.g. novaskin): those are
  uploaded through MineSkin once, which needs a free API key from mineskin.org (NPCs page, top).
- `/npc create <name>` where it should stand (facing your way), or `/npc adopt <name>` next to a mannequin you made
  before (keeps skin, pose, name, equipment). `/npc skin <name> <player name|link|base64>`, `/npc pose`, `/npc equip`
  (what you wear and hold), `/npc move`, `/npc remove`, `/npc list`.
- Walking: `/npc path <name> add [seconds to wait]` at each checkpoint in order; `remove` (the last), `clear`.
  The panel picks how: round and round, back and forth, or to random checkpoints (a different one each time).
- Panel: actions on a right-click and on a hit (`say: Hello {player}!`, `player: spawn`, or a command as the server),
  a reward shop, talking lines (now and then, or one per click), the hostile stats.
- **Sounds:** pick a mob (e.g. `husk`) and the NPC uses its sounds: the idle sound now and then (also when it talks or is
  right-clicked), the hurt sound on a hit, the death sound when a hostile one dies, steps while walking; pitch 0.5–2 and
  volume in the panel. Empty = silent.
- **Hostiles:** `npc:<name>` in any mob pool (wave fights, dungeon markers `mob:npc:knight x2`, maze mobs), or
  `/npc summon <name> <x y z>`. It fights with what it holds: sword or nothing melee, spears charge, bow/crossbow shoots, trident throws.
  Panel: health, damage, speed, armor, a size range (random each time) and the loot table it drops.

## Slot Machines

Machines players spend points at, built from five looks (acacia, bamboo, mangrove, poplar, warped): a copper base
with the lever on its side, the reel shelf on top of it, shelves behind and a trapdoor roof.
- Panel (Games → Slot Machines): **types**: what a pull costs, how long the reels spin, the **wins** (three symbols
  as item ids, one filled in = three of it; each with its chance per pull in %, its payout in points, optional
  commands, a message, and whether everyone is told) and extra symbols that only show on losing rows. The page shows
  the chance of any win and how much of what's put in comes back on average.
- In game: `/slots place <type> [look]` shows a glowing preview two blocks ahead (front toward you); click Place,
  Turn, Look, move it a block (forward, back, left, right, up, down) or Cancel. `/slots type <type>` next to one
  changes its type, `/slots remove` takes the nearest away (removing one in the panel takes its blocks away
  too), `/slots list`.
- Pulling the lever takes the cost, flicks back at once (it powers nothing) and spins the three shelf slots, which
  stop one after the other; a win pays out with a chime and sparkles (a burst for 10× the cost or more). Anyone can
  pull the lever, also in claims; nobody can take the shelves' items or break the machine (admins in creative can).

## Points and reward shops
- Points come from Rewards rules (the reward "Points") or `/points give <player> <n> [reason]`; own games built
  with command blocks use `/points give|take|check`. Name and abbreviation: Rewards → Points.
- A shop: `/points shop create <name>` where its NPC stands; `/points shop npc add <shop>` for more (they're NPCs:
  skin, lines and the rest under NPCs);
  hold an item, `/points shop item <shop> <price> <name>`. Skin, prices, limits, commands and other rewards:
  Rewards → Reward shops.
