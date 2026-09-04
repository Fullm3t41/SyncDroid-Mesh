package com.syncdows.app.ui

import kotlin.test.Test
import kotlin.test.assertTrue

class MeshMapLayoutTest {
    @Test fun allDeviceLabelsFitWithoutOverlappingAtEveryWindowSize() {
        for (width in listOf(120, 280, 400, 550, 800, 1200)) {
            for (peers in 0..64) {
                for (scale in listOf(1, 2, 3)) {
                    val padding = minOf(20, width / 4)
                    val nodeWidth = minOf(156 * scale, width - padding * 2)
                    val heights = (0..peers).map { (100 + (it % 5) * 45) * scale }
                    val plan = planMeshMap(width, nodeWidth, heights, padding, 24, 420)
                    val context = "width=$width peers=$peers scale=$scale"
                    plan.positions.forEachIndexed { i, position ->
                        assertTrue(position.x >= 0 && position.x + nodeWidth <= width, context)
                        assertTrue(position.y >= 0 && position.y + heights[i] <= plan.height, context)
                        for (j in 0 until i) {
                            val other = plan.positions[j]
                            assertTrue(position.x >= other.x + nodeWidth || other.x >= position.x + nodeWidth ||
                                position.y >= other.y + heights[j] || other.y >= position.y + heights[i], context)
                        }
                    }
                }
            }
        }
    }
}
