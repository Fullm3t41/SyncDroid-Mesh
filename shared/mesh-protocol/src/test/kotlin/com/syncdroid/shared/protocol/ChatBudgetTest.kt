package com.syncdroid.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatBudgetTest {
    @Test
    fun shortHistoryIsKeptWhole() {
        val messages = List(5_000) { "message $it" }

        assertEquals(messages, newestChatWithinBudget(messages) { it.length })
    }

    @Test
    fun longHistoryKeepsTheNewestMessagesInOrder() {
        val messages = List(5_000) { index -> "$index:" + "x".repeat(3_990) }

        val kept = newestChatWithinBudget(messages) { it.length }

        assertTrue(kept.size in 1 until messages.size)
        assertEquals(messages.takeLast(kept.size), kept)
    }
}
