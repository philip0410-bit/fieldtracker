package com.philip.fieldtracker.ui.screens

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.data.Profile
import com.philip.fieldtracker.data.SessionStore
import com.philip.fieldtracker.data.Sport
import com.philip.fieldtracker.data.Sports
import com.philip.fieldtracker.data.StatsCalculator
import com.philip.fieldtracker.health.HealthLink
import com.philip.fieldtracker.trackers.BleHeartRate
import com.philip.fieldtracker.trackers.TrackerImport
import com.philip.fieldtracker.tracking.Calibrator
import com.philip.fieldtracker.ui.components.BigButton
import com.philip.fieldtracker.ui.components.Chip
import com.philip.fieldtracker.ui.components.FieldCanvas
import com.philip.fieldtracker.ui.components.GhostButton
import com.philip.fieldtracker.ui.components.GpsPanel
import com.philip.fieldtracker.ui.components.IconSquare
import com.philip.fieldtracker.ui.components.Label
import com.philip.fieldtracker.ui.components.Panel
import com.philip.fieldtracker.ui.components.Pill
import com.philip.fieldtracker.ui.components.SectionTitle
import com.philip.fieldtracker.ui.components.Shape12
import com.philip.fieldtracker.ui.components.Shape8
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Palettes
import com.philip.fieldtracker.ui.theme.Type
import com.philip.fieldtracker.watch.WatchLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(onNext: () -> Unit, onOpenStats: () -> Unit) {
    val p = LocalPalette.current
    val selected by AppState.sport.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        GpsPanel("setup-panel")
        Spacer(Modifier.height(20.dp))
        SportSection(selected)
        selected?.let { PositionSection(it) }
        Spacer(Modifier.height(20.dp))
        HardwareSection(onOpenStats)
        Spacer(Modifier.height(20.dp))
        LanguageSection()
        Spacer(Modifier.height(20.dp))
        DisplaySection()
        Spacer(Modifier.height(20.dp))
        ProfileSection()
        Spacer(Modifier.height(20.dp))
        BigButton(
            text = if (selected != null) tr("Passer au balisage") else tr("Choisis un sport"),
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            enabled = selected != null,
            onClick = {
                selected?.let { Calibrator.prepare(it) }
                onNext()
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Le terrain se balise en te plaçant sur ses 4 coins."),
            style = Type.caption, color = p.muted, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )
    }
}

// ------------------------------------------------------------------ Matériel

