# Ghast deflect

Ghasts float above the arena and shoot fireballs. Players hit them back with snowballs (handed out and refilled); every ghast they bring down counts.

## Set it up

1. Stand where players appear: `/game create ghasts <name>`.
2. The play box: `/game edit <name> corner1` and `corner2`.
3. Ghast spawns up high: `/game edit <name> mobspawn add` (several).
4. An entrance: `/game edit <name> entrance add`.

## Settings

- **Ghasts** in the mob pool; `{ExplosionPower:0}` in their data keeps the arena whole.
- **Ghasts at once**, round length, players at most.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
