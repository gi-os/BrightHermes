package com.gios.brighthermes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.light.common.theme.akkuratFamilyOrDefault

/**
 * Three colours and one grid. That is the whole visual system, and it is the design brief's:
 * `#FFF` content and `#BBB` secondary on `#000`, no shadows, no fills, no radii, no colour.
 * Every gutter, tile height and margin is a whole multiple of [Grid] (15dp = one LightGrid unit).
 */
object Ink {
    val Paper = Color.Black
    val Content = Color.White
    val Secondary = Color(0xFFBBBBBB)

    /** The only other grey: rules that should be found, not seen. */
    val Rule = Color(0xFF2A2A2A)
}

val Grid = 15.dp

/** Type, sized in the design's px which are dp on this panel. Akkurat is the system font on LightOS. */
class Type(family: FontFamily) {
    // One step up from the brief's px values across the board (v0.5): the brief was drawn at
    // desk distance and the phone is read at arm's length on a matte panel.

    /** 10sp, tracked, upper case: tile labels, section heads, timestamps. */
    val label = TextStyle(fontFamily = family, fontSize = 10.sp, letterSpacing = 1.sp, lineHeight = 13.sp)

    /** 27sp, tight: the number on a tile. */
    val value = TextStyle(fontFamily = family, fontSize = 27.sp, letterSpacing = (-0.5).sp, lineHeight = 29.sp)

    /** 36sp: the clock, the only thing bigger than a value. */
    val clock = TextStyle(fontFamily = family, fontSize = 36.sp, letterSpacing = (-0.8).sp, lineHeight = 38.sp)

    /** 12.5sp: the line under a value, chips, hints. */
    val small = TextStyle(fontFamily = family, fontSize = 12.5.sp, lineHeight = 16.sp)

    /** 17sp: the conversation, the input. */
    val body = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 24.sp)

    /** The user's own words: same size, medium weight. Alignment plus weight is what says "you". */
    val bodyYou = body.copy(fontWeight = FontWeight.Medium)

    /** 22sp: a heading on the setup and edit screens. */
    val title = TextStyle(fontFamily = family, fontSize = 22.sp, letterSpacing = (-0.2).sp, lineHeight = 26.sp)

    /** 28sp: what you are saying, while you say it. The brief's 26px live line, one step up. */
    val live = TextStyle(fontFamily = family, fontSize = 28.sp, letterSpacing = (-0.5).sp, lineHeight = 35.sp)
}

@Composable
fun BrightHermesTheme(content: @Composable (Type) -> Unit) {
    val type = remember { Type(akkuratFamilyOrDefault()) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink.Paper,
            surface = Ink.Paper,
            onBackground = Ink.Content,
            onSurface = Ink.Content,
            primary = Ink.Content,
            onPrimary = Ink.Paper,
            secondary = Ink.Secondary,
            outline = Ink.Rule,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink.Paper) { content(type) }
    }
}
