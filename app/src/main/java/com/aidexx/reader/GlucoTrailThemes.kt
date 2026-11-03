package com.aidexx.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val LocalTrailTheme = staticCompositionLocalOf { AppThemeMode.SYSTEM }
val AppThemeMode.isModern: Boolean get() = this !in listOf(AppThemeMode.SYSTEM, AppThemeMode.DAY, AppThemeMode.NIGHT)
fun AppThemeMode.isDark(systemDark: Boolean) = when(this) {
    AppThemeMode.SYSTEM -> systemDark
    AppThemeMode.DAY, AppThemeMode.CLEAN_LIGHT, AppThemeMode.CLINICAL_CLEAR -> false
    else -> true
}
fun trailColors(mode: AppThemeMode, systemDark: Boolean): ColorScheme {
    val dark = mode.isDark(systemDark)
    val palette = when(mode) {
        AppThemeMode.MODERN_DARK -> listOf(0xFF9CE665, 0xFF111518, 0xFF1D2328, 0xFF2C353B, 0xFFEAF0F3, 0xFFBEC8CE)
        AppThemeMode.CLEAN_LIGHT -> listOf(0xFF006F73, 0xFFF6FAFA, 0xFFFFFFFF, 0xFFE2EFEF, 0xFF132C32, 0xFF456068)
        AppThemeMode.AMOLED -> listOf(0xFF65F578, 0xFF000000, 0xFF070B08, 0xFF142319, 0xFFF4FFF5, 0xFFBACDBD)
        AppThemeMode.SOFT_GLASS -> listOf(0xFFD2B8FF, 0xFF171A35, 0xFF282C50, 0xFF353A60, 0xFFF3EEFF, 0xFFCDC8E5)
        AppThemeMode.CLINICAL_CLEAR -> listOf(0xFF005AC1, 0xFFFFFFFF, 0xFFF6F9FF, 0xFFE0ECFF, 0xFF071E43, 0xFF3B5270)
        AppThemeMode.SIGNATURE -> listOf(0xFF83EDCD, 0xFF061925, 0xFF102A38, 0xFF1E3B49, 0xFFE8FBF5, 0xFFB4CFD8)
        else -> if(dark) listOf(0xFF73D338, 0xFF111315, 0xFF111315, 0xFF303238, 0xFFF0F0F0, 0xFFCACACA)
                else listOf(0xFF2F7D32, 0xFFF8F8F8, 0xFFF8F8F8, 0xFFE3E9E1, 0xFF192019, 0xFF434D43)
    }.map { Color(it) }
    val base = if(dark) darkColorScheme() else lightColorScheme()
    return base.copy(primary=palette[0], onPrimary=if(dark) Color(0xFF082019) else Color.White,
        primaryContainer=palette[3], onPrimaryContainer=palette[4],
        secondary=palette[0], onSecondary=if(dark) Color.Black else Color.White,
        secondaryContainer=palette[3], onSecondaryContainer=palette[4],
        tertiary=palette[0], background=palette[1], onBackground=palette[4],
        surface=palette[2], onSurface=palette[4], surfaceVariant=palette[3], onSurfaceVariant=palette[5],
        surfaceTint=palette[0], outline=palette[5].copy(alpha=.55f), outlineVariant=palette[5].copy(alpha=.22f),
        surfaceContainerLowest=palette[1], surfaceContainerLow=palette[2], surfaceContainer=palette[2],
        surfaceContainerHigh=palette[3], surfaceContainerHighest=palette[3])
}

@Composable
fun trailBackdrop(): Modifier {
    val colors=MaterialTheme.colorScheme
    return if(LocalTrailTheme.current==AppThemeMode.SOFT_GLASS)
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFF33345E), colors.background, Color(0xFF141B2D))))
    else Modifier.background(colors.background)
}

@Composable
fun TrailThemePicker(selected: AppThemeMode, onSelected: (AppThemeMode)->Unit) {
    val choices=listOf(
        Triple(AppThemeMode.MODERN_DARK,"Modern Dark","Charcoal cards and crisp green accents"),
        Triple(AppThemeMode.CLEAN_LIGHT,"Clean Light","White surfaces and teal highlights"),
        Triple(AppThemeMode.AMOLED,"AMOLED","True black background and luminous green"),
        Triple(AppThemeMode.SOFT_GLASS,"Soft Glass","Layered indigo surfaces and lavender accents"),
        Triple(AppThemeMode.CLINICAL_CLEAR,"Clinical Clear","Larger text, strong contrast and blue accents"),
        Triple(AppThemeMode.SIGNATURE,"GlucoTrail Signature","Deep navy and mint"),
        Triple(AppThemeMode.SYSTEM,"Follow System","Follow the phone's light or dark appearance"),
        Triple(AppThemeMode.DAY,"Classic Day","Original light appearance"),
        Triple(AppThemeMode.NIGHT,"Classic Night","Original dark appearance")
    )
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("Appearance",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("Choose a theme. Your selection is applied immediately and saved.",style=MaterialTheme.typography.bodyMedium)
        }
        items(choices,key={it.first.name}) { (mode,title,description) ->
            val preview=trailColors(mode, true)
            Card(onClick={onSelected(mode)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),
                colors=CardDefaults.cardColors(containerColor=preview.surface,contentColor=preview.onSurface),
                border=androidx.compose.foundation.BorderStroke(if(selected==mode) 2.dp else 1.dp,if(selected==mode) preview.primary else preview.outlineVariant)) {
                Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected=selected==mode,onClick={onSelected(mode)},colors=RadioButtonDefaults.colors(selectedColor=preview.primary,unselectedColor=preview.onSurfaceVariant))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(title,fontWeight=FontWeight.Bold)
                        Text(description,style=MaterialTheme.typography.bodySmall,color=preview.onSurfaceVariant)
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf(preview.background,preview.surfaceVariant,preview.primary).forEach { color ->
                                Box(Modifier.size(width=36.dp,height=12.dp).background(color,RoundedCornerShape(6.dp)))
                            }
                        }
                    }
                }
            }
        }
    }
}
