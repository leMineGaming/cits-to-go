package org.opentrafficmap.citstogo.bridge

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecordingTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val plugInMs = Instant.parse("2026-10-17T18:30:12Z").toEpochMilli()

    @Test
    fun `names a recording after the local moment it started`() {
        assertEquals("cits-20261017-183012.pcap", AutoRecording.fileName(plugInMs, utc))
    }

    @Test
    fun `names two plug-ins a minute apart differently`() {
        assertNotEquals(
            AutoRecording.fileName(plugInMs, utc),
            AutoRecording.fileName(plugInMs + 61_000L, utc),
        )
        assertEquals("cits-20261017-183113.pcap", AutoRecording.fileName(plugInMs + 61_000L, utc))
    }

    @Test
    fun `reads a folder out of a tree uri`() {
        assertEquals(
            "Download/CITS",
            AutoRecording.folderLabel(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FCITS",
            ),
        )
    }

    @Test
    fun `reads an sd card folder without its serial prefix`() {
        assertEquals(
            "CITS/field",
            AutoRecording.folderLabel(
                "content://com.android.externalstorage.documents/tree/1234-5678%3ACITS%2Ffield",
            ),
        )
    }

    @Test
    fun `keeps a document id it cannot split into volume and path`() {
        assertEquals(
            "some/where",
            AutoRecording.folderLabel("content://com.android.externalstorage.documents/tree/some%2Fwhere"),
        )
    }

    @Test
    fun `names the built-in storage root when the picker chose nothing below it`() {
        assertEquals(
            "Internal storage",
            AutoRecording.folderLabel("content://com.android.externalstorage.documents/tree/primary%3A"),
        )
    }

    @Test
    fun `keeps a provider it cannot name`() {
        assertEquals(
            "com.example.provider",
            AutoRecording.folderLabel("content://com.example.provider/tree/com.example.provider"),
        )
    }

    @Test
    fun `has no label for anything that is not a tree`() {
        assertEquals("", AutoRecording.folderLabel(null))
        assertEquals("", AutoRecording.folderLabel(""))
        assertEquals("", AutoRecording.folderLabel("content://x.documents/document/primary%3Aa"))
    }

    @Test
    fun `accepts only tree uris as a stored folder`() {
        assertTrue(AutoRecording.isTreeUri("content://x.documents/tree/primary%3ADownload"))
        assertFalse(AutoRecording.isTreeUri("content://x.documents/document/primary%3ADownload"))
        assertFalse(AutoRecording.isTreeUri("file:///sdcard/Download"))
        assertFalse(AutoRecording.isTreeUri(""))
        assertFalse(AutoRecording.isTreeUri(null))
    }

    @Test
    fun `recognises only the Espressif receiver as a plug-in trigger`() {
        assertTrue(AutoRecording.isReceiver(AutoRecording.RECEIVER_VENDOR_ID))
        // A charger, hub or sound card also matches the manifest's class filter and must not start a capture.
        assertFalse(AutoRecording.isReceiver(0x04e8))
        assertFalse(AutoRecording.isReceiver(0))
    }

    @Test
    fun `starts both when the plug-in has nothing to yield to`() {
        val plan = planOf()
        assertTrue(plan.startConnection)
        assertTrue(plan.record)
        assertNull(plan.notice)
    }

    @Test
    fun `starts nothing while the user has a connection of their own`() {
        assertStartsNothing(planOf(connectionActive = true))
    }

    @Test
    fun `starts nothing while a replay runs`() {
        assertStartsNothing(planOf(replayActive = true))
    }

    @Test
    fun `starts nothing while the flasher holds the port`() {
        assertStartsNothing(planOf(flashing = true))
    }

    @Test
    fun `starts nothing for a plug-in when the app is set to Bluetooth`() {
        assertStartsNothing(planOf(usbTransport = false))
    }

    @Test
    fun `starts nothing when auto connect is off, even with recording on`() {
        assertStartsNothing(planOf(autoConnectEnabled = false))
    }

    @Test
    fun `connects without recording when recording is off`() {
        val plan = planOf(autoRecordEnabled = false, folderChosen = false)
        assertTrue(plan.startConnection)
        assertFalse(plan.record)
        assertNull(plan.notice)
    }

    @Test
    fun `connects and says why it did not record when no folder was chosen`() {
        val plan = planOf(folderChosen = false)
        assertTrue(plan.startConnection)
        assertFalse(plan.record)
        assertEquals(AutoRecording.MISSING_FOLDER_NOTICE, plan.notice)
    }

    private fun assertStartsNothing(plan: AutoRecording.Plan) {
        assertFalse(plan.startConnection)
        assertFalse(plan.record)
    }

    /** The plug-in defaults: both settings on, USB, nothing else going on. */
    private fun planOf(
        autoConnectEnabled: Boolean = true,
        autoRecordEnabled: Boolean = true,
        folderChosen: Boolean = true,
        connectionActive: Boolean = false,
        replayActive: Boolean = false,
        usbTransport: Boolean = true,
        flashing: Boolean = false,
    ) = AutoRecording.plan(
        autoConnectEnabled = autoConnectEnabled,
        autoRecordEnabled = autoRecordEnabled,
        folderChosen = folderChosen,
        connectionActive = connectionActive,
        replayActive = replayActive,
        usbTransport = usbTransport,
        flashing = flashing,
    )
}
