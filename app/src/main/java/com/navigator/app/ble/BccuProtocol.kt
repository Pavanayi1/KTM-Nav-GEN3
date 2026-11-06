package com.navigator.app.ble

import java.util.UUID

/**
 * GATT layout and wire-format tables for the BCCU BLE protocol, confirmed
 * byte-exact from decompiled source of two independent apps. See
 * BCCU_BLE_PROTOCOL.md for the full write-up and provenance.
 */
object BccuProtocol {

    private fun uuid(suffix: String) = UUID.fromString("71ced1ac-$suffix-44f5-9454-806ff70b3e02")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val MAIN_SERVICE: UUID = uuid("0700")
    val AUTH_REQUEST: UUID = uuid("0701")
    val AUTH_REPLY: UUID = uuid("0702")
    val NAVIGATION_STATE: UUID = uuid("0703")
    val TURN_ICON: UUID = uuid("0704")
    val TURN_DISTANCE: UUID = uuid("0705")
    val TURN_INFO: UUID = uuid("0706")
    val TURN_ROAD: UUID = uuid("0707")
    val ETA: UUID = uuid("0708")
    val REMAINING_DISTANCE: UUID = uuid("0709")
    val NOTIFICATION: UUID = uuid("070a")
    val TBT_NAV_REQUEST: UUID = uuid("070b")
    val TBT_NAV_RESPONSE: UUID = uuid("070c")
    val LAST_DESTINATION: UUID = uuid("070d")
    val FAVORITE: UUID = uuid("070e")
    val ACTIVE_WAYPOINT: UUID = uuid("0711")

    val BASE_SERVICE: UUID = uuid("0000")
    val BASE_GET_VIN_REQUEST: UUID = uuid("0001")
    val BASE_VIN: UUID = uuid("0002")

    /** Standard Bluetooth SIG Device Information service (model/serial/fw/hw/sw revision). */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    // Undocumented services seen in the live GATT dump of bike KTM3638 but
    // absent from both reference apps' source (BCCU_BLE_PROTOCOL.md §9.1).
    // 0200 exposes six R/W/w/N/I characteristics (0201-0206) - the best
    // remaining lead for streaming telemetry on a bike without PRPC (0600).
    val MYSTERY_SERVICE_0200: UUID = uuid("0200")
    val MYSTERY_SERVICE_0300: UUID = uuid("0300")

    val RCM_SERVICE: UUID = uuid("0100")
    val RCM_REMOTE_CONTROL: UUID = uuid("0103")

    // Protobuf/telemetry RPC channel - confirmed from BleRpcTransport.java:
    // requests -> 0601, responses -> 0602, telemetry notifications -> 0603.
    // Same session frame()+AES scheme as MAIN_SERVICE (BCcuClient.tryEncrypt(tryFrameMessage(...))).
    val PRPC_SERVICE: UUID = uuid("0600")
    val PRPC_REQUEST: UUID = uuid("0601")
    val PRPC_RESPONSE: UUID = uuid("0602")
    val PRPC_NOTIFICATION: UUID = uuid("0603")

    // RPC opcodes, confirmed from CUKT_bCCU.json "opcodes" section.
    const val RPC_OPCODE_GET_VALUE = 1
    const val RPC_OPCODE_SET_VALUE = 2
    const val RPC_OPCODE_TELEMETRY_CONFIGURE = 3
    const val RPC_OPCODE_TELEMETRY_CONTROL = 4

    const val TELEMETRY_CMD_STOP = 0
    const val TELEMETRY_CMD_START = 1
    const val TELEMETRY_CMD_RESET = 2

    // Build an RPC request header+payload, confirmed byte-exact from
    // RPCRequestBuilder / RPCConfigureRequestBuilder / RPCControlRequestBuilder:
    // header is opcode (u16), sid (u8), payloadLength (u8), then the payload -
    // little-endian throughout.
    fun buildTelemetryConfigureRequest(sid: Int, datapointId: Int, sampleRateMs: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(4 + 6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RPC_OPCODE_TELEMETRY_CONFIGURE.toShort())
        buf.put(sid.toByte())
        buf.put(6.toByte())
        buf.putShort(datapointId.toShort())
        buf.putInt(sampleRateMs)
        return buf.array()
    }

