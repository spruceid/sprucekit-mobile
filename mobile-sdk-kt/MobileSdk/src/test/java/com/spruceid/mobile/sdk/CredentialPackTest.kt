package com.spruceid.mobile.sdk

import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class CredentialPackTest {
    @Test
    fun constructContentsFromUuidAndCredentials() {
        val uuid = UUID.randomUUID()
        val credentialId = UUID.randomUUID().toString()
        val credentials = listOf(credentialId)
        val packContents = CredentialPackContents(uuid, credentials)

        try {
            assertEquals(uuid, packContents.id)
            assertEquals(credentials, packContents.credentials)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}