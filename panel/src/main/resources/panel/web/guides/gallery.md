# Snowball gallery

Mobs walk past on lanes; players hit them with snowballs (handed out and refilled). Some mobs, like villagers, cost points.

## Set it up

1. `/game create gallery <name>` where players appear.
2. The box where players stay: `/game edit <name> corner1` and `corner2`.
3. Lanes: `/game edit <name> lane add` at a lane's start, then `lane end` at its end (several; `lane clear`).
4. An entrance: `/game edit <name> entrance add`.

## Settings

- **Mobs walking the lanes** (the mob pool; NPCs can't walk lanes), how many at once.
- **Walking speed** at the start (15 % faster every 15 s).
- **Points per mob**: `minecraft:villager=-5`; mobs not listed give 1.

The mobs can't be hurt any other way and don't fight.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
