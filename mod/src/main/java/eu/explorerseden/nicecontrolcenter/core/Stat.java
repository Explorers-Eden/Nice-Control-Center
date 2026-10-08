package eu.explorerseden.nicecontrolcenter.core;

/** Mutable timing counter, only touched on the server thread. */
public class Stat {
	public static final byte ENTITY = 1;
	public static final byte BLOCK_ENTITY = 2;
	public static final byte FUNCTION = 3;

	/** Time spent in this frame minus time spent in nested frames. */
	public long selfNs;
	/** Time spent in this frame including nested frames. */
	public long totalNs;
	public long calls;

	/** What this measures, for naming it in lag spikes: kind plus entity type / block entity type / function id. */
	public byte kind;
	public Object key;
	/** Self time within the current tick; reset when {@link #tickId} is stale. */
	long tickNs;
	int tickId = -1;

	public Stat() {
	}

	public Stat(byte kind, Object key) {
		this.kind = kind;
		this.key = key;
	}
}
