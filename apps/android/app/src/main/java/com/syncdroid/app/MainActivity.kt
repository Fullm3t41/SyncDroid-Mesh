package com.syncdroid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.syncdroid.app.ui.SyncDroidApp
import com.syncdroid.app.service.SyncServiceController
import com.syncdroid.app.update.AndroidUpdateProvider

class MainActivity : ComponentActivity() {
    private val openFoldersRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidUpdateProvider.schedule(this, checkNow = true)
        handleNavigationIntent(intent)
        setContent { SyncDroidApp(openFoldersRequest = openFoldersRequest.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        SyncServiceController.setAppInForeground(true)
    }

    override fun onResume() {
        super.onResume()
        SyncServiceController.start(this)
    }

    override fun onStop() {
        SyncServiceController.setAppInForeground(false)
        super.onStop()
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_FOLDERS) openFoldersRequest.intValue++
    }

    companion object {
        const val ACTION_OPEN_FOLDERS = "com.syncdroid.app.action.OPEN_FOLDERS"
    }
}
