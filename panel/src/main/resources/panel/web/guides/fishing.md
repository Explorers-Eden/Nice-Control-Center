# Fishing frenzy

Every catch scores points: fish, rare fish, treasure, even junk. A fishing rod is handed out.

## Set it up

1. `/game create fishing <name>` where players appear.
2. The box with water inside: `/game edit <name> corner1` and `corner2`.
3. An entrance: `/game edit <name> entrance add`.

## Settings

- **Points** for cod and salmon, pufferfish and tropical fish, treasure, junk.

> Fish bite within a few seconds. Bubbling spots come and go on the water, and a hook in one gets a bite almost at once.

## How quick rounds work

- Everyone who steps in during the **join window** (10 s by default) plays the same round, each for their own score.
- Nobody dies: a deadly hit knocks them out (their score still counts).
- Players who step in during a round are queued for the next one, in the spectator area when there is one.
- Their own things are put aside and given back afterwards (the **kit** option), or they play with their own gear.
- With two or more players the winner gets the winner commands and the Rewards trigger "Wins a round".
- Test it with `/game try <name>`: a round with only you.
