package com.philip.fieldtracker.data

import com.philip.fieldtracker.i18n.tr

/** Tracé du terrain dessiné à l'écran. */
enum class FieldLayout {
    SOCCER, FUTSAL, HANDBALL, RUGBY, AMERICAN, GAELIC, FIELD_HOCKEY, ICE_HOCKEY,
    BASKETBALL, BASKET_HALF, NETBALL, TENNIS, PADEL, BADMINTON, PICKLEBALL, VOLLEY, TABLE_TENNIS,
    SQUASH, BASEBALL, CRICKET, OVAL, TRACK, ULTIMATE, GENERIC, GENERIC_THIRDS
}

/** Façon de nommer les zones de présence. */
enum class ZoneMode { GOALS, NET, GENERIC }

/** Type d'exercice envoyé à Health Connect / Samsung Health. */
enum class HcType {
    SOCCER, FOOTBALL_AMERICAN, FOOTBALL_AUSTRALIAN, RUGBY, HANDBALL, BASKETBALL, VOLLEYBALL,
    TENNIS, BADMINTON, SQUASH, TABLE_TENNIS, BASEBALL, SOFTBALL, CRICKET, ICE_HOCKEY,
    FRISBEE, RUNNING, OTHER
}

data class Sport(
    val id: String,
    val name: String,
    val category: String,
    /** Dimensions de référence (m) : longueur x largeur. */
    val lengthM: Double,
    val widthM: Double,
    val layout: FieldLayout,
    val indoor: Boolean = false,
    /** Seuil "haute intensité" (km/h). */
    val hiKmh: Double = 19.8,
    /** Seuil "sprint" (km/h). */
    val sprintKmh: Double = 25.2,
    val zoneMode: ZoneMode = ZoneMode.GOALS,
    val hc: HcType = HcType.OTHER,
    /** Remarque affichée pour le balisage (formes non rectangulaires). */
    val note: String? = null,
    /**
     * Zone de jeu autour du terrain (m) : derrière les lignes de fond / sur les côtés.
     * Ex. tennis : on joue souvent derrière la ligne de fond.
     */
    val marginLM: Double = 0.0,
    val marginWM: Double = 0.0,
) {
    val dims: String
        get() = "${fmt(lengthM)} × ${fmt(widthM)} m"

    private fun fmt(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.1f", d)

    fun zoneLabelsLength(): List<String> = when (zoneMode) {
        ZoneMode.GOALS -> listOf("Défense", "Milieu", "Attaque")
        ZoneMode.NET -> listOf("Fond A", "Zone filet", "Fond B")
        ZoneMode.GENERIC -> listOf("Zone 1", "Zone 2", "Zone 3")
    }.map { tr(it) }

    fun zoneLabelsWidth(): List<String> = listOf("Gauche", "Axe", "Droite").map { tr(it) }
}

object Sports {
    const val CAT_FOOT = "Football"
    const val CAT_OVAL = "Rugby & ovale"
    const val CAT_STICK = "Crosse & bâton"
    const val CAT_HAND = "Ballon à la main"
    const val CAT_NET = "Raquette & filet"
    const val CAT_BAT = "Batte"
    const val CAT_OTHER = "Autres terrains"

    val categories = listOf(CAT_FOOT, CAT_OVAL, CAT_STICK, CAT_HAND, CAT_NET, CAT_BAT, CAT_OTHER)

    private const val OUTDOOR_TIP = "Coins = les 4 angles du rectangle qui entoure le terrain."

    val all: List<Sport> = listOf(
        // ---- Football
        Sport("football11", "Football (à 11)", CAT_FOOT, 105.0, 68.0, FieldLayout.SOCCER, hc = HcType.SOCCER),
        Sport("football8", "Football à 8", CAT_FOOT, 60.0, 45.0, FieldLayout.SOCCER, hiKmh = 18.0, sprintKmh = 24.0, hc = HcType.SOCCER),
        Sport("football7", "Football à 7", CAT_FOOT, 50.0, 35.0, FieldLayout.SOCCER, hiKmh = 18.0, sprintKmh = 24.0, hc = HcType.SOCCER),
        Sport("foot5", "Foot à 5 (five)", CAT_FOOT, 40.0, 20.0, FieldLayout.FUTSAL, hiKmh = 16.0, sprintKmh = 22.0, hc = HcType.SOCCER),
        Sport("futsal", "Futsal", CAT_FOOT, 40.0, 20.0, FieldLayout.FUTSAL, indoor = true, hiKmh = 16.0, sprintKmh = 22.0, hc = HcType.SOCCER, marginLM = 1.0, marginWM = 1.0),
        Sport("beachsoccer", "Beach soccer", CAT_FOOT, 36.0, 27.0, FieldLayout.GENERIC, hiKmh = 14.0, sprintKmh = 19.0, hc = HcType.SOCCER),
        Sport("footus", "Football américain", CAT_FOOT, 109.7, 48.8, FieldLayout.AMERICAN, hc = HcType.FOOTBALL_AMERICAN),
        Sport("footca", "Football canadien", CAT_FOOT, 137.0, 59.0, FieldLayout.AMERICAN, hc = HcType.FOOTBALL_AMERICAN),
        Sport("flagfootball", "Flag football", CAT_FOOT, 64.0, 27.4, FieldLayout.AMERICAN, hiKmh = 18.0, sprintKmh = 24.0, hc = HcType.FOOTBALL_AMERICAN),

        // ---- Rugby & ovale
        Sport("rugby15", "Rugby à XV", CAT_OVAL, 120.0, 70.0, FieldLayout.RUGBY, hc = HcType.RUGBY),
        Sport("rugby13", "Rugby à XIII", CAT_OVAL, 120.0, 68.0, FieldLayout.RUGBY, hc = HcType.RUGBY),
        Sport("rugby7", "Rugby à 7", CAT_OVAL, 120.0, 70.0, FieldLayout.RUGBY, hc = HcType.RUGBY),
        Sport("touchrugby", "Touch rugby", CAT_OVAL, 70.0, 50.0, FieldLayout.GENERIC_THIRDS, hiKmh = 18.0, sprintKmh = 24.0, hc = HcType.RUGBY),
        Sport("aussie", "Football australien", CAT_OVAL, 165.0, 135.0, FieldLayout.OVAL, hc = HcType.FOOTBALL_AUSTRALIAN, note = OUTDOOR_TIP),
        Sport("gaelic", "Football gaélique", CAT_OVAL, 140.0, 85.0, FieldLayout.GAELIC),
        Sport("hurling", "Hurling / Camogie", CAT_OVAL, 140.0, 85.0, FieldLayout.GAELIC),

        // ---- Crosse & bâton
        Sport("hockeygazon", "Hockey sur gazon", CAT_STICK, 91.4, 55.0, FieldLayout.FIELD_HOCKEY, hiKmh = 19.0, sprintKmh = 24.0),
        Sport("lacrosse", "Lacrosse", CAT_STICK, 100.0, 55.0, FieldLayout.GENERIC_THIRDS),
        Sport("hockeyglace", "Hockey sur glace", CAT_STICK, 60.0, 30.0, FieldLayout.ICE_HOCKEY, indoor = true, hiKmh = 22.0, sprintKmh = 28.0, hc = HcType.ICE_HOCKEY),
        Sport("rollerhockey", "Roller hockey", CAT_STICK, 40.0, 20.0, FieldLayout.ICE_HOCKEY, indoor = true, hiKmh = 18.0, sprintKmh = 24.0),
        Sport("floorball", "Floorball / Unihockey", CAT_STICK, 40.0, 20.0, FieldLayout.ICE_HOCKEY, indoor = true, hiKmh = 16.0, sprintKmh = 21.0),
        Sport("bandy", "Bandy", CAT_STICK, 105.0, 65.0, FieldLayout.GENERIC, hiKmh = 24.0, sprintKmh = 30.0),

        // ---- Ballon à la main
        Sport("handball", "Handball", CAT_HAND, 40.0, 20.0, FieldLayout.HANDBALL, indoor = true, hiKmh = 16.0, sprintKmh = 21.0, hc = HcType.HANDBALL, marginLM = 1.0, marginWM = 1.0),
        Sport("beachhandball", "Beach handball", CAT_HAND, 27.0, 12.0, FieldLayout.HANDBALL, hiKmh = 13.0, sprintKmh = 18.0, hc = HcType.HANDBALL, marginLM = 2.0, marginWM = 2.0),
        Sport("basketball", "Basketball", CAT_HAND, 28.0, 15.0, FieldLayout.BASKETBALL, indoor = true, hiKmh = 14.0, sprintKmh = 18.0, hc = HcType.BASKETBALL, marginLM = 2.0, marginWM = 2.0),
        Sport("basket3x3", "Basket 3x3 (extérieur)", CAT_HAND, 15.0, 11.0, FieldLayout.BASKET_HALF, hiKmh = 13.0, sprintKmh = 17.0, hc = HcType.BASKETBALL, marginLM = 2.0, marginWM = 2.0),
        Sport("netball", "Netball", CAT_HAND, 30.5, 15.25, FieldLayout.NETBALL, hiKmh = 14.0, sprintKmh = 18.0, marginLM = 2.0, marginWM = 2.0),
        Sport("korfball", "Korfball", CAT_HAND, 40.0, 20.0, FieldLayout.GENERIC, indoor = true, hiKmh = 14.0, sprintKmh = 18.0, marginLM = 2.0, marginWM = 2.0),
        Sport("ultimate", "Ultimate frisbee", CAT_HAND, 100.0, 37.0, FieldLayout.ULTIMATE, hiKmh = 18.0, sprintKmh = 24.0, hc = HcType.FRISBEE),
        Sport("tchoukball", "Tchoukball", CAT_HAND, 40.0, 20.0, FieldLayout.GENERIC, indoor = true, hiKmh = 14.0, sprintKmh = 18.0, marginLM = 2.0, marginWM = 2.0),
        Sport("kabaddi", "Kabaddi", CAT_HAND, 13.0, 10.0, FieldLayout.GENERIC, hiKmh = 12.0, sprintKmh = 16.0, marginLM = 2.0, marginWM = 2.0),
        Sport("dodgeball", "Balle au prisonnier / Dodgeball", CAT_HAND, 18.0, 9.0, FieldLayout.VOLLEY, hiKmh = 12.0, sprintKmh = 16.0, zoneMode = ZoneMode.GENERIC, marginLM = 2.0, marginWM = 2.0),

        // ---- Raquette & filet
        Sport("tennis", "Tennis", CAT_NET, 23.77, 10.97, FieldLayout.TENNIS, hiKmh = 12.0, sprintKmh = 16.0, zoneMode = ZoneMode.NET, hc = HcType.TENNIS, marginLM = 6.4, marginWM = 3.66),
        Sport("padel", "Padel", CAT_NET, 20.0, 10.0, FieldLayout.PADEL, hiKmh = 11.0, sprintKmh = 15.0, zoneMode = ZoneMode.NET),
        Sport("badminton", "Badminton", CAT_NET, 13.4, 6.1, FieldLayout.BADMINTON, indoor = true, hiKmh = 10.0, sprintKmh = 14.0, zoneMode = ZoneMode.NET, hc = HcType.BADMINTON, marginLM = 2.0, marginWM = 2.0),
        Sport("pickleball", "Pickleball", CAT_NET, 13.41, 6.1, FieldLayout.PICKLEBALL, hiKmh = 10.0, sprintKmh = 14.0, zoneMode = ZoneMode.NET, marginLM = 3.0, marginWM = 2.0),
        Sport("volleyball", "Volley-ball", CAT_NET, 18.0, 9.0, FieldLayout.VOLLEY, indoor = true, hiKmh = 10.0, sprintKmh = 14.0, zoneMode = ZoneMode.NET, hc = HcType.VOLLEYBALL, marginLM = 3.0, marginWM = 3.0),
        Sport("beachvolley", "Beach-volley", CAT_NET, 16.0, 8.0, FieldLayout.VOLLEY, hiKmh = 10.0, sprintKmh = 14.0, zoneMode = ZoneMode.NET, hc = HcType.VOLLEYBALL, marginLM = 3.0, marginWM = 3.0),
        Sport("footvolley", "Footvolley", CAT_NET, 18.0, 9.0, FieldLayout.VOLLEY, hiKmh = 10.0, sprintKmh = 14.0, zoneMode = ZoneMode.NET, marginLM = 3.0, marginWM = 3.0),
        Sport("sepaktakraw", "Sepak takraw", CAT_NET, 13.4, 6.1, FieldLayout.VOLLEY, hiKmh = 10.0, sprintKmh = 14.0, zoneMode = ZoneMode.NET, marginLM = 3.0, marginWM = 3.0),
        Sport("tennisdetable", "Tennis de table", CAT_NET, 2.74, 1.525, FieldLayout.TABLE_TENNIS, indoor = true, hiKmh = 7.0, sprintKmh = 10.0, zoneMode = ZoneMode.NET, hc = HcType.TABLE_TENNIS, note = "Coins = les 4 coins de la table.", marginLM = 5.6, marginWM = 2.74),
        Sport("squash", "Squash", CAT_NET, 9.75, 6.4, FieldLayout.SQUASH, indoor = true, hiKmh = 9.0, sprintKmh = 13.0, zoneMode = ZoneMode.GENERIC, hc = HcType.SQUASH),

        // ---- Batte
        Sport("baseball", "Baseball", CAT_BAT, 120.0, 120.0, FieldLayout.BASEBALL, hiKmh = 18.0, sprintKmh = 24.0, zoneMode = ZoneMode.GENERIC, hc = HcType.BASEBALL, note = OUTDOOR_TIP),
        Sport("softball", "Softball", CAT_BAT, 70.0, 70.0, FieldLayout.BASEBALL, hiKmh = 17.0, sprintKmh = 23.0, zoneMode = ZoneMode.GENERIC, hc = HcType.SOFTBALL, note = OUTDOOR_TIP),
        Sport("cricket", "Cricket", CAT_BAT, 150.0, 130.0, FieldLayout.CRICKET, hiKmh = 18.0, sprintKmh = 24.0, zoneMode = ZoneMode.GENERIC, hc = HcType.CRICKET, note = OUTDOOR_TIP),

        // ---- Autres
        Sport("athletisme", "Athlétisme – piste 400 m", CAT_OTHER, 176.9, 92.5, FieldLayout.TRACK, hiKmh = 20.0, sprintKmh = 28.0, zoneMode = ZoneMode.GENERIC, hc = HcType.RUNNING, note = OUTDOOR_TIP),
        Sport("libre", "Terrain libre / personnalisé", CAT_OTHER, 50.0, 30.0, FieldLayout.GENERIC, hiKmh = 18.0, sprintKmh = 24.0, zoneMode = ZoneMode.GENERIC),
    )

    fun byId(id: String?): Sport? = all.firstOrNull { it.id == id }
}
