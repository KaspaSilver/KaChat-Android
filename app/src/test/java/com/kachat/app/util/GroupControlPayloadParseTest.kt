package com.kachat.app.util

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

/**
 * Control payloads that leave out, or null, a field they can't do without parse to null, as iOS's
 * Codable refuses them. Gson alone filled those with null under non-null Kotlin types: a
 * stranger's self-signed root with `"members":[null]` passed the signature and sender checks and
 * then threw in the invite code, which stopped group catch-up for good.
 */
class GroupControlPayloadParseTest {
    private val privateKey = run {
        var key: ByteArray
        do { key = ByteArray(32).also { SecureRandom().nextBytes(it) } } while (runCatching { Schnorr.publicKeyXOnly(key) }.isFailure)
        key
    }
    private val pub = Schnorr.publicKeyXOnly(privateKey)
    private val groupId = ByteArray(32) { 7 }

    private fun rootJson(): JsonObject {
        val root = GroupCipher.buildSignedRootPayload(
            groupId = groupId, epoch = 3, groupRootEpoch = ByteArray(32) { 1 }, blindingKey = ByteArray(32) { 2 },
            adminSigningPub = pub, members = listOf("kaspa:qqa", "kaspa:qqb"), name = "Group",
            rosterVersion = 1_793_900_000_000L, adminPrivateKey = privateKey,
        )
        return JsonParser.parseString(GroupCipher.rootPayloadToJson(root)).asJsonObject
    }

    private fun JsonObject.edited(change: JsonObject.() -> Unit): String = deepCopy().apply(change).toString()

    @Test
    fun `a well-formed root parses`() {
        val parsed = GroupCipher.rootPayloadFromJson(rootJson().toString())
        assertNotNull(parsed)
        assertEquals(listOf("kaspa:qqa", "kaspa:qqb"), parsed!!.members)
    }

    @Test
    fun `a self-signed root with a null member parses to null`() {
        val json = rootJson().edited { add("members", JsonArray().apply { add(JsonNull.INSTANCE) }) }
        assertNull(GroupCipher.rootPayloadFromJson(json))
    }

    @Test
    fun `a root with a null or blank member among real ones parses to null`() {
        val withNull = rootJson().edited { getAsJsonArray("members").add(JsonNull.INSTANCE) }
        val withBlank = rootJson().edited { getAsJsonArray("members").add(" ") }
        assertNull(GroupCipher.rootPayloadFromJson(withNull))
        assertNull(GroupCipher.rootPayloadFromJson(withBlank))
    }

    @Test
    fun `a root missing or nulling a field it needs parses to null`() {
        val required = listOf("members", "name", "group_id", "admin_signing_pub", "group_root_epoch", "blinding_key", "sig", "epoch")
        for (field in required) {
            assertNull("missing $field", GroupCipher.rootPayloadFromJson(rootJson().edited { remove(field) }))
            assertNull("null $field", GroupCipher.rootPayloadFromJson(rootJson().edited { add(field, JsonNull.INSTANCE) }))
        }
    }

    @Test
    fun `a root whose members isn't a list of strings parses to null`() {
        assertNull(GroupCipher.rootPayloadFromJson(rootJson().edited { addProperty("members", "kaspa:qqa") }))
        assertNull(GroupCipher.rootPayloadFromJson(rootJson().edited { add("members", JsonArray().apply { add(5) }) }))
    }

    @Test
    fun `a root that isn't JSON or isn't an object parses to null`() {
        assertNull(GroupCipher.rootPayloadFromJson("not json"))
        assertNull(GroupCipher.rootPayloadFromJson("[]"))
    }

    @Test
    fun `the other controls refuse a missing field and keep an optional one optional`() {
        val tomb = JsonParser.parseString(
            GroupCipher.tombstonePayloadToJson(GroupCipher.buildSignedTombstonePayload(groupId, null, pub, privateKey))
        ).asJsonObject
        assertNotNull(GroupCipher.tombstonePayloadFromJson(tomb.toString()))
        assertNull(GroupCipher.tombstonePayloadFromJson(tomb.edited { remove("group_id") }))

        val accept = JsonParser.parseString(
            GroupCipher.inviteAcceptPayloadToJson(GroupCipher.buildSignedInviteAcceptPayload(groupId, pub, pub, privateKey))
        ).asJsonObject
        assertNotNull(GroupCipher.inviteAcceptPayloadFromJson(accept.toString()))
        assertNotNull(GroupCipher.inviteAcceptPayloadFromJson(accept.edited { remove("admin_signing_pub") }))
        assertNull(GroupCipher.inviteAcceptPayloadFromJson(accept.edited { remove("signing_pub") }))

        val photo = JsonParser.parseString(
            GroupCipher.photoPayloadToJson(GroupCipher.buildSignedPhotoPayload(groupId, "", pub, privateKey))
        ).asJsonObject
        assertNotNull(GroupCipher.photoPayloadFromJson(photo.toString()))
        assertNull(GroupCipher.photoPayloadFromJson(photo.edited { remove("group_id") }))
        assertNull(GroupCipher.photoPayloadFromJson(photo.edited { add("photo", JsonNull.INSTANCE) }))

        val epoch = JsonParser.parseString(
            GroupCipher.epochPayloadToJson(GroupCipher.buildSignedEpochPayload(groupId, 4, "add", privateKey))
        ).asJsonObject
        assertNotNull(GroupCipher.epochPayloadFromJson(epoch.toString()))
        assertNull(GroupCipher.epochPayloadFromJson(epoch.edited { addProperty("reason", "takeover") }))
        assertNull(GroupCipher.epochPayloadFromJson(epoch.edited { remove("sig") }))
    }
}
