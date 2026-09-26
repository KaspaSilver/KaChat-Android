package com.kachat.app.util

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kachat.app.R

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Gates [onSuccess] behind whatever the device's own lock screen is set to (fingerprint/face
 * unlock or PIN/pattern/password) — used for the wallet's most sensitive actions (viewing the
 * seed phrase, unlocking a saved account after logout) so they can't be reached by anyone who
 * just picked up an already-unlocked phone.
 *
 * A phone with no secure lock screen at all used to fall straight through to [onSuccess]: the
 * "gate" on the recovery phrase was then no gate, and anyone holding the unlocked phone could
 * read it. It now refuses, says what to set, and calls [onFailure] — matching iOS's `DeviceAuth`.
 */
fun Context.authenticateWithDeviceCredential(
    title: String,
    subtitle: String? = null,
    onSuccess: () -> Unit,
    onFailure: () -> Unit = {}
) {
    val activity = findFragmentActivity()
    if (activity == null) {
        // MainActivity is an AppCompatActivity, so this is unreachable in the app as it stands.
        // Refusing rather than passing keeps it that way: a future host with no FragmentActivity
        // must not silently turn the gate off.
        onFailure()
        return
    }
    val allowedAuthenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowedAuthenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        presentScreenLockRequired(activity)
        onFailure()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { subtitle?.let { setSubtitle(it) } }
        .setAllowedAuthenticators(allowedAuthenticators)
        .build()
    prompt.authenticate(promptInfo)
}

/**
 * One dialog, from wherever the reveal was asked: the callers are spread over several screens
 * that have no shared message surface. Mirrors iOS's `presentPasscodeRequired`, naming Android's
 * own setting instead of the iPhone's.
 */
private fun presentScreenLockRequired(activity: FragmentActivity) {
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.screen_lock_required))
        .setMessage(activity.getString(R.string.screen_lock_required_message))
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
