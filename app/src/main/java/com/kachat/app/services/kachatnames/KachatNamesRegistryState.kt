package com.kachat.app.services.kachatnames

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.Failure
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.services.kachatnames.KachatNames.unhex
import com.kachat.app.services.kachatnames.KachatNames.unhex32
import java.text.BreakIterator

// The `.kachat` registry as data: what a name, gap or offer looks like to the screens, the status
// rule, the label rule, the address profile record, and the registry walker's state with its
// transition decoder - a port of the kachat-domains CLI's `registry.rs` (`Registry::apply`,
// KACHAT_NAMES_INDEXER.md B3), through iOS KaChat/Services/KachatNames/KachatNamesRegistryState.swift
// (KaChat 27edcd5). Pure Kotlin + Gson: no network, no keys, no Android, so it is tested on the JVM
// (`KachatNamesRegistryTest`, the port of iOS scripts/test_kachat_names_registry.swift).
//
// Swift nests these in `extension KachatNames`; Kotlin cannot add nested types to the object from
// another file, so they are top-level in this package like the phase-1 types (`Plan`, `Builder`...),
// and Swift's static functions are extension functions on the object (`KachatNames.label(...)`).
// Amounts stay `Long` (sompi), as in the core.

// Status (KACHAT_NAMES_INDEXER.md B5)

enum class Status(val raw: String) {
    /** `now < expiresAt`: resolves, everything works. */
    ACTIVE("active"),
    /** `expiresAt <= now < expiresAt + grace`: no longer resolves; only the owner sees it, as
     *  "renew to keep it". Nobody can take it. */
    GRACE("grace"),
    /** `now >= expiresAt + grace`, still unspent: anyone may reclaim it. */
    LAPSED("lapsed");

    val resolves: Boolean get() = this == ACTIVE

    companion object {
        fun of(expiresAt: Long, graceMs: Long, nowMs: Long): Status = when {
            nowMs < expiresAt -> ACTIVE
            nowMs < expiresAt + graceMs -> GRACE
            else -> LAPSED
        }
    }
}

fun KachatNames.nowMs(): Long = System.currentTimeMillis()

// What the screens read

/** A registered name, from either source (indexer or chain walker). */
class NameInfo(
    val name: String,
    val key: ByteArray,
    /** x-only owner key */
    val owner: ByteArray,
    /** sompi; 0 = not listed */
    val price: Long,
    val expiresAt: Long,
    val outpoint: Outpoint,
    /** unix ms of the registration, when known */
    val registeredAt: Long? = null,
    val registeredTxId: String? = null,
    val updatedAt: Long? = null
) {
    val id: String get() = name
    val display: String get() = "$name.kachat"
    val isListed: Boolean get() = price > 0

    val fields: NameFields get() = NameFields(key, Codec.padded(name), owner, price, expiresAt)

    fun status(graceMs: Long, nowMs: Long = KachatNames.nowMs()): Status = Status.of(expiresAt, graceMs, nowMs)

    override fun equals(other: Any?): Boolean =
        other is NameInfo && name == other.name && key.contentEquals(other.key) && owner.contentEquals(other.owner) &&
            price == other.price && expiresAt == other.expiresAt && outpoint == other.outpoint &&
            registeredAt == other.registeredAt && registeredTxId == other.registeredTxId && updatedAt == other.updatedAt

    override fun hashCode(): Int = listOf(name, key.contentHashCode(), owner.contentHashCode(), price, expiresAt, outpoint).hashCode()

    override fun toString(): String = "NameInfo($display, owner=${hex(owner)}, price=$price, expiresAt=$expiresAt, at $outpoint)"
}

/** An unregistered interval `(lo, hi)` of the key space. */
class GapInfo(val lo: ByteArray, val hi: ByteArray, val outpoint: Outpoint) {
    fun contains(key: ByteArray): Boolean = KachatNames.precedes(lo, key) && KachatNames.precedes(key, hi)

    override fun equals(other: Any?): Boolean =
        other is GapInfo && lo.contentEquals(other.lo) && hi.contentEquals(other.hi) && outpoint == other.outpoint

    override fun hashCode(): Int = listOf(lo.contentHashCode(), hi.contentHashCode(), outpoint).hashCode()

    override fun toString(): String = "GapInfo(${hex(lo).take(8)}..-${hex(hi).take(8)}.. at $outpoint)"
}

/** The answer for a typed name. */
sealed class Lookup {
    data class Registered(val info: NameInfo) : Lookup()

    /** Free to claim, inside [gap] (null when the source knows it is free but not where). */
    data class Free(val name: String, val gap: GapInfo?) : Lookup()
}

class OfferInfo(
    val outpoint: Outpoint,
    val key: ByteArray,
    val name: String?,
    val buyer: ByteArray,
    val amount: Long,
    /** DAA score from which anyone may refund it */
    val refundAfter: Long,
    val createdAt: Long? = null
) {
    val id: String get() = "${hex(outpoint.txid)}:${outpoint.index}"
    val fields: OfferFields get() = OfferFields(key, buyer, refundAfter)

    fun refundable(atDaa: Long): Boolean = atDaa > maxOf(refundAfter, 0L)

    override fun equals(other: Any?): Boolean =
        other is OfferInfo && outpoint == other.outpoint && key.contentEquals(other.key) && name == other.name &&
            buyer.contentEquals(other.buyer) && amount == other.amount && refundAfter == other.refundAfter &&
            createdAt == other.createdAt

    override fun hashCode(): Int = listOf(outpoint, key.contentHashCode(), buyer.contentHashCode(), amount, refundAfter).hashCode()

    override fun toString(): String = "OfferInfo($id, name=$name, buyer=${hex(buyer)}, amount=$amount, refundAfter=$refundAfter)"
}

/**
 * One registry event (history, activity). Parties are x-only keys or addresses depending on the
 * source; the screens show them through a party label.
 */
data class Event(
    val txId: String,
    /** register, transfer, list, delist, sale, renew, release, reclaim, offer_accepted, offer */
    val op: String,
    val name: String? = null,
    val at: Long? = null,
    /** previous owner: an address (indexer) or x-only key hex (walker) */
    val from: String? = null,
    /** new owner / buyer */
    val to: String? = null,
    /** sompi: listing price, sale price, offer amount */
    val price: Long? = null,
    val years: Long? = null
) {
    val id: String get() = "$txId:$op:${name ?: ""}"
}

// Label rule (KACHAT_NAMES.md section 7)

/**
 * The label an address is shown with: its `primaryName` if it owns that name and it is active;
 * otherwise its oldest active name; otherwise null (the caller shows the address).
 */
fun KachatNames.label(owned: List<NameInfo>, primaryName: String?, graceMs: Long, nowMs: Long = nowMs()): String? {
    val active = owned.filter { it.status(graceMs, nowMs) == Status.ACTIVE }
    val p = primaryName?.let { Codec.normalize(it) }
    if (p != null && active.any { it.name == p }) return p
    val oldest = active.sortedWith(compareBy<NameInfo>({ it.registeredAt ?: Long.MAX_VALUE }, { it.name }))
    return oldest.firstOrNull()?.name
}

/**
 * Where a typed `.kachat` name points: its owner when it is registered and ACTIVE - a name in
 * grace or lapsed does not resolve (KACHAT_NAMES.md section 4; iOS NameServices.resolveKachat,
 * KaChat 25cc2c9). Null otherwise.
 */
fun KachatNames.resolvedOwner(lookup: Lookup, graceMs: Long, nowMs: Long = nowMs()): ByteArray? =
    (lookup as? Lookup.Registered)?.info?.takeIf { it.status(graceMs, nowMs).resolves }?.owner

