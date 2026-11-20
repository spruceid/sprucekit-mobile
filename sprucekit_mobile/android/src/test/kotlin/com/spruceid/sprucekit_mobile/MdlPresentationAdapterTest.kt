package com.spruceid.sprucekit_mobile

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class MdlPresentationAdapterTest {
    private val states = mutableListOf<MdlPresentationState>()
    private lateinit var adapter: MdlPresentationAdapter

    @BeforeTest
    fun setUp() {
        // The adapter sends state updates to Dart from Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val flutterCallback = mockk<MdlPresentationCallback>()
        every { flutterCallback.onStateChange(any(), any()) } answers {
            states.add(firstArg<MdlPresentationStateUpdate>().state)
        }
        adapter = MdlPresentationAdapter(mockk(relaxed = true), mockk(relaxed = true))
        adapter.setCallback(flutterCallback)
        adapter.presentation = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * An app can end the session as soon as it sees SUCCESS, so SUCCESS must wait for the
     * transport to write the last chunk. submitNamespaces only queues the response.
     */
    @Test
    fun `success comes from the transport, not from submitNamespaces`() {
        var result: Result<MdlPresentationResult>? = null
        adapter.submitNamespaces(
            mapOf("org.iso.18013.5.1.mDL" to mapOf("org.iso.18013.5.1" to listOf("family_name")))
        ) { result = it }

        assertIs<MdlPresentationSuccess>(result?.getOrNull())
        assertEquals(listOf(MdlPresentationState.SENDING_RESPONSE), states)

        // The SDK's BLE session updates while the transport writes the chunks.
        val chunks = 18
        for (curr in 1 until chunks) {
            adapter.handleStateUpdate(mapOf("uploadProgress" to mapOf("curr" to curr, "max" to chunks)))
        }
        assertEquals(listOf(MdlPresentationState.SENDING_RESPONSE), states)

        adapter.handleStateUpdate(mapOf("success" to ""))
        assertEquals(
            listOf(MdlPresentationState.SENDING_RESPONSE, MdlPresentationState.SUCCESS),
            states
        )
    }
}
