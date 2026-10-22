package io.github.mfuerstenau.leia.protocol.jars;

/** Whether a runtime is loaded, and if not, why (ADR 0073). */
public enum RuntimeState {
    /** Loaded, from its folder or from the jars. */
    LOADED,
    /** Its folder, or the adapter jar in it, is missing. */
    MISSING,
    /** Its folder lacks a jar that the adapter lists. */
    INCOMPLETE,
    /** Its folder is complete, but leia refuses the runtime, such as one built for another interface. */
    REFUSED,
    /** Its folder is complete, but leia has to restart to load it (ADR 0080). */
    RESTART
}
