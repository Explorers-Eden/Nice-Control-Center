# Wave fight

A party of 1–4 fights waves of mobs in an arena you built, with boss waves now and then. Rewards per wave and for clearing. Wave arenas are on the **Arenas** tab.

## Set it up

1. Stand where players should appear: `/game create waves <name>` (move it later with `/game edit <name> spawn`).
2. The fight box: `/game edit <name> corner1` and `corner2` (height included).
3. Mob spawns: `/game edit <name> mobspawn add` (several). `mobspawn add melee|ranged|boss` makes a spawn for one kind; `mobspawn clear [kind]`.
4. The kit: what you carry becomes it with `/game edit <name> kit`. `kit add` adds another one (each player gets one at random), `kit clear` removes them. Or let them fight with their own gear.
5. Optional: a spectator area (`spectate [radius]` or a box), back points inside (5 seconds on one leaves).
6. An entrance, or put the arena in a **pool**.
7. Test it: `/game try <name>`.

## Waves

- **Waves to clear** (0 = endless), **mobs in wave 1**, **more each wave**, **% more per extra player**.
- **Toughness**: % more health and damage per wave.
- **Mob pool**: one line per mob like `/summon` (`zombie`, `skeleton x2`, `husk {…}`, `npc:knight`). `w5` makes a line come five times as often, `w0` never.
- **Hand-written waves** replace the generated ones: `5: husk x6, skeleton x2`.
- **Team lives** (0 = only all down loses), **break between waves**.

Mob kinds: blazes, ghasts, witches, evokers, bow, crossbow and trident holders, and mobs that shoot or spit are ranged and come out of ranged spawns; the boss out of boss spawns; the rest out of melee ones. Without spawns for a kind, they use the spawns for any mob.

## Bosses

- **Boss wave every … waves**: the boss comes alone.
- A list of bosses, picked **at random** (with weights like `ncc:frost_warden w3`) or **in order**.
- Built-in bosses: 37 NCC bosses (`ncc:…`) and 5 Katters Structures bosses by Katter (`katters:…`). They fight with their own attacks, get the "% more health per wave", stay inside the fight box and drop nothing. Try one where you stand: `/game boss <name>`, gone with `/game boss clear`.

## Arena pools

Arenas can share a pool (`/game edit <name> pool <pool>`, or the field). A pool entrance (`/game pool <pool> entrance add`) starts whichever arena of the pool is free. When all are busy, the party waits in the spectator area of one of them and plays in the first that's free.

## Good to know

- The fight box is put back as it was after every wave and at the end: cobwebs, fire, placed or broken blocks, water and lava are gone.
- Hostile mobs always attack in games; peaceful and neutral ones too unless switched off.
- Rewards: commands after each cleared wave (`{wave}`), the finish commands when cleared, and the Rewards triggers "Finishes a game" / "Clears a wave in a wave arena".
