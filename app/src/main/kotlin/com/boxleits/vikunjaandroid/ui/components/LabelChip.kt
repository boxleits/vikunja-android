package com.boxleits.vikunjaandroid.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxleits.vikunjaandroid.core.model.Label

@Composable
fun LabelChip(label: Label, modifier: Modifier = Modifier) {
    val background = parseHexColor(label.hexColor) ?: MaterialTheme.colorScheme.secondaryContainer
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = background,
    ) {
        Text(
            text = label.title,
            style = MaterialTheme.typography.labelSmall,
            color = readableTextColor(background),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
