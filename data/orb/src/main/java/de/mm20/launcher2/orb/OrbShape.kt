package de.mm20.launcher2.orb

/**
 * The body shape of the centre sphere.
 */
enum class OrbShape(val label: String) {
    Circle("Circle"),
    Cloud("Cloud"),
    Triangle("Triangle"),
    Hexagon("Hexagon"),
    Teardrop("Teardrop"),
    Pill("Pill"),
    Square("Square"),
    Pebble("Pebble"),
}

/**
 * How the body shape is painted: a solid body or a hollow outline.
 */
enum class OrbFill(val label: String) {
    Filled("Filled"),
    Outline("Outline"),
}