// Profile record (KACHAT_NAMES.md section 7, KACHAT_NAMES_INDEXER.md Part C)

/**
 * The address profile as written on chain: `{social, linktree, primaryName, v}` (iOS 1322216). No
 * picture and no free text is ever written: the avatar, banner and bio all come from [social],
 * looked up on each device ([KachatSocialImageResolver]), so the platform's moderation applies to
 * all three. iOS and Android read each other's records, so the shape, the normalization and the
 * JSON must stay exactly iOS's.
 */
data class Profile(
    val v: Int = 1,
    /** Your profile on a social platform ([SocialSource]): KaChat shows its avatar, banner and
     *  bio. Stored normalized (`https://x.com/name`). */
    val social: String? = null,
    /** A Linktree page (`https://linktr.ee/<name>`): the one way to link anything else. */
    val linktree: String? = null,
    val primaryName: String? = null
) {
    /**
     * The record as the indexer accepts it: a supported social link and a Linktree link,
     * normalized, anything else dropped; the primary name normalized.
     */
    fun sanitized(): Profile = Profile(
        v = 1,
        social = clean(social)?.let { SocialSource.from(it, SocialSource.Kind.AVATAR)?.link },
        linktree = linktreeLink(linktree),
        primaryName = clean(primaryName)?.let { Codec.normalize(it) }?.takeIf { Codec.isValid(it) }
    )

    /**
     * The JSON of the record: compact, keys sorted, null fields left out (Swift's JSONEncoder with
     * `.sortedKeys, .withoutEscapingSlashes`). Throws past 2 KB.
     */
    fun recordJSON(): ByteArray {
        val p = sanitized()
        val o = JsonObject()
        p.linktree?.let { o.addProperty("linktree", it) }
        p.primaryName?.let { o.addProperty("primaryName", it) }
        p.social?.let { o.addProperty("social", it) }
        o.addProperty("v", p.v)
        val data = JSON.toJson(o).toByteArray(Charsets.UTF_8)
        if (data.size > Codec.MAX_PROFILE_JSON_BYTES) throw Failure("the profile is over 2 KB")
        return data
    }

    companion object {
        /** A bio as shown is cut to this many characters (the platform's own text, see
         *  [SocialSource.trimmedBio]). */
        const val MAX_BIO = 280

        private val JSON = GsonBuilder().disableHtmlEscaping().create()

        internal fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

        /** A pasted Linktree link, normalized to `https://linktr.ee/<name>`; null for anything else. */
        fun linktreeLink(raw: String?): String? {
            var t = clean(raw) ?: return null
            if (!t.lowercase().startsWith("http://") && !t.lowercase().startsWith("https://")) t = "https://$t"
            val (rawHost, path) = SocialSource.hostAndPath(t) ?: return null
            val host = rawHost.removePrefix("www.")
            if (host != "linktr.ee") return null
            val parts = path.split('/').filter { it.isNotEmpty() }
            val handle = parts.singleOrNull() ?: return null
            if (characterCount(handle) > 60 || !handle.codePoints().allMatch { isLetterOrNumber(it) || (it < 0x80 && it.toChar() in "._-") }) return null
            return "https://linktr.ee/$handle"
        }

        /** A record's JSON as the indexer reads it (unknown fields dropped, then sanitized). */
        fun parse(data: ByteArray): Profile? {
            if (data.size > Codec.MAX_PROFILE_JSON_BYTES) return null
            val root = runCatching { JsonParser.parseString(String(data, Charsets.UTF_8)) }.getOrNull() ?: return null
            val p = decode(root) ?: return null
            return if (p.v == 1) p.sanitized() else null
        }

        /**
         * Strict like Swift's synthesized Decodable: `v` is required, every known field must have
         * its type (or be null/absent), unknown fields - the old record's avatar, banner, bio and
         * links among them - are ignored. Null for anything else.
         */
        fun decode(e: JsonElement?): Profile? {
            if (e == null || !e.isJsonObject) return null
            val o = e.asJsonObject
            val v = o.get("v")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive ?: return null
            val vd = v.asDouble
            if (vd != Math.floor(vd)) return null
            fun str(k: String): Result<String?> {
                val x = o.get(k)
                return when {
                    x == null || x.isJsonNull -> Result.success(null)
                    x.isJsonPrimitive && x.asJsonPrimitive.isString -> Result.success(x.asString)
                    else -> Result.failure(Failure(k))
                }
            }
            return try {
                Profile(
                    v = vd.toInt(),
                    social = str("social").getOrThrow(),
                    linktree = str("linktree").getOrThrow(),
                    primaryName = str("primaryName").getOrThrow()
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** The first [n] user-perceived characters (Swift `String.prefix` counts graphemes). */
internal fun prefixCharacters(s: String, n: Int): String {
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    var count = 0
    var end = 0
    while (count < n) {
        val next = it.next()
        if (next == BreakIterator.DONE) return s
        end = next
        count++
    }
    return s.substring(0, end)
}

/** Swift's `String.count`: user-perceived characters. */
internal fun characterCount(s: String): Int {
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    var count = 0
    while (it.next() != BreakIterator.DONE) count++
    return count
}

/** Swift's `Character.isLetter || Character.isNumber` for one code point. */
internal fun isLetterOrNumber(cp: Int): Boolean = Character.isLetter(cp) || Character.isAlphabetic(cp) ||
    when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

/**
 * Where a profile's avatar, banner and bio come from: a profile link on a platform that moderates
 * what it shows (X, YouTube, Facebook, ...). The record stores only the link; each device looks
 * the current avatar, banner and bio up and caches them ([KachatSocialImageResolver]), so anything
 * the platform takes down disappears here too. Nothing is ever uploaded. iOS
 * `KachatNames.SocialSource` (ad32798, 1322216, 169f6a0).
 */
data class SocialSource(
    val platform: Platform,
    /** The normalized profile link, e.g. `https://x.com/name`. */
    val link: String,
    /** The handle, channel path or invite code inside it. */
    val handle: String
) {
    enum class Kind { AVATAR, BANNER }

    enum class Platform(
        /** The platform's own name (not translated: a brand). */
        val displayName: String
    ) {
        X("X"), YOUTUBE("YouTube"), FACEBOOK("Facebook"), INSTAGRAM("Instagram"), TIKTOK("TikTok"),
        TWITCH("Twitch"), KICK("Kick"), GITHUB("GitHub"), TELEGRAM("Telegram"), LINKEDIN("LinkedIn"),
        DISCORD("Discord");

        /** Platforms whose banner can be read without signing in. */
        val hasBanner: Boolean get() = this == X || this == YOUTUBE || this == DISCORD

        /** Platforms whose preview carries the person's own bio (see [SocialSource.bio]). */
        val hasBio: Boolean get() = this in setOf(X, YOUTUBE, TELEGRAM, TWITCH, KICK, GITHUB, DISCORD)
    }

    companion object {
        /**
         * Accepts a pasted profile link (with or without `https://`, `www.`, `m.`, trailing slash
         * or query). Null for an unsupported site, a post rather than a profile, or a banner from a
         * platform that has none (Swift's failable `init?(link:for:)`).
         */
        fun from(raw: String, kind: Kind): SocialSource? {
            var t = raw.trim()
            if (t.isEmpty()) return null
            if (!t.lowercase().startsWith("http://") && !t.lowercase().startsWith("https://")) t = "https://$t"
            val (h, path) = hostAndPath(t) ?: return null
            var host = h
            for (prefix in listOf("www.", "m.", "mobile.")) if (host.startsWith(prefix)) host = host.removePrefix(prefix)
            val parts = path.split('/').filter { it.isNotEmpty() }
            fun ok(s: String): Boolean =
                s.isNotEmpty() && characterCount(s) <= 100 && s.codePoints().allMatch { isLetterOrNumber(it) || (it < 0x80 && it.toChar() in "._-@") }

            var platform: Platform? = null
            var handle = ""
            var link = ""
            when (host) {
                "x.com", "twitter.com" ->
                    if (parts.size == 1 && ok(parts[0]) && parts[0].lowercase() !in setOf("home", "explore", "search", "i", "settings")) {
                        platform = Platform.X; handle = parts[0]; link = "https://x.com/$handle"
                    }
                "youtube.com" ->
                    if (parts.isNotEmpty() && parts[0].startsWith("@") && ok(parts[0])) {
                        platform = Platform.YOUTUBE; handle = parts[0]; link = "https://www.youtube.com/$handle"
                    } else if (parts.size >= 2 && parts[0] in setOf("channel", "c", "user") && ok(parts[1])) {
                        platform = Platform.YOUTUBE; handle = "${parts[0]}/${parts[1]}"; link = "https://www.youtube.com/$handle"
                    }
                "facebook.com", "fb.com" ->
                    if (parts.size == 1 && ok(parts[0]) && parts[0].lowercase() !in setOf("profile.php", "groups", "watch", "events")) {
                        platform = Platform.FACEBOOK; handle = parts[0]; link = "https://www.facebook.com/$handle"
                    }
                "instagram.com" ->
                    if (parts.size == 1 && ok(parts[0]) && parts[0].lowercase() !in setOf("p", "reel", "reels", "explore", "stories")) {
                        platform = Platform.INSTAGRAM; handle = parts[0]; link = "https://www.instagram.com/$handle/"
                    }
                "tiktok.com" ->
                    if (parts.size == 1 && parts[0].startsWith("@") && ok(parts[0])) {
                        platform = Platform.TIKTOK; handle = parts[0]; link = "https://www.tiktok.com/$handle"
                    }
                "twitch.tv" ->
                    if (parts.size == 1 && ok(parts[0])) { platform = Platform.TWITCH; handle = parts[0]; link = "https://www.twitch.tv/$handle" }
                "kick.com" ->
                    if (parts.size == 1 && ok(parts[0])) { platform = Platform.KICK; handle = parts[0]; link = "https://kick.com/$handle" }
                "github.com" ->
                    if (parts.size == 1 && ok(parts[0])) { platform = Platform.GITHUB; handle = parts[0]; link = "https://github.com/$handle" }
                "t.me", "telegram.me" ->
                    if (parts.size == 1 && ok(parts[0]) && !parts[0].startsWith("+")) {
                        platform = Platform.TELEGRAM; handle = parts[0]; link = "https://t.me/$handle"
                    }
                "linkedin.com" ->
                    if (parts.size >= 2 && parts[0] in setOf("in", "company") && ok(parts[1])) {
                        platform = Platform.LINKEDIN; handle = "${parts[0]}/${parts[1]}"; link = "https://www.linkedin.com/$handle"
                    }
                "discord.gg" ->
                    if (parts.size == 1 && ok(parts[0])) { platform = Platform.DISCORD; handle = parts[0]; link = "https://discord.gg/$handle" }
                "discord.com", "discordapp.com" ->
                    if (parts.size == 2 && parts[0] == "invite" && ok(parts[1])) {
                        platform = Platform.DISCORD; handle = parts[1]; link = "https://discord.gg/$handle"
                    }
            }
            val p = platform ?: return null
            if (kind == Kind.BANNER && !p.hasBanner) return null
            return SocialSource(p, link, handle)
        }

        /**
         * The lowercased host and the decoded path of a URL - what Swift reads from
         * `URLComponents(string:)`'s `host` and `path`. Null when it doesn't parse or has no host.
         */
        internal fun hostAndPath(url: String): Pair<String, String>? {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            return host to (uri.path ?: "")
        }

        // Reading the profile out of what the platform serves (pure, tested on the JVM)

        private fun metaTag(html: String, key: String): String? =
            Regex("<meta[^>]+(?:property|name)=[\"']${Regex.escape(key)}[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value

        /** The `og:image` (or `twitter:image`) of an HTML page, entities decoded. */
        fun openGraphImage(html: String): String? {
            for (key in listOf("og:image", "og:image:secure_url", "twitter:image")) {
                val tag = metaTag(html, key) ?: continue
                val c = Regex("content=[\"']([^\"']+)[\"']").find(tag) ?: continue
                val value = decodeEntities(c.value.replace("content=", "").trim('"', '\''))
                if (value.lowercase().startsWith("https://")) return value
            }
            return null
        }

        /** The page's `og:description` (or `description`), entities decoded. */
        fun openGraphDescription(html: String): String? {
            for (key in listOf("og:description", "description", "twitter:description")) {
                val tag = metaTag(html, key) ?: continue
                val c = Regex("content=\"([^\"]*)\"").find(tag) ?: Regex("content='([^']*)'").find(tag) ?: continue
                val value = decodeEntities(c.groupValues[1]).trim()
                if (value.isNotEmpty()) return value
            }
            return null
        }

        /**
         * The bio a platform shows in its preview, where that text really is the person's own (X,
         * YouTube, Telegram, Kick, and Twitch without its boilerplate). Instagram, TikTok, Facebook
         * and LinkedIn only put follower counts or site text there: no bio from them. GitHub and
         * Discord come from their APIs instead.
         */
        fun bio(platform: Platform, openGraphDescription: String?): String? {
            val d = openGraphDescription
            if (d.isNullOrEmpty()) return null
            val text = when (platform) {
                Platform.X, Platform.YOUTUBE, Platform.TELEGRAM, Platform.KICK -> d
                // "<description> — Twitch streams live on Twitch! Check out their videos ..."
                Platform.TWITCH -> d.split(" — ").first()
                else -> return null
            }
            return trimmedBio(text)
        }

        fun trimmedBio(s: String?): String? {
            val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return prefixCharacters(t, Profile.MAX_BIO)
        }

        private fun jsonObject(json: String): JsonObject? =
            runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

        /** Swift's `dict[k] as? String`: a JSON string, else null. */
        private fun JsonObject.string(k: String): String? =
            get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        /** GitHub's public user API (`api.github.com/users/<name>`): avatar and bio. */
        fun githubProfile(json: String): Pair<String?, String?> {
            val root = jsonObject(json) ?: return null to null
            return root.string("avatar_url") to trimmedBio(root.string("bio"))
        }

        /** A Discord invite's server description. */
        fun discordDescription(inviteJson: String): String? {
            val guild = jsonObject(inviteJson)?.get("guild")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return trimmedBio(guild.string("description"))
        }

        /** Discord invite -> the server's icon or banner (`/api/v10/invites/{code}`). */
        fun discordImage(inviteJson: String, kind: Kind): String? {
            val guild = jsonObject(inviteJson)?.get("guild")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val id = guild.string("id") ?: return null
            return when (kind) {
                Kind.AVATAR -> guild.string("icon")?.takeIf { it.isNotEmpty() }?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=256" }
                Kind.BANNER -> guild.string("banner")?.takeIf { it.isNotEmpty() }?.let { "https://cdn.discordapp.com/banners/$id/$it.png?size=1024" }
            }
        }

        private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

        /** HTML entities as they appear in meta tags: named basics plus decimal and hex numbers. */
        fun decodeEntities(s: String): String {
            if (!s.contains("&")) return s
            var out = s
            for ((k, v) in listOf("&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")) {
                out = out.replace(k, v)
            }
            // From the start each time, as iOS does (a decoded "&" can start the next entity).
            while (true) {
                val m = NUMERIC_ENTITY.find(out) ?: break
                val body = m.groupValues[1]
                val n = if (body.startsWith("x")) body.drop(1).toLongOrNull(16) else body.toLongOrNull()
                // Swift: UInt32(...) then Unicode.Scalar(...) - nothing for overflow or a surrogate
                val scalar = n?.takeIf { it <= 0x10FFFF && it !in 0xD800L..0xDFFFL }?.toInt()
                out = out.replaceRange(m.range, scalar?.let { String(Character.toChars(it)) } ?: "")
            }
            // last, so "&amp;#39;" (double-encoded, as LinkedIn sends) decodes one level only
            return out.replace("&amp;", "&")
        }

        /** X's avatar from its page, upgraded from the 200px thumbnail to 400px. */
        fun xAvatar(openGraphImage: String): String = openGraphImage.replace("_200x200.", "_400x400.")

        /** X's banner: the page names it as `profile_banners/<user id>/<version>`. */
        fun xBanner(html: String): String? =
            Regex("profile_banners/[0-9]+/[0-9]+").find(html)?.let { "https://pbs.twimg.com/${it.value}/1500x500" }

        /** YouTube's channel banner from the page's embedded data, when the channel has one. */
        fun youtubeBanner(html: String): String? {
            // The object itself (the bare name also appears earlier, in a list of renderer types).
            val marker = "\"imageBannerViewModel\":{"
            val start = html.indexOf(marker)
            if (start < 0) return null
            val from = start + marker.length
            val window = html.substring(from, minOf(html.length, from + 4000))
            return Regex("https://yt3\\.googleusercontent\\.com/[^\"\\\\]+").find(window)?.value
        }
    }
}

/** What a social profile link shows right now: avatar, banner (X, YouTube, Discord) and bio. */
data class SocialProfile(
    val avatar: String? = null,
    val banner: String? = null,
    val bio: String? = null
) {
    val isEmpty: Boolean get() = avatar == null && banner == null && bio == null
}

data class Identity(
    val address: String,
    /** the bare name (no `.kachat`), null when the address has no active name */
    val label: String?,
    val names: List<String>,
    val profile: Profile?
)

// A transaction as the walker sees it

class ViewInput(val outpoint: Outpoint, val signatureScript: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ViewInput && outpoint == other.outpoint && signatureScript.contentEquals(other.signatureScript)

    override fun hashCode(): Int = 31 * outpoint.hashCode() + signatureScript.contentHashCode()
}

class TxView(
    val id: ByteArray,
    val inputs: List<ViewInput>,
    val outputs: List<TxOutput>,
    val payload: ByteArray,
    /** unix ms of the accepting block (or the block), when known */
    val at: Long?
) {
    val idHex: String get() = hex(id)

    fun copy(
        inputs: List<ViewInput> = this.inputs,
        outputs: List<TxOutput> = this.outputs,
        payload: ByteArray = this.payload,
        at: Long? = this.at
    ): TxView = TxView(id, inputs, outputs, payload, at)

    override fun equals(other: Any?): Boolean =
        other is TxView && id.contentEquals(other.id) && inputs == other.inputs && outputs == other.outputs &&
            payload.contentEquals(other.payload) && at == other.at

    override fun hashCode(): Int = id.contentHashCode()

    override fun toString(): String = "TxView($idHex)"

    companion object {
        private fun str(v: JsonElement?): String? =
            if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else null

        private fun num(v: JsonElement?): Long? {
            if (v == null || !v.isJsonPrimitive) return null
            val p = v.asJsonPrimitive
            return when {
                p.isNumber -> p.asLong
                p.isString -> p.asString.toLongOrNull()
                else -> null
            }
        }

        private fun objects(v: JsonElement?): List<JsonObject> =
            if (v != null && v.isJsonArray) v.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null } else emptyList()

        /**
         * A transaction from the Kaspa REST API (`GET /addresses/{a}/full-transactions` or
         * `GET /transactions/{id}`, kaspa-rest-server): version-1 outputs carry `covenant_id` and
         * `covenant_authorizing_input`. Returns null for one that is not accepted.
         */
        fun fromREST(j: JsonObject): TxView? {
            val accepted = j.get("is_accepted")
            if (accepted != null && accepted.isJsonPrimitive && accepted.asJsonPrimitive.isBoolean && !accepted.asBoolean) return null
            val idHex = str(j.get("transaction_id")) ?: throw Failure("REST transaction without an id")
            val id = unhex32(idHex)
            val inputs = ArrayList<Pair<Int, ViewInput>>()
            for ((k, any) in objects(j.get("inputs")).withIndex()) {
                val prev = str(any.get("previous_outpoint_hash"))
                val idx = num(any.get("previous_outpoint_index"))
                if (prev == null || idx == null) throw Failure("$idHex: input without its outpoint")
                val sig = unhex(str(any.get("signature_script")) ?: "")
                val order = num(any.get("index"))?.toInt() ?: k
                inputs.add(order to ViewInput(Outpoint(unhex32(prev), idx.toInt()), sig))
            }
            val outputs = ArrayList<Pair<Int, TxOutput>>()
            for ((k, any) in objects(j.get("outputs")).withIndex()) {
                val amount = num(any.get("amount"))
                val spk = str(any.get("script_public_key"))
                if (amount == null || spk == null) throw Failure("$idHex: output without amount or script")
                var covenant: CovenantBinding? = null
                val cid = str(any.get("covenant_id"))
                if (cid != null && cid.isNotEmpty()) {
                    val auth = num(any.get("covenant_authorizing_input"))
                        ?: throw Failure("$idHex: covenant output without its authorizing input")
                    covenant = CovenantBinding(auth.toInt(), unhex32(cid))
                }
                val order = num(any.get("index"))?.toInt() ?: k
                outputs.add(order to TxOutput(value = amount, scriptVersion = 0, script = unhex(spk), covenant = covenant))
            }
            val payload = unhex(str(j.get("payload")) ?: "")
            fun time(k: String): Long? = j.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            val at = time("accepting_block_time") ?: time("block_time")
            return TxView(
                id = id,
                inputs = inputs.sortedBy { it.first }.map { it.second },
                outputs = outputs.sortedBy { it.first }.map { it.second },
                payload = payload,
                at = at
            )
        }
    }
}

// The walker's registry state (cached on disk, per network)

/**
 * The registry without an indexer: the live gaps and names (and the offers this device made),
 * decoded, moved forward one spending transaction at a time from the manifest's genesis gap.
 * Hex strings throughout so the cache file stays readable.
 *
 * Swift's value semantics: every mutation REPLACES a list (never mutates one in place), so
 * `copy()` is an independent snapshot - the registry walks a copy and keeps it only on success.
 */
data class RegistryState(
    var version: Int = FORMAT_VERSION,
    var network: String,
    var registryCovenantId: String,
    var gaps: List<Gap>,
    var names: List<Name>,
    var offers: List<Offer>,
    /** transactions already applied (most recent last, bounded) */
    var applied: List<String>,
    /** every registry event the walker has seen (most recent last, bounded) */
    var events: List<Event>,
    /** when the live set was last confirmed against a node (unix ms) */
    var verifiedAt: Long? = null
) {
    data class Gap(val txid: String, val index: Int, val lo: String, val hi: String, val value: Long)

    data class Name(
        val txid: String,
        val index: Int,
        val name: String,
        val key: String,
        val owner: String,
        val price: Long,
        val expiresAt: Long,
        val value: Long,
        val registeredAt: Long? = null,
        val registeredTxId: String? = null,
        val updatedAt: Long? = null
    )

    data class Offer(
        val txid: String,
        val index: Int,
        val key: String,
        val buyer: String,
        val refundAfter: Long,
        val value: Long,
        val name: String? = null,
        val createdAt: Long? = null
    )

    /** One UTXO the walker follows, with the script it must hold. */
    class Tracked(val outpoint: String, val script: ByteArray, val registry: Boolean)

    data class WalkReport(
        var rounds: Int = 0,
        val applied: MutableList<String> = mutableListOf(),
        val events: MutableList<Event> = mutableListOf(),
        /** tracked UTXOs the node no longer has but whose spending transaction was not found yet
         *  (an indexing delay of the REST API); the next refresh retries */
        var unresolved: List<String> = emptyList()
    )

    /** Whether this cache belongs to [m]'s registry. */
    fun matches(m: Manifest): Boolean =
        version == FORMAT_VERSION && network == m.network && registryCovenantId == hex(m.registryCovenantId)

    // Reading

    fun gap(containing: ByteArray): Gap? {
        val k = hex(containing)
        return gaps.firstOrNull { it.lo < k && k < it.hi }
    }

    fun name(name: String): Name? {
        val k = hex(Codec.key(name))
        return names.firstOrNull { it.key == k }
    }

    /** The gaps on either side of a registered key: `(lo, key)` and `(key, hi)`. */
    fun neighbours(of: ByteArray): Pair<Gap, Gap>? {
        val k = hex(of)
        val below = gaps.firstOrNull { it.hi == k } ?: return null
        val above = gaps.firstOrNull { it.lo == k } ?: return null
        return below to above
    }

    /** The gaps and names tile the key space exactly. */
    fun checkInvariants() {
        val sorted = gaps.sortedBy { it.lo }
        val keys = names.map { it.key }.sorted()
        if (sorted.size != keys.size + 1) throw Failure("${sorted.size} gaps for ${keys.size} names")
        var cur = hex(KachatNames.ZERO32)
        for ((i, g) in sorted.withIndex()) {
            if (g.lo != cur) throw Failure("gap $i starts at ${g.lo.take(8)} instead of ${cur.take(8)}")
            if (g.lo >= g.hi) throw Failure("gap $i is empty")
            if (i < keys.size) {
                if (keys[i] != g.hi) throw Failure("gap $i ends at ${g.hi.take(8)} but the next name is ${keys[i].take(8)}")
                cur = keys[i]
            } else if (g.hi != hex(KachatNames.FF32)) {
                throw Failure("the last gap ends at ${g.hi.take(8)}")
            }
        }
    }

    /** Every UTXO the walker follows, with the script it must hold. */
    fun tracked(m: Manifest): List<Tracked> {
        val out = ArrayList<Tracked>()
        for (g in gaps) {
            val lo = runCatching { unhex32(g.lo) }.getOrNull() ?: continue
            val hi = runCatching { unhex32(g.hi) }.getOrNull() ?: continue
            out.add(Tracked("${g.txid}:${g.index}", m.gap.script(Codec.gapState(lo, hi)), true))
        }
        for (n in names) out.add(Tracked("${n.txid}:${n.index}", m.name.script(info(n).fields.encoded), true))
        for (o in offers) out.add(Tracked("${o.txid}:${o.index}", m.offer.script(info(o).fields.encoded), false))
        return out
    }

    // Offers this device made

    fun trackOffer(o: OfferInfo, at: Long?) {
        val txid = hex(o.outpoint.txid)
        offers = offers.filterNot { it.txid == txid && it.index == o.outpoint.index } +
            Offer(txid, o.outpoint.index, hex(o.key), hex(o.buyer), o.refundAfter, o.amount, o.name, at)
    }

    // Applying a transaction (registry.rs `Registry::apply`)

    private sealed class Predicted {
        data class GapP(val lo: String, val hi: String) : Predicted()
        data class NameP(val fields: NameFields, val name: String) : Predicted()
    }

    private class Spend(val args: List<ByteArray>, val entry: String, val redeem: ByteArray)

    /**
     * Applies one transaction. Returns its registry events; an unrelated transaction returns none.
     * Every registry output must be predicted exactly from the tracked inputs it spends (and
     * authorized by that input), or the transaction is refused and nothing changes.
     */
    fun apply(tx: TxView, manifest: Manifest): List<Event> {
        val m = manifest
        val id = tx.idHex
        if (applied.contains(id)) return emptyList()
        val registryId = unhex32(registryCovenantId)
        val regOuts = tx.outputs.indices.filter { tx.outputs[it].covenant?.covenantId?.contentEquals(registryId) == true }
        fun key(o: Outpoint) = hex(o.txid) to o.index
        val gapIns = tx.inputs.withIndex().mapNotNull { (i, input) ->
            val (t, x) = key(input.outpoint)
            gaps.firstOrNull { it.txid == t && it.index == x }?.let { i to it }
        }
        val nameIns = tx.inputs.withIndex().mapNotNull { (i, input) ->
            val (t, x) = key(input.outpoint)
            names.firstOrNull { it.txid == t && it.index == x }?.let { i to it }
        }
        val offerIns = tx.inputs.withIndex().mapNotNull { (i, input) ->
            val (t, x) = key(input.outpoint)
            offers.firstOrNull { it.txid == t && it.index == x }?.let { i to it }
        }
        val newOffer = offerFromMarker(tx, m)
        if (regOuts.isEmpty() && gapIns.isEmpty() && nameIns.isEmpty() && offerIns.isEmpty() && newOffer == null) {
            return emptyList()
        }
        val short = id.take(12)
        val events = ArrayList<Event>()
        val predicted = ArrayList<Pair<Int, Predicted>>()
        val acceptsOffer = tx.inputs.any { isOfferAccept(it, m) }

        for ((i, g) in gapIns) {
            val sp = try {
                decodeSpend(m.gap, tx.inputs[i].signatureScript)
            } catch (e: Exception) {
                throw Failure("$short: gap input $i: ${e.message}")
            }
            val lo = unhex32(g.lo)
            val hi = unhex32(g.hi)
            if (!sp.redeem.contentEquals(m.gap.redeem(Codec.gapState(lo, hi)))) {
                throw Failure("$short: gap input $i reveals a redeem script that is not the tracked gap state")
            }
            when (sp.entry) {
                "register" -> {
                    val nameBytes = sp.args.firstOrNull() ?: throw Failure("$short: register without a name")
                    val owner = arg32(sp.args, 1)
                    val now = argInt(sp.args, 3)
                    val years = argInt(sp.args, 4)
                    val name = String(nameBytes, Charsets.UTF_8)
                    val k = KachatNames.blake3(nameBytes)
                    val padded = nameBytes.copyOf(minOf(nameBytes.size, 32)).copyOf(32)
                    val f = NameFields(k, padded, owner, 0, now + years * KachatNames.YEAR_MS)
                    predicted.add(i to Predicted.GapP(g.lo, hex(k)))
                    predicted.add(i to Predicted.GapP(hex(k), g.hi))
                    predicted.add(i to Predicted.NameP(f, name))
                    events.add(Event(txId = id, op = "register", name = name, at = tx.at, to = hex(owner), years = years))
                }
                "merge" -> {
                    val succ = gapIns.firstOrNull { it.first == 2 && it.second.lo == g.hi }?.second
                        ?: throw Failure("$short: merge without the tracked successor gap at input 2")
                    predicted.add(i to Predicted.GapP(g.lo, succ.hi))
                }
                "absorbed" -> Unit
                else -> throw Failure("$short: unexpected gap entry ${sp.entry}")
            }
        }

        for ((i, n) in nameIns) {
            val sp = try {
                decodeSpend(m.name, tx.inputs[i].signatureScript)
            } catch (e: Exception) {
                throw Failure("$short: name input $i: ${e.message}")
            }
            val f = info(n).fields
            if (!sp.redeem.contentEquals(m.name.redeem(f.encoded))) {
                throw Failure("$short: name input $i reveals a redeem script that is not the tracked name state")
            }
            when (sp.entry) {
                "transfer" -> {
                    val to = arg32(sp.args, 0)
                    predicted.add(i to Predicted.NameP(f.withOwner(to), n.name))
                    // with an offer accepted, the output right after the continuation pays the old owner
                    val op = if (acceptsOffer) "offer_accepted" else "transfer"
                    events.add(Event(txId = id, op = op, name = n.name, at = tx.at, from = n.owner, to = hex(to)))
                }
                "list" -> {
                    val price = argInt(sp.args, 0)
                    predicted.add(i to Predicted.NameP(f.withPrice(price), n.name))
                    events.add(
                        Event(
                            txId = id, op = if (price == 0L) "delist" else "list", name = n.name, at = tx.at, from = n.owner,
                            price = if (price > 0) price else null
                        )
                    )
                }
                "buy" -> {
                    val to = arg32(sp.args, 0)
                    predicted.add(i to Predicted.NameP(f.withOwner(to), n.name))
                    events.add(Event(txId = id, op = "sale", name = n.name, at = tx.at, from = n.owner, to = hex(to), price = maxOf(n.price, 0L)))
                }
                "renew" -> {
                    val years = argInt(sp.args, 0)
                    predicted.add(i to Predicted.NameP(f.withExpiry(f.expiresAt + years * KachatNames.YEAR_MS), n.name))
                    events.add(Event(txId = id, op = "renew", name = n.name, at = tx.at, years = years))
                }
                "release" -> events.add(Event(txId = id, op = "release", name = n.name, at = tx.at, from = n.owner))
                "reclaim" -> events.add(Event(txId = id, op = "reclaim", name = n.name, at = tx.at, from = n.owner))
                else -> throw Failure("$short: unexpected name entry ${sp.entry}")
            }
        }

        for ((i, o) in offerIns) {
            val sp = try {
                decodeSpend(m.offer, tx.inputs[i].signatureScript)
            } catch (e: Exception) {
                throw Failure("$short: offer input $i: ${e.message}")
            }
            if (!sp.redeem.contentEquals(m.offer.redeem(info(o).fields.encoded))) {
                throw Failure("$short: offer input $i reveals a redeem script that is not the tracked offer state")
            }
            events.add(Event(txId = id, op = "offer_${sp.entry}", name = o.name, at = tx.at, to = o.buyer, price = o.value))
        }

        // Match the predictions to the registry outputs, one to one, each authorized by the input
        // that predicted it (the P2SH script commits to the whole state).
        val matched = LinkedHashMap<Int, Predicted>()
        for ((auth, p) in predicted) {
            val script = when (p) {
                is Predicted.GapP -> m.gap.script(Codec.gapState(unhex32(p.lo), unhex32(p.hi)))
                is Predicted.NameP -> m.name.script(p.fields.encoded)
            }
            val idx = regOuts.firstOrNull { j ->
                matched[j] == null && tx.outputs[j].script.contentEquals(script) && tx.outputs[j].covenant?.authorizingInput == auth
            } ?: throw Failure("$short: predicted registry output not found (authorized by input $auth)")
            matched[idx] = p
        }
        regOuts.firstOrNull { matched[it] == null }?.let {
            throw Failure("$short: registry output $it is not explained by any tracked registry input")
        }

        // Commit.
        val spent = tx.inputs.map { "${hex(it.outpoint.txid)}:${it.outpoint.index}" }.toSet()
        val carried = LinkedHashMap<String, Name>()
        for ((_, n) in nameIns) carried.putIfAbsent(n.key, n)
        val newGaps = gaps.filterNot { "${it.txid}:${it.index}" in spent }.toMutableList()
        val newNames = names.filterNot { "${it.txid}:${it.index}" in spent }.toMutableList()
        var newOffers = offers.filterNot { "${it.txid}:${it.index}" in spent }
        for (idx in matched.keys.sorted()) {
            val value = tx.outputs[idx].value
            when (val p = matched.getValue(idx)) {
                is Predicted.GapP -> newGaps.add(Gap(id, idx, p.lo, p.hi, value))
                is Predicted.NameP -> {
                    val f = p.fields
                    val k = hex(f.key)
                    val before = carried[k]
                    newNames.add(
                        Name(
                            txid = id, index = idx, name = p.name, key = k, owner = hex(f.owner), price = f.price,
                            expiresAt = f.expiresAt, value = value, registeredAt = before?.registeredAt ?: tx.at,
                            registeredTxId = before?.registeredTxId ?: id, updatedAt = tx.at
                        )
                    )
                }
            }
        }
        if (newOffer != null) {
            val (idx, fields) = newOffer
            val known = newNames.firstOrNull { it.key == hex(fields.key) }?.name
            newOffers = newOffers.filterNot { it.txid == id && it.index == idx } +
                Offer(id, idx, hex(fields.key), hex(fields.buyer), fields.refundAfter, tx.outputs[idx].value, known, tx.at)
            events.add(Event(txId = id, op = "offer", name = known, at = tx.at, to = hex(fields.buyer), price = tx.outputs[idx].value))
        }
        // an accepted offer's payout: the output right after the name continuation
        for (k in events.indices) {
            if (events[k].op != "offer_accepted") continue
            val cont = matched.entries.firstOrNull { (it.value as? Predicted.NameP)?.name == events[k].name }?.key ?: continue
            if (cont + 1 < tx.outputs.size) events[k] = events[k].copy(price = tx.outputs[cont + 1].value)
        }
        gaps = newGaps
        names = newNames
        offers = newOffers
        applied = (applied + id).takeLast(APPLIED_KEEP)
        this.events = (this.events + events).takeLast(EVENTS_KEEP)
        return events
    }

    /**
     * Moves the state forward to the chain's current registry. [live] answers which of the
     * outpoints ("txid:index") at those P2SH addresses are unspent (a node), and [transactions]
     * the accepted transactions touching an address (the REST API). Each round: every tracked UTXO
     * the node no longer has was spent; its spending transaction is found through its address and
     * applied ([apply], which decodes the spend and verifies every new state against its output's
     * script); the new outputs are tracked next round. A transaction that needs a registry input
     * not tracked yet waits for a later one in the same round. The state only ever holds outputs a
     * tracked input authorized.
     *
     * Mutates this state as it goes (Swift's `mutating`): walk a [copy] and keep it on success.
     */
    suspend fun walk(
        manifest: Manifest,
        maxRounds: Int = 64,
        address: (ByteArray) -> String?,
        live: suspend (List<String>) -> Set<String>,
        transactions: suspend (String) -> List<TxView>
    ): WalkReport {
        val m = manifest
        val report = WalkReport()
        repeat(maxRounds) {
            report.rounds += 1
            val byAddress = LinkedHashMap<String, MutableList<String>>()
            for (t in tracked(m)) {
                val a = address(t.script) ?: throw Failure("no address for a tracked script")
                byAddress.getOrPut(a) { mutableListOf() }.add(t.outpoint)
            }
            val unspent = live(byAddress.keys.sorted())
            val spent = byAddress.flatMap { (a, ops) -> ops.filter { it !in unspent }.map { a to it } }
            if (spent.isEmpty()) {
                report.unresolved = emptyList()
                return report
            }
            val candidates = LinkedHashMap<String, TxView>()
            val found = HashSet<String>()
            for (a in spent.map { it.first }.toSet().sorted()) {
                val wanted = spent.filter { it.first == a }.map { it.second }.toSet()
                for (tx in transactions(a)) {
                    val spends = tx.inputs.map { "${hex(it.outpoint.txid)}:${it.outpoint.index}" }.filter { it in wanted }
                    if (spends.isNotEmpty()) {
                        candidates[tx.idHex] = tx
                        found.addAll(spends)
                    }
                }
            }
            report.unresolved = spent.map { it.second }.filter { it !in found }.sorted()
            if (candidates.isEmpty()) return report
            var pending = candidates.values.sortedWith(compareBy<TxView>({ it.at ?: 0L }, { it.idHex }))
            var lastError: Exception? = null
            var progressed = true
            var appliedThisRound = 0
            while (progressed && pending.isNotEmpty()) {
                progressed = false
                val rest = ArrayList<TxView>()
                for (tx in pending) {
                    try {
                        val before = applied.size
                        val events = apply(tx, m)
                        if (applied.size != before || applied.contains(tx.idHex)) {
                            report.applied.add(tx.idHex)
                            report.events.addAll(events)
                        }
                        progressed = true
                        appliedThisRound += 1
                    } catch (e: Exception) {
                        lastError = e
                        rest.add(tx)
                    }
                }
                pending = rest
            }
            // nothing applied: the same spends would fail again next round
            if (appliedThisRound == 0) lastError?.let { throw it }
        }
        return report
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val APPLIED_KEEP = 4096
        const val EVENTS_KEEP = 1000

        fun atGenesis(m: Manifest): RegistryState = RegistryState(
            network = m.network,
            registryCovenantId = hex(m.registryCovenantId),
            gaps = listOf(Gap(hex(m.genesisTxid), 0, hex(m.genesisState.first), hex(m.genesisState.second), m.params.gapValue)),
            names = emptyList(),
            offers = emptyList(),
            applied = listOf(hex(m.genesisTxid)),
            events = emptyList(),
            verifiedAt = null
        )

        fun outpoint(txid: String, index: Int): Outpoint =
            Outpoint(runCatching { unhex32(txid) }.getOrNull() ?: KachatNames.ZERO32, index)

        fun info(n: Name): NameInfo = NameInfo(
            name = n.name,
            key = runCatching { unhex32(n.key) }.getOrNull() ?: KachatNames.ZERO32,
            owner = runCatching { unhex32(n.owner) }.getOrNull() ?: KachatNames.ZERO32,
            price = maxOf(n.price, 0L),
            expiresAt = n.expiresAt,
            outpoint = outpoint(n.txid, n.index),
            registeredAt = n.registeredAt,
            registeredTxId = n.registeredTxId,
            updatedAt = n.updatedAt
        )

        fun info(g: Gap): GapInfo = GapInfo(
            lo = runCatching { unhex32(g.lo) }.getOrNull() ?: KachatNames.ZERO32,
            hi = runCatching { unhex32(g.hi) }.getOrNull() ?: KachatNames.FF32,
            outpoint = outpoint(g.txid, g.index)
        )

        fun info(o: Offer): OfferInfo = OfferInfo(
            outpoint = outpoint(o.txid, o.index),
            key = runCatching { unhex32(o.key) }.getOrNull() ?: KachatNames.ZERO32,
            name = o.name,
            buyer = runCatching { unhex32(o.buyer) }.getOrNull() ?: KachatNames.ZERO32,
            amount = o.value,
            refundAfter = o.refundAfter,
            createdAt = o.createdAt
        )

        private fun decodeSpend(t: Template, sigScript: ByteArray): Spend {
            val pushes = Codec.parsePushes(sigScript).toMutableList()
            val redeem = pushes.removeLastOrNull() ?: throw Failure("empty signature script")
            val tag = pushes.removeLastOrNull() ?: throw Failure("no dispatch tag")
            val entry = t.dispatchTags.entries.firstOrNull { it.value.contentEquals(tag) }?.key
                ?: throw Failure("unknown ${t.contract} dispatch tag ${hex(tag)}")
            return Spend(pushes, entry, redeem)
        }

        private fun arg32(a: List<ByteArray>, i: Int): ByteArray {
            if (i >= a.size || a[i].size != 32) throw Failure("argument $i is not 32 bytes")
            return a[i]
        }

        private fun argInt(a: List<ByteArray>, i: Int): Long {
            if (i >= a.size) throw Failure("missing argument $i")
            return Codec.scriptNum(a[i])
        }

        /**
         * The offer a transaction announces with `kchat:1:offer:<key>:<buyer>:<refundAfter>`, if
         * one of its outputs really is that offer (KACHAT_NAMES_INDEXER.md B4).
         */
        fun offerFromMarker(tx: TxView, m: Manifest): Pair<Int, OfferFields>? {
            val text = runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(tx.payload)).toString()
            }.getOrNull() ?: return null
            val prefix = "kchat:1:offer:"
            if (!text.startsWith(prefix)) return null
            val parts = text.substring(prefix.length).split(":")
            if (parts.size != 3) return null
            val key = runCatching { unhex32(parts[0]) }.getOrNull() ?: return null
            val buyer = runCatching { unhex32(parts[1]) }.getOrNull() ?: return null
            val refundAfter = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val fields = OfferFields(key, buyer, refundAfter)
            val script = m.offer.script(fields.encoded)
            val idx = tx.outputs.indexOfFirst { it.script.contentEquals(script) && it.covenant == null }
            if (idx < 0) return null
            return idx to fields
        }

        /** Whether [input] spends an offer through `accept` (tracked or not: the redeem script it
         *  reveals is recognised by the offer template). */
        private fun isOfferAccept(input: ViewInput, m: Manifest): Boolean {
            val pushes = runCatching { Codec.parsePushes(input.signatureScript) }.getOrNull() ?: return false
            if (pushes.size < 2) return false
            if (runCatching { m.offer.stateOfRedeem(pushes[pushes.size - 1]) }.isFailure) return false
            return pushes[pushes.size - 2].contentEquals(m.offer.dispatchTags["accept"])
        }
    }
}

// Indexer API shapes (KACHAT_NAMES_INDEXER.md Part D)

/**
 * The names indexer's JSON, read with Gson like Swift's Decodable structs: a required field that
 * is missing throws (the whole response is refused), optional ones may be absent or null.
 */
object IndexerApi {
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(k: String): Long? {
        val e = get(k) ?: return null
        if (!e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        return when {
            p.isNumber -> p.asLong
            p.isString -> p.asString.toLongOrNull()
            else -> null
        }
    }

    private fun JsonObject.bool(k: String): Boolean? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(k: String): JsonArray =
        get(k)?.takeIf { it.isJsonArray }?.asJsonArray ?: throw Failure("the names indexer answered without \"$k\"")

    private fun required(v: String?, what: String): String = v ?: throw Failure("the names indexer answered without \"$what\"")

    private fun objectOf(e: JsonElement): JsonObject =
        if (e.isJsonObject) e.asJsonObject else throw Failure("the names indexer answered an unexpected body")

    class OutpointJson(val txId: String, val index: Int) {
        val outpoint: Outpoint? get() = runCatching { unhex32(txId) }.getOrNull()?.let { Outpoint(it, index) }

        companion object {
            fun from(o: JsonObject): OutpointJson =
                OutpointJson(required(o.str("txId"), "txId"), o.long("index")?.toInt() ?: throw Failure("the names indexer answered without \"index\""))
        }
    }

    class GapJson(val lo: String, val hi: String, val outpoint: OutpointJson) {
        val info: GapInfo?
            get() {
                val l = runCatching { unhex32(lo) }.getOrNull() ?: return null
                val h = runCatching { unhex32(hi) }.getOrNull() ?: return null
                val op = outpoint.outpoint ?: return null
                return GapInfo(l, h, op)
            }

        companion object {
            fun from(o: JsonObject): GapJson = GapJson(
                required(o.str("lo"), "lo"), required(o.str("hi"), "hi"),
                OutpointJson.from(o.obj("outpoint") ?: throw Failure("the names indexer answered without \"outpoint\""))
            )

            fun parse(e: JsonElement): GapJson = from(objectOf(e))
        }
    }

    /** `GET /names/{name}` and every name object in lists. */
    class NameJson(
        val name: String,
        val key: String?,
        val registered: Boolean?,
        val status: String?,
        val owner: String?,
        val ownerKey: String?,
        val price: String?,
        val expiresAt: Long?,
        val outpoint: OutpointJson?,
        val registeredAt: Long?,
        val registeredTxId: String?,
        val updatedAt: Long?,
        val gap: GapJson?
    ) {
        /** The record, when registered and complete. `ownerKey` falls back to `keyOf(owner)`. */
        fun info(keyOf: (String) -> ByteArray?): NameInfo? {
            if (registered == false) return null
            val exp = expiresAt ?: return null
            val op = outpoint?.outpoint ?: return null
            val n = Codec.normalize(name)
            if (!Codec.isValid(n)) return null
            val ownerBytes = ownerKey?.let { runCatching { unhex32(it) }.getOrNull() } ?: owner?.let(keyOf) ?: return null
            if (ownerBytes.size != 32) return null
            return NameInfo(
                name = n, key = Codec.key(n), owner = ownerBytes, price = price?.toLongOrNull() ?: 0, expiresAt = exp,
                outpoint = op, registeredAt = registeredAt, registeredTxId = registeredTxId, updatedAt = updatedAt
            )
        }

        companion object {
            fun from(o: JsonObject): NameJson = NameJson(
                name = required(o.str("name"), "name"),
                key = o.str("key"),
                registered = o.bool("registered"),
                status = o.str("status"),
                owner = o.str("owner"),
                ownerKey = o.str("ownerKey"),
                price = o.str("price"),
                expiresAt = o.long("expiresAt"),
                outpoint = o.obj("outpoint")?.let { OutpointJson.from(it) },
                registeredAt = o.long("registeredAt"),
                registeredTxId = o.str("registeredTxId"),
                updatedAt = o.long("updatedAt"),
                gap = o.obj("gap")?.let { GapJson.from(it) }
            )

            fun parse(e: JsonElement): NameJson = from(objectOf(e))

            /** `{"names": [...]}` */
            fun parseNames(e: JsonElement): List<NameJson> = objectOf(e).array("names").map { from(objectOf(it)) }

            /** `{"listings": [...], "next"}` */
            fun parseListings(e: JsonElement): List<NameJson> = objectOf(e).array("listings").map { from(objectOf(it)) }
        }
    }

    class EventJson(
        val txId: String,
        val op: String,
        val name: String?,
        val at: Long?,
        val from: String?,
        val to: String?,
        val price: String?,
        val years: Long?
    ) {
        val event: Event get() = Event(txId, op, name, at, from, to, price?.toLongOrNull(), years)

        companion object {
            fun from(o: JsonObject): EventJson = EventJson(
                required(o.str("txId"), "txId"), required(o.str("op"), "op"), o.str("name"), o.long("at"),
                o.str("from"), o.str("to"), o.str("price"), o.long("years")
            )

            /** `{"events": [...], "next"}` */
            fun parseEvents(e: JsonElement): List<Event> = objectOf(e).array("events").map { from(objectOf(it)).event }
        }
    }

    class OfferJson(
        val outpoint: OutpointJson,
        val buyer: String,
        val amount: String,
        val refundAfter: Long,
        val createdAt: Long?,
        val refundable: Boolean?,
        val name: String?
    ) {
        fun info(name: String?, keyOf: (String) -> ByteArray?): OfferInfo? {
            val op = outpoint.outpoint ?: return null
            val buyerKey = keyOf(buyer) ?: return null
            val amt = amount.toLongOrNull() ?: return null
            val n = (this.name ?: name)?.let { Codec.normalize(it) }?.takeIf { Codec.isValid(it) } ?: return null
            return OfferInfo(op, Codec.key(n), n, buyerKey, amt, refundAfter, createdAt)
        }

        companion object {
            fun from(o: JsonObject): OfferJson = OfferJson(
                outpoint = OutpointJson.from(o.obj("outpoint") ?: throw Failure("the names indexer answered without \"outpoint\"")),
                buyer = required(o.str("buyer"), "buyer"),
                amount = required(o.str("amount"), "amount"),
                refundAfter = o.long("refundAfter") ?: throw Failure("the names indexer answered without \"refundAfter\""),
                createdAt = o.long("createdAt"),
                refundable = o.bool("refundable"),
                name = o.str("name")
            )

            /** `{"offers": [...]}` */
            fun parseOffers(e: JsonElement): List<OfferJson> = objectOf(e).array("offers").map { from(objectOf(it)) }
        }
    }

    class IdentityJson(val address: String, val label: String?, val names: List<String>?, val profile: Profile?) {
        val identity: Identity get() = Identity(address, label, names ?: emptyList(), profile?.sanitized())

        companion object {
            fun parse(e: JsonElement): IdentityJson {
                val o = objectOf(e)
                return IdentityJson(
                    address = required(o.str("address"), "address"),
                    label = o.str("label"),
                    names = o.get("names")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                        it.takeIf { x -> x.isJsonPrimitive }?.asString
                    },
                    profile = Profile.decode(o.get("profile"))
                )
            }
        }
    }

    class StatusJson(
        val network: String?,
        val registryCovenantId: String?,
        val genesisTxId: String?,
        val indexedDaa: Long?,
        val synced: Boolean?
    ) {
        companion object {
            fun parse(e: JsonElement): StatusJson {
                val o = objectOf(e)
                return StatusJson(o.str("network"), o.str("registryCovenantId"), o.str("genesisTxId"), o.long("indexedDaa"), o.bool("synced"))
            }
        }
    }
}

// Key arithmetic for the indexer's gap lookups

/**
 * `key ± 1` as a 32-byte big-endian number (null past either end). The gap containing `key - 1`
 * is `(lo, key)` and the one containing `key + 1` is `(key, hi)`: the neighbours an exit
 * (release, reclaim) spends.
 */
fun KachatNames.step(key: ByteArray, by: Int): ByteArray? {
    if (key.size != 32 || (by != 1 && by != -1)) return null
    val b = key.copyOf()
    var i = 31
    while (i >= 0) {
        val v = b[i].toInt() and 0xff
        if (by == 1) {
            if (v == 0xff) { b[i] = 0; i -= 1 } else { b[i] = (v + 1).toByte(); return b }
        } else {
            if (v == 0) { b[i] = 0xff.toByte(); i -= 1 } else { b[i] = (v - 1).toByte(); return b }
        }
    }
    return null
}
