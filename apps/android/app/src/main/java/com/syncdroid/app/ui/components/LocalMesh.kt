package com.syncdroid.app.ui.components

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syncdroid.app.model.PeerDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun LocalMesh(
    currentDevice: String,
    peers: List<PeerDevice>,
    onCurrentDeviceLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outline
    val hub = MaterialTheme.colorScheme.onSurface
    val spoke = MaterialTheme.colorScheme.secondary
    val text = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val progressRotation = rememberInfiniteTransition(label = "mesh progress").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "mesh progress rotation",
    ).value

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(surface),
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .semantics {
                    contentDescription = buildString {
                        append("Local mesh. $currentDevice is the current device in the centre. ")
                        append(peers.joinToString { "${it.name} ${if (it.online) "online" else "offline"}" })
                    }
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val orbit = min(size.width, size.height) * 0.35f
            val nodeRadius = when {
                peers.size > 10 -> 16.dp.toPx()
                peers.size > 6 -> 20.dp.toPx()
                else -> 25.dp.toPx()
            }
            val centerRadius = 39.dp.toPx()
            val shownPeers = peers

            shownPeers.forEachIndexed { index, peer ->
                val angle = -PI / 2 + (2 * PI * index / shownPeers.size.coerceAtLeast(1))
                val node = Offset(
                    x = center.x + (cos(angle) * orbit).toFloat(),
                    y = center.y + (sin(angle) * orbit).toFloat(),
                )

                drawLine(
                    color = if (peer.online) spoke.copy(alpha = 0.65f) else line,
                    start = center,
                    end = node,
                    strokeWidth = if (peer.online) 2.5.dp.toPx() else 1.5.dp.toPx(),
                )
                drawCircle(
                    color = if (peer.online) spoke.copy(alpha = 0.14f) else Color.Transparent,
                    radius = nodeRadius + 4.dp.toPx(),
                    center = node,
                )
                drawCircle(
                    color = if (peer.online) spoke else line,
                    radius = nodeRadius,
                    center = node,
                )
                if (peer.syncing) {
                    val ringRadius = nodeRadius + 6.dp.toPx()
                    val strokeWidth = 3.dp.toPx()
                    drawCircle(
                        color = line.copy(alpha = 0.35f),
                        radius = ringRadius,
                        center = node,
                        style = Stroke(width = strokeWidth),
                    )
                    val progress = peer.syncProgress
                    drawArc(
                        color = spoke,
                        startAngle = if (progress == null) progressRotation - 90f else -90f,
                        sweepAngle = if (progress == null) 105f else 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(node.x - ringRadius, node.y - ringRadius),
                        size = Size(ringRadius * 2, ringRadius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    peer.initials,
                    node.x,
                    node.y + 5.dp.toPx(),
                    Paint().apply {
                        color = Color.White.toArgb()
                        textAlign = Paint.Align.CENTER
                        textSize = 12.dp.toPx()
                        isFakeBoldText = true
                        isAntiAlias = true
                    },
                )
                if (!peer.online) {
                    val timestampLines = peer.lastOnlineAtMillis.lastOnlineLines()
                    val timestampPaint = Paint().apply {
                        color = mutedText.toArgb()
                        textAlign = Paint.Align.CENTER
                        textSize = 8.5.dp.toPx()
                        isAntiAlias = true
                    }
                    timestampLines.forEachIndexed { lineIndex, label ->
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            node.x,
                            node.y + nodeRadius + (13 + lineIndex * 11).dp.toPx(),
                            timestampPaint,
                        )
                    }
                }
            }

            drawCircle(color = surface, radius = centerRadius + 5.dp.toPx(), center = center)
            drawCircle(color = hub, radius = centerRadius, center = center)
            drawCircle(
                color = spoke,
                radius = centerRadius,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
            val currentDeviceLines = currentDevice.nodeLabelLines()
            val centerLabelPaint = Paint().apply {
                color = if (hub.luminance() > 0.5f) Color.Black.toArgb() else Color.White.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 9.dp.toPx()
                isFakeBoldText = true
                isAntiAlias = true
            }
            currentDeviceLines.forEachIndexed { index, label ->
                val lineOffset = if (currentDeviceLines.size == 1) 3.dp.toPx() else {
                    (-3.5f + index * 11f).dp.toPx()
                }
                drawContext.canvas.nativeCanvas.drawText(label, center.x, center.y + lineOffset, centerLabelPaint)
            }
            drawContext.canvas.nativeCanvas.drawText(
                "This device",
                center.x,
                center.y + centerRadius + 17.dp.toPx(),
                Paint().apply {
                    color = mutedText.toArgb()
                    textAlign = Paint.Align.CENTER
                    textSize = 10.dp.toPx()
                    isAntiAlias = true
                },
            )
        }
        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.Center)
                .size(96.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onCurrentDeviceLongPress,
                    onLongClickLabel = "Update device name",
                )
                .semantics {
                    contentDescription = "$currentDevice, this device"
                    onLongClick("Update device name") {
                        onCurrentDeviceLongPress()
                        true
                    }
                },
        )
    }
}

private fun String.nodeLabelLines(): List<String> {
    val clean = trim().replace(Regex("\\s+"), " ")
    if (clean.length <= 12) return listOf(clean)
    val splitAt = clean.indices
        .filter { clean[it] == ' ' && it in 4..12 }
        .minByOrNull { kotlin.math.abs(it - clean.length / 2) }
        ?: 12
    val first = clean.take(splitAt).trim().take(12)
    val remainder = clean.drop(if (clean.getOrNull(splitAt) == ' ') splitAt + 1 else splitAt).trim()
    val second = if (remainder.length <= 12) remainder else remainder.take(11).trimEnd() + "…"
    return listOf(first, second).filter(String::isNotEmpty)
}

private fun Long?.lastOnlineLines(): List<String> {
    if (this == null) return listOf("Last online: Unknown")
    val instant = Date(this)
    return listOf(
        "Last online: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(instant)}",
        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(instant),
    )
}

private fun Color.luminance(): Float =
    (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)
