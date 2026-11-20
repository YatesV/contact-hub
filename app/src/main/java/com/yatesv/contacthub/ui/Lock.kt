package com.yatesv.contacthub.ui

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.yatesv.contacthub.Hub
import java.security.MessageDigest

fun hashPin(pin: String): String =
    MessageDigest.getInstance("SHA-256").digest(("contacthub:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }

fun canUseBiometric(act: FragmentActivity) =
    BiometricManager.from(act).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun biometricPrompt(act: FragmentActivity, title: String, onOk: () -> Unit) {
    val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build(),
    )
}

/** Hidden chats always need your fingerprint or PIN. */
fun unlockHidden(act: MainActivity, onOk: () -> Unit) {
    if (Nav.hiddenUnlocked) { onOk(); return }
    val s = Hub.settings.value
    when {
        s.useBiometric && canUseBiometric(act) -> biometricPrompt(act, "Unlock hidden chats") { Nav.hiddenUnlocked = true; onOk() }
        s.pinHash.isNotBlank() -> { Nav.openHiddenAfterUnlock = true; Nav.locked = true }
        else -> Toast.makeText(act, "Set a PIN or fingerprint in Settings > Privacy first", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun LockScreen(act: FragmentActivity, onUnlock: () -> Unit) {
    val s = Hub.settings.value
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bio = s.useBiometric && canUseBiometric(act)
    LaunchedEffect(Unit) { if (bio) biometricPrompt(act, "Unlock Orbit", onUnlock) }

    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Orbit is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        if (s.pinHash.isNotBlank()) {
            Text("•".repeat(pin.length).ifEmpty { " " }, fontSize = 32.sp, letterSpacing = 6.sp)
            if (error) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            val keys = listOf("123", "456", "789", "<0✓")
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { k ->
                        TextButton({
                            error = false
                            when (k) {
                                '<' -> pin = pin.dropLast(1)
                                '✓' -> if (hashPin(pin) == s.pinHash) onUnlock() else { error = true; pin = "" }
                                else -> if (pin.length < 12) {
                                    pin += k
                                    if (pin.length >= 4 && hashPin(pin) == s.pinHash) onUnlock()
                                }
                            }
                        }, Modifier.size(80.dp)) { Text(if (k == '<') "⌫" else k.toString(), fontSize = 28.sp) }
                    }
                }
            }
        }
        if (bio) TextButton({ biometricPrompt(act, "Unlock Orbit", onUnlock) }) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.size(8.dp)); Text("Use fingerprint")
        }
        if (!bio && s.pinHash.isBlank()) TextButton(onUnlock) { Text("Open") }
    }
}
