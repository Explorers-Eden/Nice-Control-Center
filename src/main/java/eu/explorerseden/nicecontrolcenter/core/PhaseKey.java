package eu.explorerseden.nicecontrolcenter.core;

/**
 * A tick phase within one dimension ("server" for phases outside any level). The index lets
 * {@link LiveBucket} keep phase stats in an array, since a phase ends on every timed frame.
 */
public record PhaseKey(String dimension, Phase phase, int index) {
}
