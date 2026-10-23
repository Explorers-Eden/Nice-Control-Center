package eu.explorerseden.nicecontrolcenter.core;

/** A hopper waiting after finding nothing to move (Performance → hoppers); see HopperMixin. */
public interface IdleHopper {
	/** Ends the wait so it looks again next tick; no effect on vanilla's cooldown after a move. */
	void nicecontrolcenter$wake();
}
