package com.syncdroid.app.cloud

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import com.syncdroid.shared.cloud.CloudProvider
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService

class CloudSignInActivity : ComponentActivity() {
    private val accounts by lazy { AndroidCloudAccounts.get(this) }
    private val service by lazy { AuthorizationService(this) }
    private val googleResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        finishResult {
            check(result.resultCode == Activity.RESULT_OK) { "Sign-in was cancelled" }
            accounts.saveGoogle(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data))
        }
    }
    private val microsoftResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        finishResult {
            check(result.resultCode == Activity.RESULT_OK) { "Sign-in was cancelled" }
            accounts.completeMicrosoft(requireNotNull(result.data), service)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            try {
                when (CloudProvider.valueOf(requireNotNull(intent.getStringExtra("provider")))) {
                    CloudProvider.GOOGLE_DRIVE -> {
                        val result = accounts.authorizeGoogle()
                        if (result.hasResolution()) googleResult.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build())
                        else { accounts.saveGoogle(result); finish() }
                    }
                    CloudProvider.ONE_DRIVE -> microsoftResult.launch(accounts.microsoftIntent(service))
                }
            } catch (error: Exception) { showError(error); finish() }
        }
    }
    private fun finishResult(block: suspend () -> Unit) = lifecycleScope.launch {
        try { block() } catch (error: Exception) { showError(error) } finally { finish() }
    }
    private fun showError(error: Exception) { Toast.makeText(this, error.message ?: "Cloud sign-in failed", Toast.LENGTH_LONG).show() }
    override fun onDestroy() { service.dispose(); super.onDestroy() }
}
