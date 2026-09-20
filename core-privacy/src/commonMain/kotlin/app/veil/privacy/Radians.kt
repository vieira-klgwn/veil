package app.veil.privacy

import kotlin.math.PI

/** Degrees to radians, without the JVM only `Math` class. */
internal fun radians(degrees: Float): Double = degrees.toDouble() * PI / 180.0