    fun buildTelemetryControlRequest(sid: Int, command: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(4 + 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RPC_OPCODE_TELEMETRY_CONTROL.toShort())
        buf.put(sid.toByte())
        buf.put(2.toByte())
        buf.putShort(command.toShort())
        return buf.array()
    }

    data class TelemetryTriple(val timestampMs: Long, val datapointId: Int, val value: ByteArray)

    // Parse a decrypted PRPC_NOTIFICATION payload, confirmed from
    // PRpcClient.parsePRpcNotification / processNotificationTriples: header is
    // notifyCode (u16), length (u8), then triples; notifyCode==7 is "telemetryData"
    // and each triple is timestamp (u64), datapointId (u16), value (N bytes, N from schema).
    fun parseTelemetryNotification(data: ByteArray, typeLengthOf: (Int) -> Int): List<TelemetryTriple> {
        if (data.size < 4) return emptyList()
        val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val notifyCode = buf.short.toInt() and 0xFFFF
        buf.get() // length byte, unused - we just consume until the buffer is empty
        if (notifyCode != 7) return emptyList()
        val triples = mutableListOf<TelemetryTriple>()
        while (buf.remaining() >= 10) {
            val timestamp = buf.long
            val datapointId = buf.short.toInt() and 0xFFFF
            val valueLen = typeLengthOf(datapointId)
            if (valueLen <= 0 || buf.remaining() < valueLen) break
            val value = ByteArray(valueLen)
            buf.get(value)
            triples.add(TelemetryTriple(timestamp, datapointId, value))
        }
        return triples
    }

    // Handshake command codes (byte[2] of a decrypted 16-byte control message)
    const val CMD_HELLO = 0
    const val CMD_GENERATE_KEYS = 1
    const val CMD_KEY_ACK_BASE = 16 // 16 | keyIndex (0-15)

    enum class TurnIcon(val binary: Int) {
        UNKNOWN(0), UNDEFINED(1), GO_STRAIGHT(2), UTURN_RIGHT(3), UTURN_LEFT(4),
        KEEP_RIGHT(5), LIGHT_RIGHT(6), QUITE_RIGHT(7), HEAVY_RIGHT(8), KEEP_MIDDLE(9),
        KEEP_LEFT(10), LIGHT_LEFT(11), QUITE_LEFT(12), HEAVY_LEFT(13),
        ENTER_HIGHWAY_RIGHT_LANE(14), ENTER_HIGHWAY_LEFT_LANE(15),
        LEAVE_HIGHWAY_RIGHT_LANE(16), LEAVE_HIGHWAY_LEFT_LANE(17),
        HIGHWAY_KEEP_RIGHT(18), HIGHWAY_KEEP_LEFT(19),
        START(20), END(21), FERRY(22), PASS_STATION(23), HEAD_TO(24), CHANGE_LINE(25),
        RAB_SECT_1_RH(26), RAB_SECT_2_RH(27), RAB_SECT_3_RH(28), RAB_SECT_4_RH(29),
        RAB_SECT_5_RH(30), RAB_SECT_6_RH(31), RAB_SECT_7_RH(32), RAB_SECT_8_RH(33),
        RAB_SECT_9_RH(34), RAB_SECT_10_RH(35), RAB_SECT_11_RH(36), RAB_SECT_12_RH(37),
        RAB_SECT_13_RH(38), RAB_SECT_14_RH(39), RAB_SECT_15_RH(40), RAB_SECT_16_RH(41),
        RAB_SECT_1_LH(42), RAB_SECT_2_LH(43), RAB_SECT_3_LH(44), RAB_SECT_4_LH(45),
        RAB_SECT_5_LH(46), RAB_SECT_6_LH(47), RAB_SECT_7_LH(48), RAB_SECT_8_LH(49),
        RAB_SECT_9_LH(50), RAB_SECT_10_LH(51), RAB_SECT_11_LH(52), RAB_SECT_12_LH(53),
        RAB_SECT_13_LH(54), RAB_SECT_14_LH(55), RAB_SECT_15_LH(56), RAB_SECT_16_LH(57);

