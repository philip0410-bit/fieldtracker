package com.philip.fieldtracker.ui.components

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.philip.fieldtracker.tracking.GpsEngine
import com.philip.fieldtracker.tracking.GpsStatus
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Panneau d'état de la localisation avec le bouton qui débloque la situation :
 * autoriser, activer la localisation, autoriser la position exacte.
 */
@Composable
fun GpsPanel(tag: String = "panel") {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val fix by GpsEngine.fix.collectAsState()
    val sats by GpsEngine.satellites.collectAsState()
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            GpsEngine.start(ctx, tag)
            delay(1000); tick++
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { GpsEngine.stop(tag) } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            GpsEngine.ensure(ctx)
        } else {
            // Refus définitif : on ouvre la page de l'app dans les réglages
            val act = ctx as? Activity
            val canAsk = act?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ?: false
            if (!canAsk) ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val askPerms = {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val status = if (tick >= 0) GpsEngine.status(ctx) else GpsStatus.SEARCHING
    val acc = fix?.accuracy?.toInt() ?: 0
    val color = when (status) {
        GpsStatus.OK -> if (acc <= 10) p.primary else p.warning
        GpsStatus.APPROXIMATE, GpsStatus.SEARCHING -> p.warning
        else -> p.secondary
    }

    Panel(accent = color.copy(alpha = 0.6f), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(
                when (status) {
                    GpsStatus.OK -> Icons.Filled.GpsFixed
                    GpsStatus.NO_PERMISSION, GpsStatus.LOCATION_OFF -> Icons.Filled.LocationOff
                    else -> Icons.Filled.GpsNotFixed
                }, color
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (status) {
                        GpsStatus.NO_PERMISSION -> tr("Localisation non autorisée")
                        GpsStatus.LOCATION_OFF -> tr("Localisation désactivée")
                        GpsStatus.SEARCHING -> tr("Recherche des satellites…")
                        GpsStatus.APPROXIMATE -> trf("Position approximative ±%d m", acc)
                        GpsStatus.OK -> trf("GPS actif ±%d m", acc)
                    },
                    style = Type.bodyBold, color = p.text
                )
                Text(
                    when (status) {
                        GpsStatus.NO_PERMISSION -> tr("Nécessaire pour baliser le terrain et suivre tes déplacements.")
                        GpsStatus.LOCATION_OFF -> tr("Active la localisation du téléphone.")
                        GpsStatus.SEARCHING -> tr("Va à découvert. Le GPS fonctionne sans internet ; le premier signal peut prendre 30 s à 1 min.")
                        GpsStatus.APPROXIMATE ->
                            if (!GpsEngine.hasPrecise(ctx)) tr("Seule la position approximative est autorisée.")
                            else tr("Localisation par Wi-Fi/antennes en attendant les satellites.")
                        GpsStatus.OK -> trf("Satellites : %d utilisés / %d visibles", sats.first, sats.second)
                    },
                    style = Type.caption, color = p.muted
                )
            }
            if (status == GpsStatus.OK) SignalBars(when { acc <= 4 -> 4; acc <= 8 -> 3; acc <= 15 -> 2; else -> 1 })
        }
        when (status) {
            GpsStatus.NO_PERMISSION -> {
                Spacer(Modifier.height(10.dp))
                GhostButton(tr("Autoriser la localisation"), Modifier.fillMaxWidth(), Icons.Filled.GpsFixed, tint = p.primary) { askPerms() }
            }
            GpsStatus.LOCATION_OFF -> {
                Spacer(Modifier.height(10.dp))
                GhostButton(tr("Activer la localisation"), Modifier.fillMaxWidth(), Icons.Filled.GpsFixed, tint = p.primary) {
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            GpsStatus.APPROXIMATE -> if (!GpsEngine.hasPrecise(ctx)) {
                Spacer(Modifier.height(10.dp))
                GhostButton(tr("Autoriser la position exacte"), Modifier.fillMaxWidth(), Icons.Filled.GpsFixed, tint = p.primary) { askPerms() }
            }
            else -> {}
        }
    }
}

@Composable
fun SignalBars(level: Int) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(5.dp)
                    .height((6 + i * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) p.primary else p.elevated)
            )
        }
    }
}
