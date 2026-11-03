package com.aidexx.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class GlucoTrailThemesTest {
    private fun contrast(a: Color,b: Color): Float {
        val x=a.luminance(); val y=b.luminance()
        return (maxOf(x,y)+0.05f)/(minOf(x,y)+0.05f)
    }
    @Test fun sixModernThemesWithReadableText() {
        val modes=AppThemeMode.entries.filter { it.isModern }
        assertEquals(6,modes.size)
        modes.forEach { mode ->
            val c=trailColors(mode,false)
            assertTrue("$mode surface contrast",contrast(c.onSurface,c.surface)>=4.5f)
            assertTrue("$mode secondary text",contrast(c.onSurfaceVariant,c.surfaceVariant)>=4.5f)
            assertTrue("$mode primary contrast",contrast(c.onPrimary,c.primary)>=4.5f)
            assertEquals(mode,AppThemeMode.valueOf(mode.name))
        }
    }
    @Test fun legacyModesAndAmoled() {
        assertFalse(AppThemeMode.SYSTEM.isDark(false))
        assertTrue(AppThemeMode.SYSTEM.isDark(true))
        assertFalse(AppThemeMode.DAY.isDark(true))
        assertTrue(AppThemeMode.NIGHT.isDark(false))
        assertEquals(Color.Black,trailColors(AppThemeMode.AMOLED,false).background)
        assertFalse(AppThemeMode.CLINICAL_CLEAR.isDark(true))
        assertFalse(AppThemeMode.CLEAN_LIGHT.isDark(true))
    }
}