        companion object {
            fun fromBinary(b: Int): TurnIcon = entries.firstOrNull { it.binary == b } ?: UNKNOWN
        }
    }

    enum class NotificationIcon(val binary: Int) {
        UNKNOWN(0), NOTIFICATION_REROUTING(1), NOTIFICATION_WAYPOINT(2), TARGET_REACHED(3),
        GPS_LOST(4), WARNING(5), INFORMATION(6), SPEED(7);

        companion object {
            fun fromBinary(b: Int): NotificationIcon = entries.firstOrNull { it.binary == b } ?: UNKNOWN
        }
    }

    enum class HandlebarButton(val bitIndex: Int) {
        SET(0), BACK(1), DOWN(2), UP(3)
    }

    /** Confirmed from com.ktm.mob.services.etbt.Visibility.binary() - NOT a 0/1 boolean. */
    enum class Visibility(val binary: Int) {
        UNKNOWN(-1), FULL(3), HALF(2), OFF(1)
    }

    /** Confirmed from com.ktm.mob.services.etbt.impl.ble.characteristics.payloads.types.OnOff. */
    enum class OnOff(val binary: Int) {
        OFF(0), ON(1)
    }

    data class RcmState(val active: Boolean, val pressed: Map<HandlebarButton, Boolean>)

    /** characteristicValue must be the decrypted+unframed RCM notification payload. */
    fun parseRcmValue(v: ByteArray): RcmState {
        val active = v.size > 16 && v[16] == 0xFF.toByte()
        val mask = if (v.size > 17) v[17].toInt() else 0
        val pressed = HandlebarButton.entries.associateWith { (mask shr it.bitIndex) and 1 == 1 }
        return RcmState(active, pressed)
    }

    /** Build a TURN_ICON characteristic payload: [visibility][iconByte]. */
    fun buildTurnIconPayload(icon: TurnIcon, visibility: Visibility = Visibility.FULL): ByteArray =
        byteArrayOf(visibility.binary.toByte(), icon.binary.toByte())

