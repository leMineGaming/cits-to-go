package org.opentrafficmap.citstogo.bridge

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What plugging in the receiver may start on its own, and where an automatic recording lands.
 *
 * The decision and the naming live here, away from the activity, so the matrix of the two settings
 * against the states of the app is testable on its own. [org.opentrafficmap.citstogo.MainActivity]
 * asks for a [Plan] and acts on it.
 */
object AutoRecording {
    /** The type the manual **Record PCAP** picker asks the storage provider for, so both write the same files. */
    const val MIME_TYPE = "application/vnd.tcpdump.pcap"

    /** Shown in the activity log when recording was asked for without a folder; the connection still starts. */
    const val MISSING_FOLDER_NOTICE = "Auto PCAP needs a folder. Choose one in Settings."

    /** Shown by the bridge when the plug-in found a capture already running; that capture is left alone. */
    const val BUSY_NOTICE = "Another capture is running. The plug-in start was ignored."

    /** Shown by the bridge when it ends a capture on its own because the receiver stayed away. */
    const val UNPLUGGED_NOTICE = "Receiver unplugged. Capture and PCAP recording stopped."

    /** Espressif's USB vendor id, which the XIAO ESP32-C5 uses for its ROM download and its serial. */
    const val RECEIVER_VENDOR_ID = 0x303a

    /**
     * Whether a plugged-in USB device is the C-ITS receiver rather than a charger, hub or sound card.
     * The manifest device filter also matches plain CDC and vendor specific classes, so the plug-in
     * trigger cannot rely on it; the id decides. The value is taken on its own so the check stays off
     * [android.hardware.usb.UsbDevice] and remains testable.
     */
    fun isReceiver(vendorId: Int): Boolean = vendorId == RECEIVER_VENDOR_ID

    /** The built-in shared storage, which every provider names `primary` in front of the path. */
    private const val PRIMARY_VOLUME = "primary"

    private const val TREE_PATH_MARKER = "/tree/"

    private val nameFormat = DateTimeFormatter.ofPattern("'cits-'uuuuMMdd'-'HHmmss'.pcap'")

    /**
     * `cits-20261017-203012.pcap`. Local time, because the file is looked for in a file manager next to
     * the moment the cable went in. Seconds keep two plug-ins in the same minute apart, and a provider
     * that already holds the name appends a counter of its own.
     */
    fun fileName(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        nameFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * What the plug-in should start.
     *
     * Recording follows the connection, so a disabled or overridden auto-connect never records either.
     * A connection the user started by hand is left alone, a replay owns the sink, the flasher owns the
     * USB port while it runs, and Bluetooth has no plug-in event to key off, so its setting is ignored.
     * The bridge service re-checks the running and replay states of its own, because the activity does
     * not know them yet when the plug-in intent is the first thing it sees.
     */
    fun plan(
        autoConnectEnabled: Boolean,
        autoRecordEnabled: Boolean,
        folderChosen: Boolean,
        connectionActive: Boolean,
        replayActive: Boolean,
        usbTransport: Boolean,
        flashing: Boolean,
    ): Plan {
        if (!autoConnectEnabled || connectionActive || replayActive || flashing || !usbTransport) {
            return Plan(startConnection = false, record = false)
        }
        if (!autoRecordEnabled) return Plan(startConnection = true, record = false)
        if (!folderChosen) return Plan(startConnection = true, record = false, notice = MISSING_FOLDER_NOTICE)
        return Plan(startConnection = true, record = true)
    }

    data class Plan(
        val startConnection: Boolean,
        val record: Boolean,
        /** Why the recording did not happen, for the activity log; null when there is nothing to say. */
        val notice: String? = null,
    )

    /** Whether a stored setting still looks like a folder the app can create documents in. */
    fun isTreeUri(value: String?): Boolean =
        value != null && value.startsWith("content://") && value.contains(TREE_PATH_MARKER)

    /**
     * The folder a tree URI points at, spelled the way a file manager shows it:
     * `content://com.android.externalstorage.documents/tree/primary%3ADownload%2FCITS` gives
     * `Download/CITS`. Providers percent-encode the path, and the volume in front of the colon —
     * `primary`, or an SD card serial — is not part of the name the user picked, so it is decoded
     * first and dropped. An empty result means the label cannot be read; the caller shows its own.
     */
    fun folderLabel(treeUri: String?): String {
        if (treeUri == null) return ""
        val encoded = treeUri.substringAfterLast(TREE_PATH_MARKER, "").substringBefore('?')
        if (encoded.isBlank()) return ""
        val decoded = percentDecode(encoded)
        val volume = decoded.substringBefore(':', decoded)
        val path = decoded.substringAfter(':', "").trim('/', '\\')
        return when {
            path.isNotBlank() -> path
            volume == PRIMARY_VOLUME -> "Internal storage"
            else -> volume
        }
    }

    /** Percent decoding for the few bytes a document URI carries; `+` stays a plus, unlike a form decode. */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.write(code)
                    index += 3
                    continue
                }
            }
            bytes.write(char.code)
            index += 1
        }
        return runCatching { bytes.toString("UTF-8") }.getOrDefault(value)
    }
}