@Composable
private fun HardwareSection(onOpenStats: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nodes by WatchLink.nodes.collectAsState()
    val bleStatus by BleHeartRate.status.collectAsState()
    val bleFound by BleHeartRate.found.collectAsState()
    val bleName by BleHeartRate.deviceName.collectAsState()
    var hcGranted by remember { mutableStateOf<Boolean?>(null) }
    val hcAvailable = remember { HealthLink.isAvailable(ctx) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        WatchLink.refresh()
        if (hcAvailable) hcGranted = HealthLink.hasAllPermissions(ctx)
    }

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        hcGranted = granted.containsAll(HealthLink.permissions)
    }
    val blePerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) BleHeartRate.startScan(ctx)
        else Toast.makeText(ctx, tr("Autorisation Bluetooth refusée"), Toast.LENGTH_SHORT).show()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val sport = AppState.sport.value
        if (sport == null) {
            Toast.makeText(ctx, tr("Choisis d'abord le sport de la séance importée"), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        importing = true
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val (name, raw) = TrackerImport.read(ctx, uri)
                    // Réutilise le balisage du sport s'il correspond au lieu de la séance
                    val cal = (Calibrator.calibration.value ?: AppState.lastCalibration(sport.id))?.takeIf { c ->
                        val inside = raw.count { c.isInside(c.toUV(com.philip.fieldtracker.data.LatLon(it.lat, it.lon)), 5.0) }
                        inside > raw.size / 2
                    }
                    val session = TrackerImport.toSession(sport, name, raw, cal)
                    val stats = StatsCalculator.compute(session, AppState.profile.value)
                    SessionStore.save(ctx, session, stats)
                    AppState.selectSession(session.id)
                    AppState.historyChanged()
                    null
                }
            } catch (e: Exception) {
                e.message ?: tr("Import impossible")
            }
            importing = false
            if (msg == null) onOpenStats() else Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    SectionTitle(tr("Matériel connecté"), "${(if (nodes.isNotEmpty()) 1 else 0) + (if (bleStatus == BleHeartRate.Status.CONNECTED) 1 else 0)} actif(s)")

    // Montre
    Panel(accent = if (nodes.isNotEmpty()) p.primary.copy(alpha = 0.6f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(Icons.Filled.Watch, if (nodes.isNotEmpty()) p.primary else p.muted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nodes.firstOrNull()?.name ?: tr("Montre Wear OS"), style = Type.bodyBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Label(if (nodes.isNotEmpty()) tr("Connectée · balisage + FC au poignet") else tr("Aucune montre connectée"), style = Type.caption)
            }
            IconSquare(Icons.Filled.Refresh, p.muted) { WatchLink.refresh() }
        }
        if (nodes.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Installe « FieldTracker montre » sur ta Galaxy Watch (Wear OS) puis appuie sur actualiser."),
                style = Type.body, color = p.muted
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    // Capteur cardio BLE
    Panel(accent = if (bleStatus == BleHeartRate.Status.CONNECTED) p.primary.copy(alpha = 0.6f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(Icons.Filled.Bluetooth, if (bleStatus == BleHeartRate.Status.CONNECTED) p.primary else p.muted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (bleName.isNotEmpty()) bleName else tr("Capteur cardio Bluetooth"), style = Type.bodyBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Label(
                    when (bleStatus) {
                        BleHeartRate.Status.IDLE -> tr("Ceinture, brassard ou traqueur (profil FC)")
                        BleHeartRate.Status.SCANNING -> tr("Recherche…")
                        BleHeartRate.Status.CONNECTING -> tr("Connexion…")
                        BleHeartRate.Status.CONNECTED -> tr("Connecté")
                        BleHeartRate.Status.ERROR -> tr("Bluetooth indisponible / désactivé")
                    }, style = Type.caption
                )
            }
            if (bleStatus == BleHeartRate.Status.CONNECTED || bleStatus == BleHeartRate.Status.CONNECTING) {
                GhostButton(tr("Déconnecter"), onClick = { BleHeartRate.disconnect() })
            } else {
                GhostButton(tr("Rechercher"), onClick = {
                    val perms = if (Build.VERSION.SDK_INT >= 31)
                        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    blePerms.launch(perms)
                })
            }
        }
        if (bleFound.isNotEmpty() && bleStatus != BleHeartRate.Status.CONNECTED) {
            Spacer(Modifier.height(8.dp))
            bleFound.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Shape8)
                        .clickable { BleHeartRate.connect(ctx, f) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.FavoriteBorder, null, tint = p.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(f.name, style = Type.body, color = p.text, modifier = Modifier.weight(1f))
                    Label("${f.rssi} dBm", style = Type.caption)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // Samsung Health
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(Icons.Filled.FavoriteBorder, if (hcGranted == true) p.primary else p.muted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Samsung Health"), style = Type.bodyBold, color = p.text)
                Label(
                    when {
                        !hcAvailable -> tr("Health Connect non installé")
                        hcGranted == true -> tr("Autorisé via Health Connect")
                        else -> tr("Via Health Connect — non autorisé")
                    }, style = Type.caption
                )
            }
            if (hcAvailable && hcGranted != true) GhostButton(tr("Autoriser"), onClick = { hcLauncher.launch(HealthLink.permissions) })
        }
    }
    Spacer(Modifier.height(10.dp))

    // Traqueurs GPS pro
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(Icons.Filled.FileUpload, p.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Traqueur GPS (APEX, MyCoach…)"), style = Type.bodyBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Label(tr("Import de l'export CSV, GPX ou TCX"), style = Type.caption)
            }
            GhostButton(if (importing) tr("Import…") else tr("Importer"), onClick = { if (!importing) picker.launch(arrayOf("*/*")) })
        }
    }
}

