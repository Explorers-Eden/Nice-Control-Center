package eu.explorerseden.nicecontrolcenter.core;

/**
 * Parts of a server tick. Labels and descriptions follow the explorerseden.eu Profiling Inspector
 * so both read the same.
 */
public enum Phase {
	FUNCTIONS("Data pack functions", "Functions in #minecraft:tick run by data packs every tick"),
	WORLD("World upkeep", "Weather, time, world border, scheduled functions and sleeping"),
	SCHEDULED_TICKS("Pending block ticks", "Queued block and fluid updates (redstone, water, etc.)"),
	RAIDS("Raid logic", "Raid spawning and tracking"),
	CHUNKS("Chunk management", "Loading, saving and generating chunks, random ticks, mob spawning"),
	BLOCK_EVENTS("Block events", "Piston extensions, note blocks, etc."),
	ENTITIES("Mob processing", "Entity AI, pathfinding, and movement"),
	BLOCK_ENTITIES("Block entities", "Furnaces, hoppers, pistons, and other ticking blocks"),
	ENTITY_MANAGEMENT("Entity management", "Loading and unloading entities with their chunks"),
	CONNECTION("Network & console", "Handling packets from players and console commands"),
	PLAYERS("Player handling", "Processing player actions and movement"),
	AUTOSAVE("Autosave", "Periodic world saving"),
	OTHER("Other", "Everything else the server does during a tick");

	public final String label;
	public final String description;

	Phase(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public static final Phase[] VALUES = values();
}
