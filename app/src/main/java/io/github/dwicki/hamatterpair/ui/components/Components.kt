package io.github.dwicki.hamatterpair.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dwicki.hamatterpair.R
import io.github.dwicki.hamatterpair.commission.CheckStatus
import io.github.dwicki.hamatterpair.ui.QrCodes
import io.github.dwicki.hamatterpair.ui.theme.LocalStatusColors

/** Four-step progress header of the add-device wizard: Scan · Check · Connect · Finish. */
@Composable
fun StepIndicator(current: Int, modifier: Modifier = Modifier) {
    val labels = listOf(R.string.step_scan, R.string.step_check, R.string.step_connect, R.string.step_name)
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val active = i <= current
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "step",
            )
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(color))
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Animated traffic-light icon for a check. */
@Composable
fun StatusIcon(status: CheckStatus, size: Dp = 24.dp) {
    val c = LocalStatusColors.current
    AnimatedContent(
        status,
        transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() },
        label = "status",
    ) { s ->
        when (s) {
            CheckStatus.PENDING -> CircularProgressIndicator(Modifier.size(size).padding(3.dp), strokeWidth = 2.5.dp)
            CheckStatus.OK -> Icon(Icons.Rounded.CheckCircle, null, tint = c.ok, modifier = Modifier.size(size))
            CheckStatus.WARN -> Icon(Icons.Rounded.Warning, null, tint = c.warn, modifier = Modifier.size(size))
            CheckStatus.FAIL -> Icon(Icons.Rounded.Error, null, tint = c.fail, modifier = Modifier.size(size))
        }
    }
}

enum class BannerKind { INFO, WARN, ERROR }

/** Rounded inline message used for hints, warnings and errors. */
@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = LocalStatusColors.current
    val (bg, fg, icon) = when (kind) {
        BannerKind.INFO -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Rounded.Info)
        BannerKind.WARN -> Triple(c.warnContainer, MaterialTheme.colorScheme.onSurface, Icons.Rounded.Warning)
        BannerKind.ERROR -> Triple(c.failContainer, MaterialTheme.colorScheme.onSurface, Icons.Rounded.Error)
    }
    val tint = when (kind) { BannerKind.INFO -> fg; BannerKind.WARN -> c.warn; BannerKind.ERROR -> c.fail }
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
                if (action != null) { Spacer(Modifier.height(8.dp)); action() }
            }
        }
    }
}

/** Big round icon with a soft halo, used as screen illustration. */
@Composable
fun HeroIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, pulse: Boolean = false, size: Dp = 120.dp) {
    val scale = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s").value
    } else 1f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size * scale).clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
        )
        Box(Modifier.size(size * 0.66f).clip(CircleShape).background(tint.copy(alpha = 0.18f)))
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.36f))
    }
}

/** Primary full-width button with consistent height. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick, modifier.fillMaxWidth().height(56.dp), enabled = enabled,
        shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Renders a QR code (for sharing a commissioning window). */
@Composable
fun QrImage(content: String, modifier: Modifier = Modifier, sizePx: Int = 720) {
    val bmp = remember(content) { QrCodes.encode(content, sizePx) }
    Surface(color = Color.White, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.padding(16.dp).fillMaxWidth())
    }
}
