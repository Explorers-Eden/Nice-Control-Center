# Slot Machines

Machines players spend points at, built from five looks (acacia, bamboo, mangrove, poplar, warped): a copper base with the lever on its side, the reel shelf on top of it, shelves behind and a trapdoor roof.

## Types

Every machine uses a type, set up on this page:

- **What a pull costs** and **how long the reels spin**.
- **Wins**: three symbols as item ids (one filled in = three of it), each with its **chance per pull** in %, its **payout** in points, optional commands, a message, and whether everyone is told.
- **Extra symbols** that only show on losing rows.

Each win has its own chance; together they're the chance of any win, the rest are losses showing a mixed row. The page shows that chance and the **return**: how much of what's put in comes back on average.

## Place one

1. `/slots place <type> [acacia|bamboo|mangrove|poplar|warped]` shows a glowing preview two blocks ahead, its front toward you.
2. Click **Place**, **Turn**, **Look**, move it a block (forward, back, left, right, up, down) or **Cancel**.

| Command | What it does |
|---|---|
| `/slots type <type>` | Changes the type of the machine next to you. |
| `/slots remove` | Takes the nearest machine away, blocks too. |
| `/slots list` | Lists every machine. |

Removing a machine on this page takes its blocks away too.

## In play

- Pulling the lever takes the cost, flicks back at once (it powers nothing) and spins the three shelf slots, which stop one after the other.
- A win pays out with a chime and sparkles; a burst for 10× the cost or more.
- Anyone can pull the lever, also in claims. Nobody can take the shelves' items or break the machine (admins in creative can).
