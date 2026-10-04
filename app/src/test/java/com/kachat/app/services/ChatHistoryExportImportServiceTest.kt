package com.kachat.app.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.models.ChatHistoryArchiveMessage
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

class ChatHistoryExportImportServiceTest {

    private val myAddress = "kaspa:me"
    private val contactAddress = "kaspa:them"

    private fun message(
        id: String = "txid1",
        type: String = MessageProtocol.TYPE_COMM,
        direction: String = "sent",
        deliveryStatus: String = "sent"
    ) = MessageEntity(
        id = id,
        contactId = contactAddress,
        walletAddress = myAddress,
        type = type,
        direction = direction,
        plaintextBody = "hello",
        encryptedPayload = "deadbeef",
        amountSompi = null,
        blockTimestamp = 1_700_000_000_000L,
        isRead = false,
        deliveryStatus = deliveryStatus
    )

    // --- type mapping ---------------------------------------------------------------

    @Test
    fun `entity type maps to the matching archive type`() {
        assertEquals("handshake", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_HANDSHAKE))
        assertEquals("contextual", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_COMM))
        assertEquals("payment", ChatHistoryExportImportService.archiveMessageType(MessageProtocol.TYPE_PAY))
    }

    @Test
    fun `archive type maps back to the matching entity type`() {
        assertEquals(MessageProtocol.TYPE_HANDSHAKE, ChatHistoryExportImportService.entityMessageType("handshake"))
        assertEquals(MessageProtocol.TYPE_COMM, ChatHistoryExportImportService.entityMessageType("contextual"))
        assertEquals(MessageProtocol.TYPE_PAY, ChatHistoryExportImportService.entityMessageType("payment"))
    }

    @Test
    fun `an iOS-only audio type imports as a regular contextual message`() {
        assertEquals(MessageProtocol.TYPE_COMM, ChatHistoryExportImportService.entityMessageType("audio"))
    }

    // --- toArchiveMessage -------------------------------------------------------------

    @Test
    fun `an outgoing message maps sender to me and receiver to the contact`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(direction = "sent"), myAddress)
        assertEquals(myAddress, archived.senderAddress)
        assertEquals(contactAddress, archived.receiverAddress)
        assertTrue(archived.isOutgoing)
    }

    @Test
    fun `an incoming message maps sender to the contact and receiver to me`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(direction = "received"), myAddress)
        assertEquals(contactAddress, archived.senderAddress)
        assertEquals(myAddress, archived.receiverAddress)
        assertTrue(!archived.isOutgoing)
    }

    @Test
    fun `txId is written verbatim and id is a real UUID, since iOS decodes id as UUID`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(id = "abc123"), myAddress)
        assertEquals("abc123", archived.txId)
        assertTrue("id must be an RFC-4122 UUID, was ${archived.id}", archived.id.matches(UUID_REGEX))
        // Deterministic: a second export of the same message must publish the same id.
        assertEquals(archived.id, ChatHistoryExportImportService.toArchiveMessage(message(id = "abc123"), myAddress).id)
    }

    /**
     * The derived-id hash is a port of desktop's `derivedArchiveUuid` (ui/app.js). These expected
     * values were produced by running the JS original — if the two ever drift, the same message
     * would get two different ids depending on which device wrote the shared file last.
     */
    @Test
    fun `derived UUIDs match desktop's implementation exactly`() {
        assertEquals("c578303f-bb88-4b3d-8fbc-287ef1e92f90", ChatHistoryExportImportService.derivedArchiveUuid("abc123"))
        assertEquals("81106ed0-52ea-481b-91bd-4eb44339dc86", ChatHistoryExportImportService.derivedArchiveUuid("conversation:kaspa:them"))
        assertEquals("73ce14cf-68c2-4703-b0a4-853484e0734e", ChatHistoryExportImportService.derivedArchiveUuid("0".repeat(64)))
        assertEquals("09f45f69-9b9a-4489-9921-c94c508f729e", ChatHistoryExportImportService.derivedArchiveUuid(""))
        assertEquals("5ce33da7-53d4-492a-b5f4-4ca4c305477d", ChatHistoryExportImportService.derivedArchiveUuid("üñí✓"))
    }

    @Test
    fun `an id that is already a UUID is passed through untouched`() {
        val existing = "81106ed0-52ea-481b-91bd-4eb44339dc86"
        assertEquals(existing, ChatHistoryExportImportService.archiveUuid(existing, "ignored"))
    }

    @Test
    fun `timestamps are whole-second ISO8601, since iOS rejects fractional seconds`() {
        // 1_700_000_000_123 has non-zero millis — ISO_INSTANT would emit ".123" and iOS would throw.
        val archived = ChatHistoryExportImportService.toArchiveMessage(
            message().copy(blockTimestamp = 1_700_000_000_123L),
            myAddress
        )
        assertEquals("2023-11-14T22:13:20Z", archived.timestamp)
    }

    @Test
    fun `an unknown internal type never leaks into the file as-is`() {
        assertEquals("contextual", ChatHistoryExportImportService.archiveMessageType("something_new"))
    }

    @Test
    fun `an unknown local delivery status is written as sent`() {
        val archived = ChatHistoryExportImportService.toArchiveMessage(message(deliveryStatus = "queued"), myAddress)
        assertEquals("sent", archived.deliveryStatus)
    }

    // --- shared-file merge --------------------------------------------------------------

    private fun archiveJson(
        exportedAt: String,
        wallet: String = myAddress,
        messages: String,
        extraTopLevel: String = ""
    ) = """
        {"schemaVersion":1,"exportedAt":"$exportedAt","walletAddress":"$wallet",
         "conversations":[{"contactAddress":"$contactAddress","contactAlias":"Them","unreadCount":0,
         "messages":[$messages]}]$extraTopLevel}
    """.trimIndent()

    private fun messageJson(txId: String, content: String = "hello", status: String = "sent", blockTime: Long = 1_700_000_000_000L) =
        """{"id":"$txId","txId":"$txId","senderAddress":"$myAddress","receiverAddress":"$contactAddress",
            "content":"$content","timestamp":"2023-11-14T22:13:20.123Z","blockTime":$blockTime,
            "isOutgoing":true,"messageType":"contextual","deliveryStatus":"$status"}"""

    private fun merge(remote: String, local: String): JsonObject =
        ChatHistoryExportImportService.mergeArchives(
            ChatHistoryExportImportService.parseRemoteArchive(remote, myAddress),
            JsonParser.parseString(local).asJsonObject
        )

    private fun mergedMessages(merged: JsonObject): List<JsonObject> =
        merged.getAsJsonArray("conversations").first().asJsonObject
            .getAsJsonArray("messages").map { it.asJsonObject }

    @Test
    fun `merge is a union, so neither side's messages are lost`() {
        val merged = merge(
            archiveJson("2026-08-01T00:00:00Z", messages = messageJson("remote_only")),
            archiveJson("2026-08-02T00:00:00Z", messages = messageJson("local_only"))
        )
        assertEquals(setOf("remote_only", "local_only"), mergedMessages(merged).map { it.get("txId").asString }.toSet())
    }

    @Test
    fun `a remote pending message survives the merge, even though this device never exports pending`() {
        val merged = merge(
            archiveJson("2026-08-01T00:00:00Z", messages = messageJson("tx_pending", status = "pending")),
            archiveJson("2026-08-02T00:00:00Z", messages = messageJson("tx_other"))
        )
        val pending = mergedMessages(merged).single { it.get("txId").asString == "tx_pending" }
        assertEquals("pending", pending.get("deliveryStatus").asString)
    }

    @Test
    fun `the same txId dedupes to the better copy - a real body beats a placeholder`() {
        val merged = merge(
            archiveJson("2026-08-01T00:00:00Z", messages = messageJson("tx1", content = "[Encrypted message]")),
            archiveJson("2026-08-02T00:00:00Z", messages = messageJson("tx1", content = "the real text"))
        )
        val messages = mergedMessages(merged)
        assertEquals(1, messages.size)
        assertEquals("the real text", messages.single().get("content").asString)
    }

    @Test
    fun `the same txId dedupes to the further-along delivery status`() {
        val merged = merge(
            archiveJson("2026-08-01T00:00:00Z", messages = messageJson("tx1", status = "sent")),
            archiveJson("2026-08-02T00:00:00Z", messages = messageJson("tx1", status = "pending"))
        )
        assertEquals("sent", mergedMessages(merged).single().get("deliveryStatus").asString)
    }

    @Test
    fun `unknown top-level keys survive the merge - dropping desktopState would wipe desktop's state`() {
        val merged = merge(
            archiveJson("2026-08-01T00:00:00Z", messages = messageJson("tx1"), extraTopLevel = ""","desktopState":{"kind":"kachat-desktop-backup","state":{"a":1}}"""),
            archiveJson("2026-08-02T00:00:00Z", messages = messageJson("tx2"))
        )
        assertEquals(
            "kachat-desktop-backup",
            merged.getAsJsonObject("desktopState").get("kind").asString
        )
    }

    @Test
    fun `merging heals a legacy Android row into something iOS can decode`() {
        val merged = merge(
            archiveJson("2026-08-01T00:00:00Z", messages = messageJson("64hex_not_a_uuid")),
            archiveJson("2026-08-02T00:00:00Z", messages = messageJson("tx2"))
        )
        val healed = mergedMessages(merged).single { it.get("txId").asString == "64hex_not_a_uuid" }
        assertTrue(healed.get("id").asString.matches(UUID_REGEX))
        assertEquals("2023-11-14T22:13:20Z", healed.get("timestamp").asString)   // fractional seconds stripped
        val conversationId = merged.getAsJsonArray("conversations").first().asJsonObject.get("conversationId").asString
        assertTrue(conversationId.matches(UUID_REGEX))
    }

    @Test
    fun `a remote backup for a different wallet aborts instead of being overwritten`() {
        val error = runCatching {
            ChatHistoryExportImportService.parseRemoteArchive(
                archiveJson("2026-08-01T00:00:00Z", wallet = "kaspa:someone_else", messages = messageJson("tx1")),
                myAddress
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `content that isn't an archive is reported as unreadable, a newer schema as incompatible`() {
        for (body in listOf("not json at all", "[1,2,3]", """{"hello":"world"}""")) {
            val error = runCatching { ChatHistoryExportImportService.parseRemoteArchive(body, myAddress) }.exceptionOrNull()
            assertTrue("expected $body to be unreadable", error is RemoteBackupUnreadableException)
        }
        for (body in listOf("""{"schemaVersion":7,"conversations":[]}""", """{"schemaVersion":7}""")) {
            val error = runCatching { ChatHistoryExportImportService.parseRemoteArchive(body, myAddress) }.exceptionOrNull()
            assertTrue("expected $body to be refused as a newer schema", error is IllegalStateException)
            assertFalse("a newer schema is never taken for damage: $body", error is RemoteBackupUnreadableException)
        }
    }

    // --- what a sync does with the server copy (NEXTCLOUD_SYNC.md §7, iOS d57019a) ----------

    private val ownHint = BackupCrypto.walletHint(myAddress)

    private fun envelope(hint: String? = ownHint, version: Int = 1) =
        """{"kachatEncryptedBackup":$version,"cipher":"aes-256-gcm","nonce":"AAAAAAAAAAAAAAAA","ciphertext":"BBBB"""" +
            (if (hint != null) ""","walletHint":"$hint"}""" else "}")

    private val validArchive get() = archiveJson("2026-08-01T00:00:00Z", messages = messageJson("tx1"))

    private fun plan(remote: String?, decrypt: (String) -> String = { throw IllegalStateException(BackupCrypto.DECRYPT_FAILED_MESSAGE) }) =
        ChatHistoryExportImportService.planRemoteBackup(remote, myAddress, decrypt)

    private fun assertAborts(remote: String?, decrypt: (String) -> String = { throw IllegalStateException(BackupCrypto.DECRYPT_FAILED_MESSAGE) }) {
        val error = runCatching { plan(remote, decrypt) }.exceptionOrNull()
        assertTrue("expected the sync to abort before any PUT, got ${error ?: "a plan"}", error is IllegalStateException)
    }

    private fun assertReplacedInPlace(remote: String?, decrypt: (String) -> String = { throw IllegalStateException(BackupCrypto.DECRYPT_FAILED_MESSAGE) }) {
        val decision = plan(remote, decrypt)
        assertTrue("expected a write without merge", decision is RemoteBackupPlan.WriteLocal)
        assertNotNull("expected it logged as a replacement", (decision as RemoteBackupPlan.WriteLocal).replacedBecause)
    }

    @Test
    fun `no backup yet writes this device's history without merging`() {
        val decision = plan(null)
        assertTrue(decision is RemoteBackupPlan.WriteLocal)
        assertNull((decision as RemoteBackupPlan.WriteLocal).replacedBecause)
    }

    @Test
    fun `a readable archive of this wallet is merged`() {
        assertTrue(plan(validArchive) is RemoteBackupPlan.Merge)
        assertTrue(plan(envelope(), decrypt = { validArchive }) is RemoteBackupPlan.Merge)
    }

    @Test
    fun `this wallet's envelope that won't decrypt is replaced in place`() {
        assertReplacedInPlace(envelope(hint = ownHint))
    }

    @Test
    fun `this wallet's envelope that decrypts to something that isn't an archive is replaced in place`() {
        assertReplacedInPlace(envelope(hint = ownHint), decrypt = { """{"conversations":""" })
    }

    @Test
    fun `a file cut off at rest is replaced in place`() {
        assertReplacedInPlace(validArchive.take(validArchive.length / 2))   // plaintext
        val sealed = envelope()
        assertReplacedInPlace(sealed.take(sealed.length - 30))              // envelope, hint lost with the tail
        for (body in listOf("not json at all", "[1,2,3]", """{"hello":"world"}""", "   ")) assertReplacedInPlace(body)
    }

    @Test
    fun `another wallet's envelope is never touched, and is not even decrypted`() {
        var decryptCalled = false
        assertAborts(envelope(hint = BackupCrypto.walletHint("kaspa:someone_else")), decrypt = { decryptCalled = true; validArchive })
        assertFalse(decryptCalled)
    }

    @Test
    fun `another wallet's plaintext archive is never touched`() {
        assertAborts(archiveJson("2026-08-01T00:00:00Z", wallet = "kaspa:someone_else", messages = messageJson("tx1")))
    }

    @Test
    fun `an envelope without a walletHint that won't decrypt can't be attributed, so it is never touched`() {
        assertAborts(envelope(hint = null))
    }

    @Test
    fun `a newer schema is never touched`() {
        assertAborts("""{"schemaVersion":2,"walletAddress":"$myAddress","conversations":[]}""")
        assertAborts("""{"schemaVersion":2,"walletAddress":"$myAddress","history":{}}""")
        assertAborts(envelope(), decrypt = { """{"schemaVersion":2,"conversations":[]}""" })
        assertAborts(envelope(version = 2))
    }

    @Test
    fun `envelope detection reads the marker version and walletHint`() {
        assertTrue(BackupCrypto.isEnvelope(envelope()))
        assertFalse(BackupCrypto.isEnvelope(envelope(version = 2)))
        assertEquals(2, BackupCrypto.envelopeVersion(envelope(version = 2)))
        assertNull(BackupCrypto.envelopeVersion(validArchive))
        assertEquals(ownHint, BackupCrypto.envelopeWalletHint(envelope()))
        assertNull(BackupCrypto.envelopeWalletHint(envelope(hint = null)))
    }

    // --- per-conversation photo (NEXTCLOUD_SYNC.md §5) -------------------------------------

    private fun archiveWithPhoto(exportedAt: String, photo: String?) = """
        {"schemaVersion":1,"exportedAt":"$exportedAt","walletAddress":"$myAddress",
         "conversations":[{"contactAddress":"$contactAddress","unreadCount":0,
         ${if (photo != null) "\"contactPhoto\":\"$photo\"," else ""}"messages":[${messageJson("tx1")}]}]}
    """.trimIndent()

    private fun mergedPhoto(merged: JsonObject): String? =
        merged.getAsJsonArray("conversations").first().asJsonObject.get("contactPhoto")?.asString

    @Test
    fun `a photo only this device has survives the merge`() {
        val merged = merge(archiveWithPhoto("2026-08-01T00:00:00Z", null), archiveWithPhoto("2026-08-02T00:00:00Z", "LOCAL"))
        assertEquals("LOCAL", mergedPhoto(merged))
    }

    @Test
    fun `the newer archive's photo wins, and an empty one never overwrites a real one`() {
        assertEquals("LOCAL", mergedPhoto(merge(archiveWithPhoto("2026-08-01T00:00:00Z", "REMOTE"), archiveWithPhoto("2026-08-02T00:00:00Z", "LOCAL"))))
        assertEquals("REMOTE", mergedPhoto(merge(archiveWithPhoto("2026-08-03T00:00:00Z", "REMOTE"), archiveWithPhoto("2026-08-02T00:00:00Z", "LOCAL"))))
        assertEquals("REMOTE", mergedPhoto(merge(archiveWithPhoto("2026-08-01T00:00:00Z", "REMOTE"), archiveWithPhoto("2026-08-02T00:00:00Z", null))))
    }

    // --- toMessageEntity ---------------------------------------------------------------

    private fun archiveMessage(
        isOutgoing: Boolean = true,
        deliveryStatus: String = "sent",
        messageType: String = "contextual"
    ) = ChatHistoryArchiveMessage(
        id = "txid1",
        txId = "txid1",
        senderAddress = if (isOutgoing) myAddress else contactAddress,
        receiverAddress = if (isOutgoing) contactAddress else myAddress,
        content = "hello",
        timestamp = "2023-11-14T22:13:20Z",
        blockTime = 1_700_000_000_000L,
        isOutgoing = isOutgoing,
        messageType = messageType,
        deliveryStatus = deliveryStatus
    )

    @Test
    fun `imported messages are always marked read, since the archive has no per-message read state`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = false), contactAddress, myAddress)
        assertTrue(entity.isRead)
    }

    @Test
    fun `outgoing archive message becomes a sent-direction entity`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = true), contactAddress, myAddress)
        assertEquals("sent", entity.direction)
    }

    @Test
    fun `incoming archive message becomes a received-direction entity`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(isOutgoing = false), contactAddress, myAddress)
        assertEquals("received", entity.direction)
    }

    @Test
    fun `an iOS warning delivery status is preserved as-is`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(deliveryStatus = "warning"), contactAddress, myAddress)
        assertEquals("warning", entity.deliveryStatus)
    }

    @Test
    fun `an unrecognized delivery status falls back to sent rather than crashing`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(deliveryStatus = "bogus"), contactAddress, myAddress)
        assertEquals("sent", entity.deliveryStatus)
    }

    @Test
    fun `entity always attaches to the currently active wallet address, not any address in the archive`() {
        val entity = ChatHistoryExportImportService.toMessageEntity(archiveMessage(), contactAddress, myAddress)
        assertEquals(myAddress, entity.walletAddress)
    }
}