// ------------------------------------------------------------------ Sports

@Composable
private fun SportSection(selected: Sport?) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val list = Sports.all.filter { s ->
        (category == null || s.category == category) &&
            (query.isBlank() || s.name.contains(query.trim(), ignoreCase = true) || tr(s.name).contains(query.trim(), ignoreCase = true))
    }

    var expanded by rememberSaveable { mutableStateOf(selected == null) }

    // En-tête repliable
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Shape8)
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Choix du sport"), style = Type.h2, color = p.text, modifier = Modifier.weight(1f))
        Label(if (expanded) tr("Fermer") else tr("Ouvrir"), color = p.primary)
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = p.primary)
    }
    if (!expanded) {
        if (selected != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Shape12)
                    .background(p.surface)
                    .border(2.dp, p.primary, Shape12)
                    .clickable { expanded = true }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(96.dp)) { FieldCanvas(selected, inset = 4.dp, lineColor = p.primary, strokeDp = 1f) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(selected.name), style = Type.bodyBold, color = p.text)
                    Label(selected.dims, color = p.primary, style = Type.caption)
                    Label(tr("Touche pour changer de sport"), style = Type.caption)
                }
            }
        } else {
            Text(tr("Aucun sport choisi — touche « Ouvrir »."), style = Type.body, color = p.muted)
        }
        return
    }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(tr("Rechercher un sport"), style = Type.body, color = p.muted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = p.muted) },
            textStyle = Type.body.copy(color = p.text),
            shape = Shape8,
            colors = fieldColors(),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Tous"), category == null) { category = null }
            Sports.categories.forEach { c -> Chip(tr(c), category == c) { category = c } }
        }
        Spacer(Modifier.height(12.dp))
        list.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    SportCard(s, s.id == selected?.id, Modifier.weight(1f)) {
                        AppState.selectSport(s)
                        expanded = false
                        Calibrator.prepare(s)
                        WatchLink.pushState(force = true)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (list.isEmpty()) Text(tr("Aucun sport trouvé."), style = Type.body, color = p.muted)
}

