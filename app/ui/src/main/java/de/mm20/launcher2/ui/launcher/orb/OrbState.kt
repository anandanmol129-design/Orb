package de.mm20.launcher2.ui.launcher.orb

/**
 * Visual/behavioural state of the centre sphere. Drives both the eye animation and the
 * glow so the user can tell what the assistant is doing at a glance.
 */
enum class OrbState {
    /** Resting: subtle idle animation, occasional blink. */
    Idle,

    /** Microphone is open and speech is being captured. */
    Listening,

    /** A command is being interpreted. */
    Thinking,

    /** Last command succeeded. */
    Success,

    /** Last command failed or was not understood. */
    Error,

    /** The radial menu is open; pupils track the finger. */
    Pie,

    /** Dormant: dimmed and unresponsive until woken with a triple tap. */
    Sleep,
}
