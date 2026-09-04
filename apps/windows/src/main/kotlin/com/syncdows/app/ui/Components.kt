package com.syncdows.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syncdows.app.model.MeshPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun WifiSuggestionBanner(
    ssid: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Add this Wi-Fi network?", style = MaterialTheme.typography.titleMedium)
                Text(ssid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onNo) { Text("No") }
            TextButton(onClick = onYes) { Text("Yes") }
        }
    }
}

@Composable
fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (alert) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EmptyStateCard(title: String, detail: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.padding(13.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(11.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SelectablePill(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(13.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun ExpandableInfoCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    containerColor: Color? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor ?: MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                Spacer(Modifier.height(14.dp))
                body()
            }
        }
    }
}

@Composable
fun LocalMeshView(
    currentDevice: String,
    peers: List<MeshPeer>,
    onRenameCurrentDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outline
    val hub = MaterialTheme.colorScheme.onSurface
    val spoke = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progressRotation = rememberInfiniteTransition(label = "mesh progress").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "mesh progress rotation",
    ).value

    androidx.compose.ui.layout.SubcomposeLayout(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(surface),
    ) { constraints ->
        val width = constraints.maxWidth
        val padding = minOf(20.dp.roundToPx(), width / 4)
        val gap = 24.dp.roundToPx()
        val nodeWidth = minOf(156.dp.roundToPx(), (width - padding * 2).coerceAtLeast(1))
        val nodes = subcompose("devices") {
            Column(
                Modifier.combinedClickable(onClick = {}, onLongClick = onRenameCurrentDevice),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(82.dp),
                    color = hub,
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(3.dp, spoke),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(currentDevice.trim().split(Regex("\\s+"))
                            .filter(String::isNotBlank).take(2)
                            .joinToString("") { it.first().uppercase() }.ifBlank { "?" },
                            color = MaterialTheme.colorScheme.surface,
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(currentDevice, style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(surface))
                Text("This device", style = MaterialTheme.typography.bodySmall,
                    color = muted, textAlign = TextAlign.Center, modifier = Modifier.background(surface))
            }
            peers.forEach { peer ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                        if (peer.syncing) {
                            Canvas(Modifier.matchParentSize()) {
                                val inset = 2.dp.toPx()
                                drawCircle(color = line.copy(alpha = 0.35f),
                                    radius = size.minDimension / 2f - inset,
                                    style = Stroke(width = 3.dp.toPx()))
                                drawArc(color = spoke,
                                    startAngle = if (peer.syncProgress == null) progressRotation - 90f else -90f,
                                    sweepAngle = peer.syncProgress?.let { 360f * it.coerceIn(0f, 1f) } ?: 105f,
                                    useCenter = false,
                                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                        Surface(Modifier.size(48.dp), color = if (peer.online) spoke else line, shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(peer.initials, color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(peer.name, style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(surface))
                    val detail = when {
                        peer.syncing -> peer.syncProgress?.let { "Syncing ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: "Syncing…"
                        peer.online -> "Online"
                        else -> peer.lastOnlineAtMillis.lastOnlineLabel()
                    }
                    Text(detail, style = MaterialTheme.typography.labelSmall,
                        color = muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(surface))
                }
            }
        }.map { it.measure(androidx.compose.ui.unit.Constraints.fixedWidth(nodeWidth)) }
        val plan = planMeshMap(width, nodeWidth, nodes.map { it.height }, padding, gap, 420.dp.roundToPx())
        val canvas = subcompose("connections") {
            Canvas(Modifier) {
                val local = plan.positions[0]
                val origin = androidx.compose.ui.geometry.Offset(local.x + nodeWidth / 2f, local.y + 41.dp.toPx())
                peers.forEachIndexed { index, peer ->
                    val position = plan.positions[index + 1]
                    drawLine(
                        color = if (peer.online) spoke.copy(alpha = 0.65f) else line,
                        start = origin,
                        end = androidx.compose.ui.geometry.Offset(position.x + nodeWidth / 2f, position.y + 31.dp.toPx()),
                        strokeWidth = if (peer.online) 2.5.dp.toPx() else 1.5.dp.toPx(),
                        pathEffect = if (peer.online) null else PathEffect.dashPathEffect(floatArrayOf(7f, 6f)),
                    )
                }
            }
        }.single().measure(androidx.compose.ui.unit.Constraints.fixed(width, plan.height))
        layout(width, plan.height) {
            canvas.place(0, 0)
            nodes.forEachIndexed { index, node ->
                val position = plan.positions[index]
                node.place(position.x, position.y)
            }
        }
    }
}

private fun Long?.lastOnlineLabel(): String {
    if (this == null) return "Last online: Unknown"
    val date = Date(this)
    return "Last online: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)}\n" +
        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(date)
}