@Composable
private fun SportCard(s: Sport, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(Shape12)
            .background(p.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.primary else p.border, Shape12)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box {
            FieldCanvas(
                s, inset = 6.dp,
                lineColor = if (selected) p.primary else p.fieldLine,
                strokeDp = 1f,
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(p.primary)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(tr(s.name), style = Type.bodyBold, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Label(s.dims, color = if (selected) p.primary else p.muted, style = Type.caption)
        if (s.indoor) {
            Spacer(Modifier.height(4.dp))
            Label(tr("En salle · GPS limité"), color = p.warning, style = Type.caption)
        }
    }
}

// ------------------------------------------------------------------ Affichage

@Composable
private fun LanguageSection() {
    val p = LocalPalette.current
    SectionTitle(tr("Langue"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.philip.fieldtracker.i18n.I18n.languages.forEach { (code, label) ->
            Chip(label, com.philip.fieldtracker.i18n.I18n.lang == code) { AppState.setLang(code) }
        }
    }
}

@Composable
private fun DisplaySection() {
    val p = LocalPalette.current
    val theme by AppState.theme.collectAsState()
    val outAlert by AppState.outAlert.collectAsState()
    val keepOn by AppState.keepScreenOn.collectAsState()

    SectionTitle(tr("Affichage & contraste"), tr("Thème"))
    Panel {
        val options = listOf("system" to tr("Auto (système)")) + Palettes.all.map { it.id to tr(it.label) }
        options.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, label) ->
                    val pal = if (id == "system") null else Palettes.all.first { it.id == id }
                    val active = theme == id
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(Shape8)
                            .background(if (active) p.primary.copy(alpha = 0.1f) else p.elevated)
                            .border(1.dp, if (active) p.primary else p.border, Shape8)
                            .clickable { AppState.setTheme(id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(pal?.bg ?: Color.Gray)
                                .border(3.dp, pal?.primary ?: p.text, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = Type.label, color = if (active) p.primary else p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        ToggleRow(tr("Alerte sortie de terrain"), tr("Vibration téléphone + montre après 3 s hors limites"), outAlert) { AppState.setOutAlert(it) }
        ToggleRow(tr("Écran toujours allumé"), tr("Pendant la séance en direct"), keepOn) { AppState.setKeepScreenOn(it) }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyBold, color = p.text)
            Text(subtitle, style = Type.caption, color = p.muted)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onPrimary, checkedTrackColor = p.primary,
                uncheckedThumbColor = p.muted, uncheckedTrackColor = p.elevated, uncheckedBorderColor = p.border,
            )
        )
    }
}

// ------------------------------------------------------------------ Profil

@Composable
private fun ProfileSection() {
    val p = LocalPalette.current
    val profile by AppState.profile.collectAsState()
    SectionTitle(tr("Profil"), tr("Calories & zones FC"))
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(tr("Âge"), profile.age.toString(), Modifier.weight(1f)) { v ->
                v.toIntOrNull()?.takeIf { it in 8..99 }?.let { AppState.setProfile(profile.copy(age = it)) }
            }
            NumberField(tr("Poids (kg)"), profile.weightKg.toInt().toString(), Modifier.weight(1f)) { v ->
                v.toIntOrNull()?.takeIf { it in 25..200 }?.let { AppState.setProfile(profile.copy(weightKg = it.toDouble())) }
            }
            NumberField(tr("FC max"), if (profile.hrMaxOverride > 0) profile.hrMaxOverride.toString() else "", Modifier.weight(1f), placeholder = "${220 - profile.age}") { v ->
                val n = v.toIntOrNull()
                if (n == null) AppState.setProfile(profile.copy(hrMaxOverride = 0))
                else if (n in 120..230) AppState.setProfile(profile.copy(hrMaxOverride = n))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(tr("Homme"), profile.male) { AppState.setProfile(profile.copy(male = true)) }
            Chip(tr("Femme"), !profile.male) { AppState.setProfile(profile.copy(male = false)) }
        }
    }
}

@Composable
private fun NumberField(label: String, initial: String, modifier: Modifier, placeholder: String = "", onCommit: (String) -> Unit) {
    val p = LocalPalette.current
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.filter { it.isDigit() }.take(3); onCommit(text) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label, style = Type.caption) },
        placeholder = { Text(placeholder, style = Type.body, color = p.muted) },
        textStyle = Type.label.copy(color = p.text),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = Shape8,
        colors = fieldColors(),
    )
}

@Composable
fun fieldColors() = LocalPalette.current.let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.primary,
        unfocusedBorderColor = p.border,
        focusedTextColor = p.text,
        unfocusedTextColor = p.text,
        cursorColor = p.primary,
        focusedLabelColor = p.primary,
        unfocusedLabelColor = p.muted,
        focusedContainerColor = p.surface,
        unfocusedContainerColor = p.surface,
    )
}


/** Choix du poste (pour les sports d'équipe). */
@Composable
fun PositionSection(sport: Sport) {
    val positions = com.philip.fieldtracker.data.Positions.forSport(sport.id)
    if (positions.isEmpty()) return
    val p = LocalPalette.current
    val current by AppState.position.collectAsState()
    Spacer(Modifier.height(16.dp))
    SectionTitle(tr("Ton poste"), positions.firstOrNull { it.id == current }?.let { tr(it.name) } ?: tr("Non précisé"))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(tr("Non précisé"), current.isEmpty()) { AppState.setPosition("") }
        positions.forEach { pos -> Chip(tr(pos.name), current == pos.id) { AppState.setPosition(pos.id) } }
    }
    Text(tr("Sert à l'analyse de poste et à comparer ta présence à la zone habituelle du poste."), style = Type.caption, color = p.muted)
}
