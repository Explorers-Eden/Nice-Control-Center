# Rogue runs

A rogue run is a chain of rooms. Players clear a room and pick a door. The next room comes up, a bit harder than the
last, and so on until everyone is out (or until a set number of rooms is cleared). There's no box to build for the
run itself: its rooms are ordinary dungeon rooms, picked at random from groups. Runs use the dungeon pads, pools,
parties, kits and the dungeon dimension, so everything in [DUNGEONS.md](DUNGEONS.md) about those applies here too.

**One life:** players who die are out and watch their party until the run ends. Leaving or logging out is out too.

## 1. Your first rogue run

1. Make the run: `/dungeon rogue create Crypt` (or in the panel, **Games → Dungeons**, type a name and press
   **New rogue run**). It gets four groups named after it: `crypt_start`, `crypt_fight`, `crypt_elite` and
   `crypt_boss`.
2. Build a few rooms (at most 64 × 64 × 64 each) and put markers in them. The markers are data structure blocks, the
   same as dungeon markers, and `/dungeon tool marker <text>` gives you one:

   | Marker | What it does |
   |---|---|
   | `start` | Where players arrive (needed). |
   | `door` | Where players pick the next room. There's one door per marker, so 2 or 3 doors make a choice. |
   | `spawn` | Where the run's mobs come (shared out over all spawn markers). |
   | `mob:<id> [xN]` | Fixed mobs, as in dungeons (they get tougher with the room too). |
   | `boss:<id>` | The boss of a boss room (else one from the run's boss list comes at a spawn marker). |

3. Save each room into a group: `/dungeon room create <room>` at one corner, `/dungeon corner2` at the other, then
   `/dungeon room save <room> crypt_fight` (or `crypt_elite`, `crypt_boss`, `crypt_start`). A room can be in several
   groups: the same hall can be a fight room and an elite room.
4. Put a pad down: `/dungeon pad add <pool>` with the pool the run is in (`default` unless you changed it).
5. Try it: `/dungeon try Crypt`.

The first room comes from the start group (a safe room whose doors are open at once). With no room in that group, the
run starts in a fight room.

## 2. How a run goes

- Every foe in the room down → the doors open, each showing what's behind it: **⚔ Fight**, **☠ Elite** or
  **♛ Boss**.
- The party leader picks by standing on a door for 2 seconds (playing solo, you pick yourself).
- The next room is pasted next to the old one, everyone is brought there, and the old room is wiped.
- Every few rooms (**Boss room every … rooms**), every door leads to a boss room.
- The run ends when everyone is out. A win: **Rooms to win** cleared (0 = endless), or no room left to go to.

## 3. Settings (panel, Games → Dungeons, on the run's card)

| Setting | What it does |
|---|---|
| First room group, fight/elite/boss groups | Where each room type's rooms come from. |
| How often (weight) | How often a door leads to a fight or an elite room. |
| Boss room every … rooms | 5 = rooms 5, 10, 15… are boss rooms (0 = never). |
| Rooms to win | 0 = endless. |
| Mobs | The mobs spawn markers draw from (`zombie`, `skeleton x2`, `npc:…`). |
| Bosses | One of these at random in a boss room (`ncc:…`, `katters:…`, any mob). |
| Mobs in the first room, more per room, % per extra player | How many mobs a room gets (at most 40). |
| % more health and damage per room, elite % on top | How much tougher each room gets. Bosses get the same as more health. |

Party size, kit, time limit, messages and commands work as for dungeons. Room weight, chance, "at most per run" and a
group's "from level" (here: from room number …) are on the Rooms page.

Messages can use `{depth}`, the number of rooms cleared.

## 4. Rewards and the board

- Rewards triggers: **rogueRoom** (each room cleared, from room …), **rogueBoss** (each boss beaten),
  **rogueEnd** (the run is over, at least … rooms cleared). A win fires **dungeonCleared** as well.
- The best run per party size (most rooms, then the faster time) is on the run's card and in `/dungeon top <name>`.

## 5. Commands

| Command | Who | What |
|---|---|---|
| `/dungeon rogue create <name> [pool]` | admin | Make a rogue run |
| `/dungeon rogue pick <slot> <door>` | admin | Go through a door (for console tests) |
| `/dungeon try <name>` | admin | Play it yourself (from the console: a 3-minute test with nobody in it) |
| `/dungeon leave` | everyone | Leave the run (you're out) |
