package com.philip.fieldtracker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** État global de l'app partagé entre l'interface, le service de suivi et la montre. */
object AppState {
    private lateinit var prefs: SharedPreferences
    lateinit var appContext: Context
        private set

    private val _sport = MutableStateFlow<Sport?>(null)
    val sport: StateFlow<Sport?> = _sport

    private val _theme = MutableStateFlow("system")
    val theme: StateFlow<String> = _theme

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile

    private val _outAlert = MutableStateFlow(true)
    val outAlert: StateFlow<Boolean> = _outAlert

    /** "watch" (GPS de la montre), "gps" (GPS du téléphone) ou "pdr" (sans GPS : podomètre + boussole). */
    private val _positionMode = MutableStateFlow("watch")
    val positionMode: StateFlow<String> = _positionMode

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

    /** Session à afficher dans l'onglet Analyse. */
    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId

    /** Incrémenté à chaque modification de l'historique. */
    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        appContext = ctx.applicationContext
        prefs = ctx.getSharedPreferences("fieldtracker", Context.MODE_PRIVATE)
        _sport.value = Sports.byId(prefs.getString("sport", null))
        _theme.value = prefs.getString("theme", "system") ?: "system"
        _outAlert.value = prefs.getBoolean("outAlert", true)
        _keepScreenOn.value = prefs.getBoolean("keepScreenOn", true)
        // Nouvelle version : la montre devient la source par défaut
        if (!prefs.getBoolean("posModeV2", false)) prefs.edit().putString("positionMode", "watch").putBoolean("posModeV2", true).apply()
        _positionMode.value = prefs.getString("positionMode", "watch") ?: "watch"
        _profile.value = Profile(
            age = prefs.getInt("age", 30),
            weightKg = prefs.getFloat("weight", 75f).toDouble(),
            male = prefs.getBoolean("male", true),
            hrMaxOverride = prefs.getInt("hrMax", 0),
        )
        _selectedSessionId.value = prefs.getString("lastSession", null)
        val device = java.util.Locale.getDefault().language
        val defLang = if (device == "ko" || device == "en") device else "fr"
        com.philip.fieldtracker.i18n.I18n.setLang(prefs.getString("lang", defLang) ?: defLang)
    }

    fun setPositionMode(m: String) {
        _positionMode.value = m
        prefs.edit().putString("positionMode", m).apply()
        com.philip.fieldtracker.watch.WatchLink.pushConfig()
    }

    fun setLang(l: String) {
        com.philip.fieldtracker.i18n.I18n.setLang(l)
        prefs.edit().putString("lang", l).apply()
        com.philip.fieldtracker.watch.WatchLink.pushState(force = true)
        com.philip.fieldtracker.watch.WatchLink.pushConfig()
    }

    fun selectSport(s: Sport) {
        _sport.value = s
        prefs.edit().putString("sport", s.id).apply()
        com.philip.fieldtracker.watch.WatchLink.pushConfig()
    }

    fun setTheme(id: String) {
        _theme.value = id
        prefs.edit().putString("theme", id).apply()
    }

    fun setProfile(p: Profile) {
        _profile.value = p
        prefs.edit().putInt("age", p.age).putFloat("weight", p.weightKg.toFloat())
            .putBoolean("male", p.male).putInt("hrMax", p.hrMaxOverride).apply()
    }

    fun setOutAlert(on: Boolean) {
        _outAlert.value = on
        prefs.edit().putBoolean("outAlert", on).apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        prefs.edit().putBoolean("keepScreenOn", on).apply()
    }

    fun selectSession(id: String?) {
        _selectedSessionId.value = id
        prefs.edit().putString("lastSession", id).apply()
    }

    fun historyChanged() {
        _historyVersion.value = _historyVersion.value + 1
    }

    /** Dernier balisage enregistré pour un sport (pour le réutiliser au même endroit). */
    fun lastCalibration(sportId: String): Calibration? = try {
        prefs.getString("cal_$sportId", null)?.let { Calibration.fromJson(JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    fun saveCalibration(sportId: String, c: Calibration) {
        prefs.edit().putString("cal_$sportId", c.toJson().toString()).apply()
    }
}
