package com.wifeassistant.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// أشكال ناعمة كبيرة — هوية وصال العالمية.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

// ————— Wisal Global Design Tokens (docs/design-system.md) —————
// «The Living Link»: Porcelain فاتح افتراضيًا + Midnight داكن كخيار.
// Coral/Amber/Teal لمسات إنسانية — مش تدرّجات في كل حتة.
object WisalColors {
    val MidnightAtlantic = Color(0xFF061827) // نص أساسي فاتح / خلفية داكن
    val HumanCoral = Color(0xFFFF6E72)       // الأساسي (أزرار/تمييز)
    val SolarAmber = Color(0xFFF2C56B)       // ثانوي (عناوين أقسام/لمسات)
    val OceanTeal = Color(0xFF35B8A6)        // نجاح/متصل/تمييز ثالث
    val Porcelain = Color(0xFFF8F5EF)        // خلفية الوضع الفاتح
    val Mist = Color(0xFFAAB8C4)             // نص ثانوي/حدود

    // مشتقات أسطح (مش في التوكنز الأساسية لكن لازمة للتطبيق):
    val PorcelainCard = Color(0xFFFFFFFF)
    val PorcelainRaised = Color(0xFFF0EBE1)
    val MidnightSurface = Color(0xFF0D2133)
    val MidnightRaised = Color(0xFF13293D)
}

// جراديانت البراند (كورال → عنبر) — للأزرار الأساسية ولحظات مختارة فقط.
val WisalGradient = Brush.horizontalGradient(listOf(WisalColors.HumanCoral, WisalColors.SolarAmber))
val WisalGradientVertical = Brush.verticalGradient(listOf(WisalColors.HumanCoral, WisalColors.SolarAmber))

// زرار الهوية: جراديانت كورال→عنبر بنص Midnight غامق (تباين AA على الخلفيتين).
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(
            brush = if (enabled) WisalGradient else Brush.horizontalGradient(
                listOf(WisalColors.Mist, WisalColors.Mist)
            ),
            shape = RoundedCornerShape(50),
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = WisalColors.MidnightAtlantic,
            disabledContentColor = WisalColors.MidnightSurface,
        ),
        shape = RoundedCornerShape(50),
        contentPadding = contentPadding,
    ) { content() }
}

// الوضع الفاتح (الافتراضي): Porcelain + Midnight نصوص + لمسات كورال/عنبر/تيل.
private val LightColors = lightColorScheme(
    primary = Color(0xFFB53142), // Deeper coral for readable text/controls on light surfaces.
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E0),
    onPrimaryContainer = Color(0xFF4A1213),
    secondary = Color(0xFF765711), // عنبر أغمق للنصوص على الفاتح (تباين AA)
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E7C4),
    onSecondaryContainer = Color(0xFF3C2E08),
    tertiary = Color(0xFF1E8A7C),
    onTertiary = Color.White,
    background = WisalColors.Porcelain,
    onBackground = WisalColors.MidnightAtlantic,
    surface = WisalColors.PorcelainCard,
    surfaceContainerLow = WisalColors.PorcelainCard,
    surfaceContainer = WisalColors.PorcelainRaised,
    onSurface = WisalColors.MidnightAtlantic,
    surfaceVariant = WisalColors.PorcelainRaised,
    onSurfaceVariant = Color(0xFF4E5B68),
    outline = WisalColors.Mist,
)

// الوضع الداكن (اختياري): Midnight Atlantic.
private val DarkColors = darkColorScheme(
    primary = WisalColors.HumanCoral,
    onPrimary = Color(0xFF3D0A0B),
    primaryContainer = Color(0xFF57292B),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = WisalColors.SolarAmber,
    onSecondary = Color(0xFF3C2E08),
    secondaryContainer = Color(0xFF2A3A50),
    onSecondaryContainer = WisalColors.SolarAmber,
    tertiary = WisalColors.OceanTeal,
    onTertiary = Color(0xFF00332B),
    background = WisalColors.MidnightAtlantic,
    onBackground = WisalColors.Porcelain,
    surface = WisalColors.MidnightSurface,
    onSurface = WisalColors.Porcelain,
    surfaceVariant = WisalColors.MidnightRaised,
    onSurfaceVariant = WisalColors.Mist,
    outline = Color(0xFF2C4157),
)

@Composable
fun WifeAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // هوية وصال هي الافتراضي؛ Material You اختياري
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, shapes = ExpressiveShapes, content = content)
}
