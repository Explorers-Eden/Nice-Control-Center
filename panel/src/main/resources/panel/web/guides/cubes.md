# Sulfur cube pool

Sulfur cubes holding a block roll around a field. Punch them into the holes: whoever hit a cube last scores the hole's points times the cube's. Needs Minecraft 26.3.

## Set it up

1. `/game create cubes <name>` where players appear.
2. The field: `/game edit <name> corner1` and `corner2` (height included). A cube that leaves it rolls back out somewhere else.
3. Dig the pits (a block deep is enough), stand in each and type `/game edit <name> hole add <points> [radius]`. Radius 0 is that block, 1 a 3×3 pit, 2 a 5×5 one. `hole remove` takes away the nearest, `hole list` shows them all.
4. Optional: where cubes roll out, `/game edit <name> mobspawn add` (several). Without them they come out at the player spawn.
5. An entrance: `/game edit <name> entrance add`.

## Settings

- **Cube size**: small (about half a block) or full size (about a block). Make the pits fit.
- **Cubes rolling at once**.
- **Punch strength**: % of vanilla (50 by default). At 100 a bouncy cube flies about 40 blocks, so build walls or keep it low.
- **Shove**: punching another player knocks them back. Nobody takes damage either way.
- **Cubes**: one per line, `block=points wN`. The block the cube holds decides how it rolls, `wN` how often it comes, below 0 costs points:
  - dirt, clay, mud, grass: rolls normally
  - planks, logs: bouncy
  - blue ice, packed ice, snow block: slides far
  - wool: light and floaty
  - soul sand, soul soil: heavy
  - honeycomb block: sticky
  - TNT: the first punch lights it and it goes off a few seconds later. It breaks nothing in the arena and hurts nobody, but it throws players and cubes about.

Every cube shows its points over it: white 1, green 2, gold 3 and up, red costs points. A cube that rolls into a hole by itself, or one nobody touched for 20 seconds, scores nothing and comes back somewhere else.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
