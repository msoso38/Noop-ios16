package com.noop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Snappy press button - scale bounce only. Fully opaque flat fill (no translucent wash /
 * radial orb - user feedback: weird glow on Log workout / Strength trainer).
 */
@Composable
fun WetBounceButton(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Palette.accent,
    rounded: Dp = 18.dp,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(rounded)
    val fill = Palette.surfaceRaised
    Box(
        modifier = modifier
            .scale(scale.value)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, tint.copy(alpha = 0.85f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                scope.launch {
                    scale.snapTo(0.94f)
                    scale.animateTo(1.02f, spring(stiffness = Spring.StiffnessHigh, dampingRatio = 0.55f))
                    scale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.7f))
                }
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = NoopType.headline, color = Palette.textPrimary)
    }
}

@Composable
fun WetBounceIconCircle(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.accent,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .scale(scale.value)
            .clip(CircleShape)
            .background(Palette.surfaceRaised)
            .border(1.5.dp, tint.copy(alpha = 0.75f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                scope.launch {
                    scale.snapTo(0.9f)
                    scale.animateTo(1.04f, spring(stiffness = Spring.StiffnessHigh, dampingRatio = 0.55f))
                    scale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.7f))
                }
                onClick()
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}