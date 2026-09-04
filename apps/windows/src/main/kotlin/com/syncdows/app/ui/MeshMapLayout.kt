package com.syncdows.app.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal data class MeshMapPosition(val x: Int, val y: Int)
internal data class MeshMapPlan(val height: Int, val positions: List<MeshMapPosition>)

/** Heights include every wrapped label. Item zero is the local device. */
internal fun planMeshMap(
    width: Int,
    nodeWidth: Int,
    heights: List<Int>,
    padding: Int,
    gap: Int,
    minimumHeight: Int,
): MeshMapPlan {
    require(heights.isNotEmpty())
    val count = heights.size - 1
    val radiusX = (width - padding * 2 - nodeWidth) / 2.0
    val xs = listOf(width / 2.0) + (0 until count).map {
        width / 2.0 + radiusX * cos(-PI / 2 + 2 * PI * it / count)
    }
    val ys = listOf(0.0) + (0 until count).map { sin(-PI / 2 + 2 * PI * it / count) }
    var radiusY = minimumHeight / 3.0
    var ringFits = count <= 12
    for (i in heights.indices) for (j in 0 until i) {
        if (abs(xs[i] - xs[j]) < nodeWidth + gap) {
            val separation = abs(ys[i] - ys[j])
            if (separation < 0.001) ringFits = false
            else radiusY = max(radiusY, ((heights[i] + heights[j]) / 2.0 + gap + 2) / separation)
        }
    }
    if (ringFits) {
        val tops = heights.indices.map { ys[it] * radiusY - heights[it] / 2.0 }
        val top = tops.min()
        val bottom = heights.indices.maxOf { tops[it] + heights[it] }
        val height = max(minimumHeight, ceil(bottom - top).toInt() + padding * 2)
        val extra = (height - (bottom - top) - padding * 2) / 2
        return MeshMapPlan(height, heights.indices.map {
            MeshMapPosition((xs[it] - nodeWidth / 2.0).toInt(),
                (tops[it] - top + padding + extra).toInt())
        })
    }

    // Preserve readable text at narrow widths and large device counts instead of shrinking nodes.
    val columns = ((width - padding * 2 + gap) / (nodeWidth + gap)).coerceAtLeast(1)
    val positions = mutableListOf(MeshMapPosition((width - nodeWidth) / 2, padding))
    var y = padding + heights[0] + gap
    for (row in (1 until heights.size).toList().chunked(columns)) {
        val rowWidth = row.size * nodeWidth + (row.size - 1) * gap
        row.forEachIndexed { column, _ ->
            positions += MeshMapPosition((width - rowWidth) / 2 + column * (nodeWidth + gap), y)
        }
        y += row.maxOf { heights[it] } + gap
    }
    return MeshMapPlan(max(minimumHeight, y - gap + padding), positions)
}