    /** Build a NOTIFICATION characteristic payload: [visibility][iconByte][UTF-8 text, max 16 chars]. */
    fun buildNotificationPayload(icon: NotificationIcon, text: String, visibility: Visibility = Visibility.FULL): ByteArray {
        val truncated = if (text.length > 16) text.substring(0, 16) else text
        val textBytes = truncated.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + textBytes.size)
        out[0] = visibility.binary.toByte()
        out[1] = icon.binary.toByte()
        System.arraycopy(textBytes, 0, out, 2, textBytes.size)
        return out
    }

    /**
     * Build a NAVIGATION_STATE payload: [byte0: bit0=guidanceOn bit1=gpsIconOn][byte1: volume 0-100 or 255=unset].
     * Confirmed from com.ktm...NavigationState.serialize(). The dash appears to require
     * guidanceOn=ON before it will render TURN_ICON/TURN_INFO/NOTIFICATION content.
     */
    fun buildNavigationStatePayload(guidanceOn: Boolean, gpsIconOn: Boolean, volume: Int = 255): ByteArray {
        val flags = (if (guidanceOn) OnOff.ON.binary else OnOff.OFF.binary) or
            ((if (gpsIconOn) OnOff.ON.binary else OnOff.OFF.binary) shl 1)
        return byteArrayOf(flags.toByte(), (volume and 0xFF).toByte())
    }

    /**
     * Truncation confirmed from com.ktm.mob.utils.StrElipsed.elipse(): if the string
     * already fits, leave it; otherwise cut to maxLen-3 chars and append "...".
     * Operates on String.length() (UTF-16 code units), same as the source.
     */
    private fun elipse(text: String, maxLen: Int): String = when {
        text.length <= maxLen -> text
        maxLen <= 3 -> text.substring(0, maxLen)
        else -> text.substring(0, maxLen - 3) + "..."
    }

    /**
     * How many characters each center text slot holds, confirmed from the
     * official app's own label classes (TurnDistance/TurnInfo/TurnRoad/Eta/
     * Dist2Target). Public because the service scrolls text that overflows a
     * slot rather than letting [elipse] cut it to "Head tow..." - the window it
     * slides has to be exactly this wide.
     */
    const val TURN_DISTANCE_MAX = 8
    const val TURN_INFO_MAX = 16
    const val TURN_ROAD_MAX = 32
    const val ETA_MAX = 8
    const val REMAINING_DISTANCE_MAX = 8

    /** Shared [visibility][UTF-8 text] shape used by TURN_DISTANCE/TURN_INFO/TURN_ROAD/ETA/REMAINING_DISTANCE. */
    private fun buildLabelPayload(text: String, maxLen: Int, visibility: Visibility): ByteArray {
        val textBytes = elipse(text, maxLen).toByteArray(Charsets.UTF_8)
        return byteArrayOf(visibility.binary.toByte()) + textBytes
    }

    /**
     * TURN_DISTANCE (0705): distance-to-next-turn label shown in the dash's
     * center guidance view, e.g. "110 m". Confirmed from TurnDistance.java
     * (max 8 chars) - the official app builds the text as "$turnDist $turnDistUnit".
     */
    fun buildTurnDistancePayload(text: String, visibility: Visibility = Visibility.FULL) =
        buildLabelPayload(text, TURN_DISTANCE_MAX, visibility)

    /**
     * TURN_INFO (0706): secondary maneuver text (Sygic's GuidanceManeuver.turnInfo).
     * Confirmed from TurnInfo.java (max 16 chars).
     */
    fun buildTurnInfoPayload(text: String, visibility: Visibility = Visibility.FULL) =
        buildLabelPayload(text, TURN_INFO_MAX, visibility)

    /**
     * TURN_ROAD (0707): street/road name shown in the dash's center guidance
     * view, e.g. "towards 1st Cross Rd". Confirmed from TurnRoad.java (max 32 chars).
     */
    fun buildTurnRoadPayload(text: String, visibility: Visibility = Visibility.FULL) =
        buildLabelPayload(text, TURN_ROAD_MAX, visibility)

    /** ETA (0708): arrival time label, e.g. "12:45". Confirmed from Eta.java (max 8 chars). */
    fun buildEtaPayload(text: String, visibility: Visibility = Visibility.FULL) =
        buildLabelPayload(text, ETA_MAX, visibility)

    /**
     * REMAINING_DISTANCE (0709): total remaining trip distance, e.g. "123 km".
     * Confirmed from Dist2Target.java (max 8 chars).
     */
    fun buildRemainingDistancePayload(text: String, visibility: Visibility = Visibility.FULL) =
        buildLabelPayload(text, REMAINING_DISTANCE_MAX, visibility)

    // ---------------------------------------------------------------------
    // Dash-driven navigation: the cluster's own Favourites menu
    // ---------------------------------------------------------------------
    //
    // The dash browses destinations itself. It asks on TBT_NAV_REQUEST (070b,
    // bike -> phone notify), the phone answers on FAVORITE (070e) /
    // LAST_DESTINATION (070d), and when the rider presses SET on an entry the
    // dash sends START_NAVIGATION carrying that entry's id back.
    //
    // Everything below is transcribed from the official app's own serialisers
    // in com.ktm.mob.services.etbt.impl.ble.characteristics.payloads - not
    // inferred from traffic. Sources are named per declaration.

    /**
     * Requests the dash sends on 070b. Byte values from `RequestType.java`.
     * VOLUME_UNMUTE is 0x80, which is -128 as a signed Java byte - it is a
     * plain unsigned 0x80 on the wire.
     */
    enum class NavRequestType(val binary: Int) {
        START_NAVIGATION(1),
        STOP_NAVIGATION(2),
        SKIP_ACTIVE_WAYPOINT(4),
        GET_LAST_DESTINATIONS(5),
        GET_FAVORITES(6),
        GET_POI_CATEGORIES(7),
        GET_POIS_BY_CATEGORY(8),
        GET_ACTIVE_WAYPOINT(10),
        VOLUME_UP(16),
        VOLUME_DOWN(32),
        VOLUME_MUTE(64),
        VOLUME_UNMUTE(128);

        companion object {
            fun fromBinary(b: Int): NavRequestType? = entries.firstOrNull { it.binary == b }
        }
    }

    /**
     * A decoded 070b request. [argument] is the request's single trailing byte
     * where it has one - the waypoint id for START_NAVIGATION
     * (`StartNavigationRequest.java`), the category for GET_POIS_BY_CATEGORY -
     * and null for the no-argument requests.
     */
    data class NavRequest(val type: NavRequestType, val argument: Int?)

    /** Decode a 070b frame, or null if it isn't a request shape we know. */
    fun parseNavRequest(data: ByteArray): NavRequest? {
        if (data.isEmpty()) return null
        val type = NavRequestType.fromBinary(data[0].toInt() and 0xFF) ?: return null
        val arg = if (data.size >= 2) data[1].toInt() and 0xFF else null
        return NavRequest(type, arg)
    }

    /** HTTP-style response codes, from `ResponseBase.java` (valid range 100..599). */
    object NavResponseCode {
        const val TRYING = 100
        const val OK = 200
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 500
    }

    /**
     * `LengthString.serialize()`: one length byte then UTF-8 bytes, or a lone
     * 0xFF for null. The length byte counts BYTES, not characters, and the
     * official app truncates to [maxLen] characters first.
     */
    private fun lengthString(text: String?, maxLen: Int): ByteArray {
        if (text == null) return byteArrayOf(0xFF.toByte())
        val bytes = elipse(text, maxLen).toByteArray(Charsets.UTF_8)
        return byteArrayOf((bytes.size and 0xFF).toByte()) + bytes
    }

    /** `UInt16.serialize()` - big-endian, high byte first. */
    private fun uint16(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    /**
     * One entry of a dash list, as `FavoriteResponse` -> `WaypointItem` ->
     * `ListItem` -> `ResponseBase` serialise it, in that order:
     *
     *     UInt16       responseCode     100 trying / 200 ok / 4xx / 5xx
     *     LengthString responsePhrase   max 32
     *     UInt8        nrListItems      total entries in this list
     *     UInt8        itemId           this entry's index
     *     LengthString title            max 32  ("Home")
     *     LengthString description      max 32  (the address)
     *
     * [itemId] is what comes back in a START_NAVIGATION request, so it is the
     * handle the dash uses to name a destination - keep it stable per entry.
     */
    fun buildWaypointItemPayload(
        responseCode: Int,
        responsePhrase: String?,
        nrListItems: Int,
        itemId: Int,
        title: String?,
        description: String?,
    ): ByteArray =
        uint16(responseCode) +
            lengthString(responsePhrase, WAYPOINT_PHRASE_MAX) +
            byteArrayOf((nrListItems and 0xFF).toByte(), (itemId and 0xFF).toByte()) +
            lengthString(title, WAYPOINT_TITLE_MAX) +
            lengthString(description, WAYPOINT_TITLE_MAX)

    /**
     * An empty list: the official app still answers, with a success code and a
     * zero count, so the dash shows "no entries" instead of hanging on the
     * request. Title/description are null (the 0xFF form).
     */
    fun buildEmptyListPayload(responseCode: Int = NavResponseCode.OK, phrase: String? = "OK"): ByteArray =
        buildWaypointItemPayload(responseCode, phrase, 0, 0, null, null)

    /** `ResponseBase.MAX_PHRASE_LENGTH` / `WaypointItem.MAX_TITLE_LENGTH`. */
    const val WAYPOINT_PHRASE_MAX = 32
    const val WAYPOINT_TITLE_MAX = 32
}
