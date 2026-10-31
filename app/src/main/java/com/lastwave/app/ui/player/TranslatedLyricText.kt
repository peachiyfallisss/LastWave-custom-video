package com.lastwave.app.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.lyrics.isRtlText

/** Translation direction is independent of the original lyric's script. */
@Composable
internal fun TranslatedLyricText(text: String, color: Color) {
    CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(text)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
