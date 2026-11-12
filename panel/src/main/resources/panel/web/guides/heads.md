# Head hunt

Heads with your own skin hidden around the world. Players right-click them to find them, with a reward every few heads and one for finding them all.

## Set it up

1. `/game create heads <name>`.
2. The skin: `/game edit <name> texture <skin url or value>`, or the field on the card.
3. Take heads with `/game edit <name> headitem [count]` and place them; each one placed joins the hunt.
4. `/game edit <name> head add` / `head remove` for a player head you look at.

Break one in creative (as an admin) to take it out; players can't break them. Players check how far they are with `/game join <name>`.

## Rewards

- **A reward every … heads** with commands (`{player}`, `{found}`, `{total}`), also the Rewards trigger "Finds hidden heads".
- Commands and a message when all heads are found, and "Finishes a game".

The card lists where every head is, and can take one out of the hunt.
