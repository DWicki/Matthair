package io.github.dwicki.matthair.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.commission.CommissioningState
import io.github.dwicki.matthair.commission.EnvironmentCheck
import io.github.dwicki.matthair.commission.Phase
import io.github.dwicki.matthair.ui.MainViewModel
import io.github.dwicki.matthair.ui.components.Banner
import io.github.dwicki.matthair.ui.components.BannerKind
import io.github.dwicki.matthair.ui.components.PrimaryButton
import io.github.dwicki.matthair.ui.components.StepIndicator
import io.github.dwicki.matthair.ui.failureTips
import io.github.dwicki.matthair.ui.failureTitle
import io.github.dwicki.matthair.ui.phaseIcon
import io.github.dwicki.matthair.ui.phaseLabel
import io.github.dwicki.matthair.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

/** The phases shown in the timeline (PREPARING and DONE are implicit). */
private val TIMELINE = listOf(Phase.SEARCHING, Phase.BLE_CONNECTED, Phase.PASE, Phase.ATTESTATION, Phase.NETWORK, Phase.CASE)

/**
 * Step 3: live progress while Home Assistant commissions the device through the phone.
 * Shows a progress ring with the current phase, a compact timeline, live warnings and, on
 * failure, a plain-language explanation with concrete next steps.
 */
@Composable
fun PairingScreen(
    vm: MainViewModel,
    onSuccess: () -> Unit,
    onBackToCheck: () -> Unit,
    onShareDiagnostics: () -> Unit,
) {
    val s by vm.session.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }
    LaunchedEffect(s.isSuccess) { if (s.isSuccess) { delay(900); onSuccess() } }
    BackHandler(enabled = s.running) { confirmCancel = true }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Spacer(Modifier.height(16.dp))
        StepIndicator(2)
        AnimatedContent(s.failure != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "content", modifier = Modifier.weight(1f)) { failed ->
            if (failed) FailureContent(s) else ProgressContent(s)
        }
        Column(Modifier.padding(16.dp)) {
            if (s.failure != null) {
                PrimaryButton(stringResource(R.string.action_retry), { vm.startCommissioning() })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onBackToCheck, Modifier.weight(1f)) { Text(stringResource(R.string.fail_back_to_check)) }
                    OutlinedButton(onShareDiagnostics, Modifier.weight(1f)) { Text(stringResource(R.string.action_share_diagnostics)) }
                }
            } else if (s.running) {
                OutlinedButton({ confirmCancel = true }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.cancel_confirm_title)) },
            text = { Text(stringResource(R.string.cancel_confirm_body)) },
            confirmButton = { TextButton({ confirmCancel = false; vm.cancelCommissioning(); onBackToCheck() }) { Text(stringResource(R.string.cancel_confirm_yes)) } },
            dismissButton = { TextButton({ confirmCancel = false }) { Text(stringResource(R.string.cancel_confirm_no)) } },
        )
    }
}

@Composable
private fun ProgressContent(s: CommissioningState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.running) { while (s.running) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = if (s.startedAtMillis > 0) ((now - s.startedAtMillis) / 1000).toInt().coerceAtLeast(0) else 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        ProgressRing(s.phase)
        Spacer(Modifier.height(24.dp))
        AnimatedContent(s.phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { p ->
            Text(stringResource(phaseLabel(p)), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                stringResource(R.string.pairing_keep_close),
                stringResource(R.string.pairing_elapsed, elapsed),
                if (s.attempt > 1) stringResource(R.string.pairing_attempt, s.attempt, s.maxAttempts) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (s.waitingForPreviousAttempt) Banner(stringResource(R.string.pairing_waiting_previous))
        s.lastRssi?.takeIf { it < EnvironmentCheck.WEAK_RSSI && s.phase.ordinal <= Phase.BLE_CONNECTED.ordinal }?.let {
            Spacer(Modifier.height(8.dp)); Banner(stringResource(R.string.warn_move_closer, it), BannerKind.WARN)
        }
        if (s.warnOtherProxy) { Spacer(Modifier.height(8.dp)); Banner(stringResource(R.string.warn_other_proxy), BannerKind.WARN) }
        if (s.warnCellular) { Spacer(Modifier.height(8.dp)); Banner(stringResource(R.string.warn_cellular), BannerKind.WARN) }

        Spacer(Modifier.height(16.dp))
        Timeline(s.phase)
    }
}

/** Ring that fills per phase and spins softly while waiting. */
@Composable
private fun ProgressRing(phase: Phase) {
    val target = (phase.ordinal.toFloat() / Phase.DONE.ordinal).coerceIn(0.04f, 1f)
    val progress by animateFloatAsState(target, tween(700), label = "progress")
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(primary, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (phase != Phase.DONE) {
                drawArc(primary.copy(alpha = 0.35f), spin, 40f, false, Offset(inset, inset), arcSize, style = Stroke(stroke / 2.5f, cap = StrokeCap.Round))
            }
        }
        AnimatedContent(phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "icon") { p ->
            Icon(phaseIcon(p), null, tint = primary, modifier = Modifier.size(72.dp))
        }
    }
}

@Composable
private fun Timeline(current: Phase) {
    val c = LocalStatusColors.current
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            TIMELINE.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        p.ordinal < current.ordinal || current == Phase.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = c.ok, modifier = Modifier.size(22.dp))
                        p == current -> CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.5.dp)
                        else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(phaseLabel(p)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (p.ordinal <= current.ordinal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureContent(s: CommissioningState) {
    val kind = s.failure ?: return
    val c = LocalStatusColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Rounded.ErrorOutline, null, tint = c.fail, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.fail_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(failureTitle(kind)), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(failureTips(kind)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
        }
        s.failureDetail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.fail_details, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
