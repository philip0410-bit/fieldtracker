package com.philip.fieldtracker.data

/**
 * Poste de jeu et sa zone habituelle, en coordonnées "sens d'attaque" :
 * u = 0 ta ligne de but → 1 la ligne adverse ; v = 0 côté gauche → 1 côté droit.
 */
data class PlayPosition(
    val id: String,
    val name: String,
    val uMin: Double, val uMax: Double,
    val vMin: Double, val vMax: Double,
    /** Référence d'intensité (m/min) au haut niveau, si connue. */
    val refMPerMin: Double? = null,
)

object Positions {
    private val FOOT = listOf(
        PlayPosition("gk", "Gardien", 0.0, 0.22, 0.2, 0.8, 55.0),
        PlayPosition("cb", "Défenseur central", 0.0, 0.5, 0.2, 0.8, 105.0),
        PlayPosition("lb", "Latéral gauche", 0.0, 0.7, 0.0, 0.35, 113.0),
        PlayPosition("rb", "Latéral droit", 0.0, 0.7, 0.65, 1.0, 113.0),
        PlayPosition("dm", "Milieu défensif", 0.15, 0.6, 0.2, 0.8, 117.0),
        PlayPosition("cm", "Milieu central", 0.25, 0.75, 0.15, 0.85, 120.0),
        PlayPosition("am", "Milieu offensif", 0.45, 0.9, 0.2, 0.8, 117.0),
        PlayPosition("lw", "Ailier gauche", 0.4, 1.0, 0.0, 0.35, 115.0),
        PlayPosition("rw", "Ailier droit", 0.4, 1.0, 0.65, 1.0, 115.0),
        PlayPosition("st", "Attaquant", 0.55, 1.0, 0.2, 0.8, 110.0),
    )
    private val FUTSAL = listOf(
        PlayPosition("gk", "Gardien", 0.0, 0.25, 0.15, 0.85),
        PlayPosition("fixo", "Défenseur (fixo)", 0.0, 0.55, 0.15, 0.85),
        PlayPosition("lw", "Ailier gauche", 0.2, 0.85, 0.0, 0.45),
        PlayPosition("rw", "Ailier droit", 0.2, 0.85, 0.55, 1.0),
        PlayPosition("pivot", "Pivot", 0.5, 1.0, 0.15, 0.85),
    )
    private val RUGBY = listOf(
        PlayPosition("front", "Pilier / Talonneur", 0.15, 0.85, 0.2, 0.8),
        PlayPosition("lock", "Deuxième ligne", 0.15, 0.85, 0.15, 0.85),
        PlayPosition("flank", "Troisième ligne", 0.1, 0.9, 0.1, 0.9),
        PlayPosition("sh", "Demi de mêlée", 0.1, 0.9, 0.15, 0.85),
        PlayPosition("fh", "Demi d'ouverture", 0.05, 0.85, 0.15, 0.85),
        PlayPosition("centre", "Centre", 0.1, 0.9, 0.15, 0.85),
        PlayPosition("wing_l", "Ailier gauche", 0.05, 1.0, 0.0, 0.3),
        PlayPosition("wing_r", "Ailier droit", 0.05, 1.0, 0.7, 1.0),
        PlayPosition("fb", "Arrière", 0.0, 0.7, 0.1, 0.9),
    )
    private val HOCKEY = listOf(
        PlayPosition("gk", "Gardien", 0.0, 0.2, 0.25, 0.75),
        PlayPosition("cb", "Défenseur central", 0.0, 0.45, 0.2, 0.8),
        PlayPosition("lb", "Défenseur gauche", 0.0, 0.6, 0.0, 0.4),
        PlayPosition("rb", "Défenseur droit", 0.0, 0.6, 0.6, 1.0),
        PlayPosition("cm", "Milieu", 0.25, 0.75, 0.1, 0.9),
        PlayPosition("lw", "Ailier gauche", 0.45, 1.0, 0.0, 0.4),
        PlayPosition("rw", "Ailier droit", 0.45, 1.0, 0.6, 1.0),
        PlayPosition("st", "Attaquant", 0.55, 1.0, 0.2, 0.8),
    )
    private val HAND = listOf(
        PlayPosition("gk", "Gardien", 0.0, 0.18, 0.25, 0.75),
        PlayPosition("lw", "Ailier gauche", 0.0, 1.0, 0.0, 0.25),
        PlayPosition("lb", "Arrière gauche", 0.0, 1.0, 0.1, 0.45),
        PlayPosition("cb", "Demi-centre", 0.0, 1.0, 0.3, 0.7),
        PlayPosition("rb", "Arrière droit", 0.0, 1.0, 0.55, 0.9),
        PlayPosition("rw", "Ailier droit", 0.0, 1.0, 0.75, 1.0),
        PlayPosition("pivot", "Pivot", 0.0, 1.0, 0.3, 0.7),
    )
    private val BASKET = listOf(
        PlayPosition("pg", "Meneur", 0.0, 1.0, 0.15, 0.85),
        PlayPosition("sg", "Arrière shooteur", 0.0, 1.0, 0.0, 1.0),
        PlayPosition("sf", "Ailier", 0.0, 1.0, 0.0, 1.0),
        PlayPosition("pf", "Ailier fort", 0.0, 1.0, 0.15, 0.85),
        PlayPosition("c", "Pivot", 0.0, 1.0, 0.25, 0.75),
    )

    fun forSport(sportId: String): List<PlayPosition> = when (sportId) {
        "football11", "football8", "football7" -> FOOT
        "foot5", "futsal", "beachsoccer" -> FUTSAL
        "rugby15", "rugby13", "rugby7", "touchrugby" -> RUGBY
        "hockeygazon" -> HOCKEY
        "handball", "beachhandball" -> HAND
        "basketball", "basket3x3" -> BASKET
        else -> emptyList()
    }

    fun find(sportId: String, id: String?): PlayPosition? = forSport(sportId).firstOrNull { it.id == id }
}

/** But marqué : team 0 = mon équipe, 1 = adversaire. */
data class Goal(val t: Long, val team: Int)
