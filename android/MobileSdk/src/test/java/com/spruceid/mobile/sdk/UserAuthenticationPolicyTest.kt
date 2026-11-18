package com.spruceid.mobile.sdk

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAuthenticationPolicyTest {

    @Test
    fun defaults() {
        val policy = UserAuthenticationPolicy()

        assertEquals(30, policy.timeoutSeconds)
        assertTrue(policy.allowDeviceCredential)
    }

    @Test
    fun keystoreAuthenticatorsWithDeviceCredential() {
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            UserAuthenticationPolicy(allowDeviceCredential = true).keystoreAuthenticators()
        )
    }

    @Test
    fun keystoreAuthenticatorsBiometricOnly() {
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG,
            UserAuthenticationPolicy(allowDeviceCredential = false).keystoreAuthenticators()
        )
    }

    @Test
    fun promptAuthenticatorsFollowPolicy() {
        assertEquals(
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
            UserAuthenticationPolicy(allowDeviceCredential = true).promptAuthenticators()
        )
        assertEquals(
            BIOMETRIC_STRONG,
            UserAuthenticationPolicy(allowDeviceCredential = false).promptAuthenticators()
        )
    }

    @Test
    fun timeoutMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            UserAuthenticationPolicy(timeoutSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserAuthenticationPolicy(timeoutSeconds = -1)
        }
    }

    @Test
    fun promptUsesBiometricOnlyOnApi28And29WithStrongBiometric() {
        val requested = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        for (sdk in listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.Q)) {
            assertEquals(
                PromptMethod.Biometric(BIOMETRIC_STRONG),
                promptMethod(requested, sdk, strongBiometricAvailable = true)
            )
        }
    }

    @Test
    fun promptConfirmsDeviceCredentialOnApi28And29WithoutStrongBiometric() {
        val requested = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        for (sdk in listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.Q)) {
            assertEquals(
                PromptMethod.ConfirmDeviceCredential,
                promptMethod(requested, sdk, strongBiometricAvailable = false)
            )
        }
    }

    @Test
    fun promptKeepsRequestedAuthenticatorsWhereSupported() {
        val requested = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        assertEquals(
            PromptMethod.Biometric(requested),
            promptMethod(requested, Build.VERSION_CODES.O, strongBiometricAvailable = false)
        )
        assertEquals(
            PromptMethod.Biometric(requested),
            promptMethod(requested, Build.VERSION_CODES.R, strongBiometricAvailable = false)
        )
        assertEquals(
            PromptMethod.Biometric(BIOMETRIC_STRONG),
            promptMethod(BIOMETRIC_STRONG, Build.VERSION_CODES.Q, strongBiometricAvailable = false)
        )
    }

    @Test
    fun promptConfirmsDeviceCredentialAloneBelowApi30() {
        assertEquals(
            PromptMethod.ConfirmDeviceCredential,
            promptMethod(DEVICE_CREDENTIAL, Build.VERSION_CODES.Q, strongBiometricAvailable = true)
        )
        assertEquals(
            PromptMethod.Biometric(DEVICE_CREDENTIAL),
            promptMethod(DEVICE_CREDENTIAL, Build.VERSION_CODES.R, strongBiometricAvailable = false)
        )
    }

    @Test
    fun userAuthenticationRequiredMessageHasPrefix() {
        val e = UserAuthenticationRequiredException("alias")

        assertTrue(e.message!!.startsWith(UserAuthenticationRequiredException.MESSAGE_PREFIX))
        assertEquals("alias", e.alias)
    }

    @Test
    fun userAuthenticationUnavailableMessageHasPrefix() {
        val created = UserAuthenticationUnavailableException("alias")
        val invalidated = UserAuthenticationUnavailableException(
            "alias", null, UserAuthenticationUnavailableException.INVALIDATED_DETAIL
        )

        for (e in listOf(created, invalidated)) {
            assertTrue(e.message!!.startsWith(UserAuthenticationUnavailableException.MESSAGE_PREFIX))
            assertEquals("alias", e.alias)
        }
        assertTrue(invalidated.message!!.contains("invalidated"))
    }
}
