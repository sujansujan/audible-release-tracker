package com.plaintext.audible.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PtsPalette(
    val name: String,
    val background: Color,
    val surfaceLine: Color,
    val foreground: Color,
    val comment: Color,
    val cyan: Color,
    val green: Color,
    val orange: Color,
    val pink: Color,
    val purple: Color,
    val red: Color,
    val yellow: Color
)

// Official Dracula Dark Palette (https://draculatheme.com/spec)
val DraculaPalette = PtsPalette(
    name = "DRACULA",
    background = Color(0xFF282A36),
    surfaceLine = Color(0xFF44475A),
    foreground = Color(0xFFF8F8F2),
    comment = Color(0xFF6272A4),
    cyan = Color(0xFF8BE9FD),
    green = Color(0xFF50FA7B),
    orange = Color(0xFFFFB86C),
    pink = Color(0xFFFF79C6),
    purple = Color(0xFFBD93F9),
    red = Color(0xFFFF5555),
    yellow = Color(0xFFF1FA8C)
)

// Official Alucard Light Palette (https://github.com/dracula/dracula-theme)
val AlucardPalette = PtsPalette(
    name = "ALUCARD",
    background = Color(0xFFFFFBEB),
    surfaceLine = Color(0xFFCFCFDE),
    foreground = Color(0xFF1F1F1F),
    comment = Color(0xFF6C664B),
    cyan = Color(0xFF036A96),
    green = Color(0xFF14710A),
    orange = Color(0xFFA34D14),
    pink = Color(0xFFA3144D),
    purple = Color(0xFF644AC9),
    red = Color(0xFFCB3A2A),
    yellow = Color(0xFF846E15)
)

val LocalPtsPalette = staticCompositionLocalOf { DraculaPalette }
