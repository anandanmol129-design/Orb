package de.mm20.launcher2.orb

/**
 * What a slot of the media ring (the extra ring of controls around the pie while music plays)
 * does when the finger is released on it.
 */
enum class MediaRingAction(val label: String) {
    None("Empty"),
    OpenApp("Open player"),
    PlayPause("Play / pause"),
    Previous("Previous"),
    Next("Next"),
    Rewind("Skip back"),
    Forward("Skip forward"),
}

/**
 * The eight fixed positions around the orb a media control can sit at, clockwise from the top.
 * [angleDeg] is measured clockwise from the right (screen coordinates).
 */
enum class MediaRingSlot(val label: String, val angleDeg: Float) {
    Top("Top", -90f),
    TopRight("Top right", -45f),
    Right("Right", 0f),
    BottomRight("Bottom right", 45f),
    Bottom("Bottom", 90f),
    BottomLeft("Bottom left", 135f),
    Left("Left", 180f),
    TopLeft("Top left", -135f),
}

/** Skip durations offered for the skip back / skip forward controls, in seconds. */
val MediaSkipOptions = listOf(5, 10, 15, 30, 60)
