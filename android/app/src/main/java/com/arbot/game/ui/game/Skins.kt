package com.arbot.game.ui.game

import androidx.compose.ui.graphics.Color
import com.arbot.game.ui.theme.Neon

data class SkinPalette(
    val id: String,
    val name: String,
    val body: Color,
    val bodyDark: Color,
    val visor: Color,
    val eyes: Color,
    val glow: Color,
    val antenna: Color,
)

object Skins {
    val classic = SkinPalette(
        "classic", "Classic", Neon.Green, Color(0xFF14A85A), Color(0xFF071A12), Neon.Cyan, Neon.Green, Neon.Magenta,
    )
    val neon = SkinPalette(
        "skin_neon", "Neon Runner", Neon.Cyan, Color(0xFF1477C9), Color(0xFF14082A), Neon.Magenta, Neon.Cyan, Neon.Magenta,
    )
    val pirate = SkinPalette(
        "skin_pirate", "Pirate Captain", Color(0xFFB8875A), Color(0xFF6B4626), Color(0xFF1A0E06), Neon.Gold, Neon.Orange, Neon.Red,
    )
    val gold = SkinPalette(
        "skin_gold", "Golden Legend", Neon.Gold, Color(0xFFB8860B), Color(0xFF2A1C00), Color.White, Neon.Gold, Neon.Cyan,
    )

    val all = listOf(classic, neon, pirate, gold)

    fun of(id: String?): SkinPalette = all.firstOrNull { it.id == id } ?: classic
}
