package com.philip.fieldtracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.philip.fieldtracker.R

@Immutable
data class Palette(
    val id: String,
    val label: String,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val tertiary: Color,
    val warning: Color,
    val fieldLine: Color,
    val fieldBg: Color,
)

object Palettes {
    val carbon = Palette(
        "carbon", "Carbon Dark", true,
        bg = Color(0xFF0D1117), surface = Color(0xFF161B22), elevated = Color(0xFF21262D), border = Color(0xFF30363D),
        text = Color(0xFFF0F6FC), muted = Color(0xFF8B949E), primary = Color(0xFF00FF66), onPrimary = Color(0xFF0D1117),
        secondary = Color(0xFFFF5722), tertiary = Color(0xFF10B981), warning = Color(0xFFF5B83D),
        fieldLine = Color(0xFF4A5361), fieldBg = Color(0xFF0A0E14),
    )
    val oled = Palette(
        "oled", "OLED Noir", true,
        bg = Color(0xFF000000), surface = Color(0xFF0B0B0B), elevated = Color(0xFF161616), border = Color(0xFF262626),
        text = Color(0xFFFFFFFF), muted = Color(0xFF8A8A8A), primary = Color(0xFF00FF66), onPrimary = Color(0xFF000000),
        secondary = Color(0xFFFF5722), tertiary = Color(0xFF10B981), warning = Color(0xFFF5B83D),
        fieldLine = Color(0xFF3A3A3A), fieldBg = Color(0xFF000000),
    )
    val pelouse = Palette(
        "pelouse", "Pelouse Nuit", true,
        bg = Color(0xFF0B1A12), surface = Color(0xFF112419), elevated = Color(0xFF183020), border = Color(0xFF24452E),
        text = Color(0xFFE8F5EC), muted = Color(0xFF8FB39A), primary = Color(0xFFB6FF3B), onPrimary = Color(0xFF0B1A12),
        secondary = Color(0xFFFF6B3D), tertiary = Color(0xFF3DDC97), warning = Color(0xFFFFC94D),
        fieldLine = Color(0xFF5E8E6A), fieldBg = Color(0xFF0E2416),
    )
    val ambre = Palette(
        "ambre", "Nocturne Ambre", true,
        bg = Color(0xFF120D0A), surface = Color(0xFF1C1510), elevated = Color(0xFF281E16), border = Color(0xFF3A2C20),
        text = Color(0xFFFFEBD6), muted = Color(0xFFB39578), primary = Color(0xFFFFB020), onPrimary = Color(0xFF1A1206),
        secondary = Color(0xFFFF4D4D), tertiary = Color(0xFFE6C46B), warning = Color(0xFFFFD166),
        fieldLine = Color(0xFF5A4636), fieldBg = Color(0xFF0E0A07),
    )
    val clair = Palette(
        "clair", "Clair", false,
        bg = Color(0xFFF4F6F8), surface = Color(0xFFFFFFFF), elevated = Color(0xFFEDF0F3), border = Color(0xFFD0D7DE),
        text = Color(0xFF0D1117), muted = Color(0xFF57606A), primary = Color(0xFF00A845), onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFFE5481A), tertiary = Color(0xFF0E9F6E), warning = Color(0xFFB7791F),
        fieldLine = Color(0xFF9AA5B1), fieldBg = Color(0xFFE8F3EC),
    )
    val soleil = Palette(
        "soleil", "Plein soleil", false,
        bg = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), elevated = Color(0xFFF0F0F0), border = Color(0xFF000000),
        text = Color(0xFF000000), muted = Color(0xFF333333), primary = Color(0xFF007A2F), onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFFC62800), tertiary = Color(0xFF006B45), warning = Color(0xFF8A5A00),
        fieldLine = Color(0xFF000000), fieldBg = Color(0xFFFFFFFF),
    )

    val all = listOf(carbon, oled, pelouse, ambre, clair, soleil)

    /** "system" suit le mode clair/sombre du téléphone. */
    @Composable
    fun resolve(id: String): Palette =
        if (id == "system") (if (isSystemInDarkTheme()) carbon else clair) else all.firstOrNull { it.id == id } ?: carbon
}

val Chivo = FontFamily(
    Font(R.font.chivo_400, FontWeight.Normal),
    Font(R.font.chivo_700, FontWeight.Bold),
    Font(R.font.chivo_900, FontWeight.Black),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object Type {
    val hero = TextStyle(fontFamily = Chivo, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp)
    val h1 = TextStyle(fontFamily = Chivo, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 28.sp)
    val h2 = TextStyle(fontFamily = Chivo, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Chivo, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val bodyBold = TextStyle(fontFamily = Chivo, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)
    val metricXl = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp)
    val metric = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
    val caption = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp)
}

val LocalPalette = staticCompositionLocalOf { Palettes.carbon }

@Composable
fun FieldTheme(themeId: String, content: @Composable () -> Unit) {
    val p = Palettes.resolve(themeId)
    val scheme = if (p.dark) darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.secondary, tertiary = p.tertiary,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.elevated, onSurfaceVariant = p.muted, outline = p.border,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.secondary, tertiary = p.tertiary,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.elevated, onSurfaceVariant = p.muted, outline = p.border,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
