package com.syncdroid.app.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CloudProgress(val busy: Boolean = false, val message: String = "Ready for cloud sync")
object AndroidCloudStatus {
    private val mutableState = MutableStateFlow(CloudProgress())
    val state = mutableState.asStateFlow()
    fun update(busy: Boolean, message: String) { mutableState.value = CloudProgress(busy, message) }
}
