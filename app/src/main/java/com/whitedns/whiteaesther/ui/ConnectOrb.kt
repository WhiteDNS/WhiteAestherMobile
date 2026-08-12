package com.whitedns.whiteaesther.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType
import kotlin.math.cos
import kotlin.math.sin

/** What the orb shows. Derived from EngineStage, never set directly. */
enum class ConnectState { IDLE, WORKING, LIVE, FAILED }

private const val VIEWBOX = 240f
private const val R_HALO = 118f
private const val R_TRACK = 104f
private const val R_DASH = 90f
private const val R_CORE = 75f

/**
 * One control carrying the connection state in colour, motion and a ring.
 *
 * While connecting the sweep is indeterminate on purpose: the engine reports
 * stage and message, not progress, so a filling bar would be inventing a number.
 */
@Composable
fun ConnectOrb(
    state: ConnectState,
    caption: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val target = when (state) {
        ConnectState.IDLE -> colors.signalIdle
        ConnectState.WORKING -> colors.signalWorking
        ConnectState.LIVE -> colors.signalLive
        ConnectState.FAILED -> colors.signalFailed
    }
    val signal by animateColorAsState(target, tween(500), label = "orb-signal")

    val transition = rememberInfiniteTransition(label = "orb")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
        label = "orb-sweep",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing)),
        label = "orb-orbit",
    )
    val breath by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(2100, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb-breath",
    )
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (state == ConnectState.WORKING) 1700 else 3400, easing = LinearEasing),
        ),
        label = "orb-ripple",
    )

    // A press has to be visible. Without it the only feedback is the engine
    // changing stage, which can take seconds, so taps look ignored and get
    // repeated -- and each repeat queues another command behind the first.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = tween(140),
        label = "orb-press",
    )

    Box(
        modifier = modifier
            .size(262.dp)
            .scale(press)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .testTag("connect-orb"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val f = size.minDimension / VIEWBOX
            val centre = Offset(size.width / 2f, size.height / 2f)

            drawBloom(centre, R_CORE * f * 2.4f, signal, bloomAlpha(state, breath))

            if (state == ConnectState.LIVE || state == ConnectState.WORKING) {
                drawRipples(centre, R_CORE * f, R_TRACK * f, signal, ripple)
            }

            // Faint outer halo the sweep travels along.
            drawCircle(signal.copy(alpha = 0.16f), R_HALO * f, centre, style = Stroke(1f * f))
            drawCircle(colors.track, R_TRACK * f, centre, style = Stroke(2.5f * f))

            // Dotted inner ring: still at rest, turning slowly once connected.
            val dashAlpha = if (state == ConnectState.LIVE) 0.34f else if (state == ConnectState.IDLE) 0.17f else 0f
            if (dashAlpha > 0f) {
                rotate(if (state == ConnectState.LIVE) orbit else 0f, centre) {
                    drawCircle(
                        signal.copy(alpha = dashAlpha),
                        R_DASH * f,
                        centre,
                        style = Stroke(
                            width = 1.5f * f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f * f, 9f * f)),
                        ),
                    )
                }
            }

            when (state) {
                ConnectState.WORKING -> rotate(sweep, centre) {
                    drawSweep(centre, R_HALO * f, f, signal)
                }
                ConnectState.LIVE, ConnectState.FAILED -> drawCircle(
                    signal,
                    R_TRACK * f,
                    centre,
                    style = Stroke(3.5f * f, cap = StrokeCap.Round),
                )
                ConnectState.IDLE -> Unit
            }
        }

        Column(
            modifier = Modifier
                .size((R_CORE * 2 / VIEWBOX * 262).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(signal.copy(alpha = 0.15f).compositeOn(colors.ink3), colors.ink2),
                    ),
                )
                .border(1.dp, signal.copy(alpha = 0.34f), CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = when (state) {
                    ConnectState.IDLE -> AetherIcons.Power
                    ConnectState.WORKING -> AetherIcons.Radar
                    ConnectState.LIVE -> AetherIcons.ShieldCheck
                    ConnectState.FAILED -> AetherIcons.ShieldAlert
                },
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = signal,
            )
            Box(Modifier.height(7.dp))
            Text(
                caption.uppercase(),
                style = AetherType.Label.copy(fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = if (state == ConnectState.IDLE) colors.text3 else signal,
            )
        }
    }
}

private fun bloomAlpha(state: ConnectState, breath: Float) = when (state) {
    ConnectState.IDLE -> 0.10f
    ConnectState.WORKING -> 0.22f
    ConnectState.LIVE -> breath * 0.30f
    ConnectState.FAILED -> 0.18f
}

private fun DrawScope.drawBloom(centre: Offset, radius: Float, colour: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colour.copy(alpha = alpha), Color.Transparent),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/** Three waves leaving the core, staggered a third of a cycle apart. */
private fun DrawScope.drawRipples(
    centre: Offset,
    from: Float,
    to: Float,
    colour: Color,
    phase: Float,
) {
    repeat(3) { index ->
        val p = (phase + index / 3f) % 1f
        drawCircle(
            color = colour.copy(alpha = (1f - p) * 0.30f),
            radius = from + (to - from) * p * 1.25f,
            center = centre,
            style = Stroke(1f),
        )
    }
}

/** A tapering arc with an arrowhead at its leading edge, drawn at the top. */
private fun DrawScope.drawSweep(centre: Offset, radius: Float, f: Float, colour: Color) {
    val segments = 14
    val span = 110f
    repeat(segments) { index ->
        val start = -200f + span / segments * index
        val alpha = (index + 1f) / segments
        drawArc(
            color = colour.copy(alpha = alpha),
            startAngle = start,
            sweepAngle = span / segments + 0.6f,
            useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(2.5f * f, cap = StrokeCap.Round),
        )
    }
    val tip = Math.toRadians(-90.0)
    val head = Offset(
        centre.x + radius * cos(tip).toFloat(),
        centre.y + radius * sin(tip).toFloat(),
    )
    val arrow = Path().apply {
        moveTo(head.x - 3f * f, head.y - 7.5f * f)
        lineTo(head.x + 13.5f * f, head.y)
        lineTo(head.x - 3f * f, head.y + 7.5f * f)
        close()
    }
    drawPath(arrow, colour)
}

private fun Color.compositeOn(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha),
    green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha),
    alpha = 1f,
)
