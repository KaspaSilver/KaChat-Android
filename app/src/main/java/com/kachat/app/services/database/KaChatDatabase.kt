package com.kachat.app.services.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.DeletedContactEntity
import com.kachat.app.models.GroupEntity
import com.kachat.app.models.GroupMessageEntity
import com.kachat.app.models.GroupSyncCursorEntity
import com.kachat.app.models.HiddenBroadcastSenderEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.MessageSyncCursorEntity
import com.kachat.app.models.PortfolioEntity
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.models.ReactionEntity
import com.kachat.app.models.SwapTransactionEntity

/**
 * Room database — local persistence layer.
 *
 * Equivalent to the Core Data stack in the iOS app.
 * Phase 4 will add DAOs for messages and contacts.
 * Phase 8 will add sync metadata for multi-device support.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        BroadcastChannelEntity::class,
        BroadcastMessageEntity::class,
        HiddenBroadcastSenderEntity::class,
        DeletedContactEntity::class,
        MessageSyncCursorEntity::class,
        PortfolioTransactionEntity::class,
        PortfolioEntity::class,
        SwapTransactionEntity::class,
        GroupEntity::class,
        GroupMessageEntity::class,
        GroupSyncCursorEntity::class,
        ReactionEntity::class,
    ],
    version = 36,
    exportSchema = true
)
abstract class KaChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun broadcastDao(): BroadcastDao
    abstract fun portfolioDao(): PortfolioDao
    abstract fun portfolioDefinitionDao(): PortfolioDefinitionDao
    abstract fun swapDao(): SwapDao
    abstract fun groupDao(): GroupDao
    abstract fun reactionDao(): ReactionDao

    companion object {
        /**
         * True if [table] already has a column named [column]. Used to make the additive
         * `ALTER TABLE ... ADD COLUMN` migrations idempotent: some open-testing devices received a
         * column before its schema version was bumped (Room recreated their DB via
         * fallbackToDestructiveMigration with the column already present, but stamped at the old
         * version), so replaying the migration would otherwise throw "duplicate column name" and
         * crash — a failing *registered* migration is not caught by fallbackToDestructiveMigration.
         */
        private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
                false
            }

        /**
         * v15 -> v16: drops `contacts.isArchived` (replaced by full delete + [DeletedContactEntity]
         * tombstones) and adds the new `deleted_contacts` table. v15 is the schema the last public
         * release ("2.0 The Broadcast Update") actually shipped with — without this, anyone
         * updating from that release would silently lose every local contact/message the moment
         * Room's `fallbackToDestructiveMigration` kicked in.
         *
         * SQLite didn't gain `ALTER TABLE ... DROP COLUMN` until 3.35 (2021), and Android's bundled
         * SQLite version varies by OS release, so this uses the portable rebuild-the-table pattern
         * instead: create the new-shape table, copy every row across explicitly (column-by-column,
         * skipping isArchived), drop the old table, rename. The exact resulting `contacts` shape
         * (column names/types/order/PK) is copied verbatim from Room's own generated schema
         * (`app/schemas/.../16.json`), and this was verified end-to-end against a real SQLite
         * engine — a v15-shaped table seeded with sample rows (mixed isArchived/nulls), migrated,
         * checked column-for-column against the expected v16 shape, and confirmed every row's data
         * survived intact — before being written here.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contacts_new` (" +
                        "`id` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `alias` TEXT, `knsName` TEXT, " +
                        "`publicKeyHex` TEXT, `handshakeComplete` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "`conversationStatus` TEXT NOT NULL, `theirAlias` TEXT, `myAlias` TEXT, `knsAvatarUrl` TEXT, " +
                        "`systemContactId` TEXT, `systemContactName` TEXT, `systemContactLinkSource` TEXT, " +
                        "PRIMARY KEY(`id`, `walletAddress`))"
                )
                db.execSQL(
                    "INSERT INTO `contacts_new` (id, walletAddress, alias, knsName, publicKeyHex, handshakeComplete, " +
                        "addedAt, conversationStatus, theirAlias, myAlias, knsAvatarUrl, systemContactId, systemContactName, systemContactLinkSource) " +
                        "SELECT id, walletAddress, alias, knsName, publicKeyHex, handshakeComplete, " +
                        "addedAt, conversationStatus, theirAlias, myAlias, knsAvatarUrl, systemContactId, systemContactName, systemContactLinkSource FROM `contacts`"
                )
                db.execSQL("DROP TABLE `contacts`")
                db.execSQL("ALTER TABLE `contacts_new` RENAME TO `contacts`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_contacts` (" +
                        "`contactId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contactId`, `walletAddress`))"
                )
            }
        }

        /**
         * v16 -> v17: adds `message_sync_cursors`, tracking per-(contact, alias) how far into the
         * indexer's `contextual-messages/by-sender` stream this wallet has synced, so future syncs
         * pass the indexer's `block_time` cursor instead of re-fetching the same recent window
         * every time — see [MessageSyncCursorEntity]. Purely additive (a brand-new empty table),
         * so unlike v15->v16 there's no existing data to preserve or column shape to reproduce —
         * every contact just does one final "no cursor yet" catch-up sync post-upgrade, then
         * switches to incremental. Table shape copied verbatim from Room's generated schema
         * (`app/schemas/.../17.json`).
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_sync_cursors` (" +
                        "`contactId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `aliasHex` TEXT NOT NULL, `lastBlockTime` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`contactId`, `walletAddress`, `aliasHex`))"
                )
            }
        }

        /**
         * v17 -> v18: adds `portfolio_transactions` — the KAS portfolio tracker's manually-entered
         * buy/sell ledger (see [PortfolioTransactionEntity]). Purely additive, same as v16->v17;
         * table shape copied verbatim from Room's generated schema (`app/schemas/.../18.json`) and
         * validated against real SQLite before being written here.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `portfolio_transactions` (" +
                        "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `amountSompi` INTEGER NOT NULL, " +
                        "`fiatValue` REAL NOT NULL, `timestampMillis` INTEGER NOT NULL, `notes` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v18 -> v19: adds `contacts.photoAutoDisplayOverride` (nullable [PhotoAutoDisplayMode]
         * name, null = automatic) backing the per-contact photo auto-display picker in Chat Info.
         * A single nullable column addition, so a plain `ALTER TABLE ... ADD COLUMN` suffices —
         * no rebuild-the-table dance needed (that was only required for the v15->v16 column drop).
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Idempotent: an earlier open-testing build shipped this column before the version
                // was bumped, so some devices already have it at stored version 18. A plain
                // ALTER ... ADD COLUMN would then throw "duplicate column name", and because this
                // migration is registered (just failing), fallbackToDestructiveMigration can't
                // rescue it — it crashes on first DB access. See columnExists below.
                if (!columnExists(db, "contacts", "photoAutoDisplayOverride")) {
                    db.execSQL("ALTER TABLE `contacts` ADD COLUMN `photoAutoDisplayOverride` TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * v19 -> v20: adds `deleted_contacts.deletedAtTxIds`, a comma-joined tie-breaker set of the
         * transaction ids that shared the tombstone's exact `deletedAt` block_time — see
         * [DeletedContactEntity.deletedAtTxIds]'s doc comment. Fixes a real bug: a plain
         * `blockTime &lt;= deletedAt` comparison could wrongly filter out a genuinely new interaction
         * from a re-contacted, previously-deleted sender whenever it happened to land at the exact
         * same block_time as the deleted one (Kaspa's DAG-based block_time isn't strictly
         * monotonic per sender) — most reproducible via a payment, since `syncPayments` has no
         * per-contact cursor and re-checks the tombstone on every ~2s poll. Existing tombstone rows
         * default to `''` (no tie-breaker ids), which is strictly safe: it only means an *old*
         * tombstoned transaction that happens to be re-checked won't get the txId-match protection
         * pre-upgrade — it still gets caught by the more common `blockTime &lt; deletedAt` branch.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Idempotent — see MIGRATION_18_19. This is the one confirmed crashing in the
                // field: devices with a stored-version-19 DB that already has deletedAtTxIds hit
                // "duplicate column name" here on update.
                if (!columnExists(db, "deleted_contacts", "deletedAtTxIds")) {
                    db.execSQL("ALTER TABLE `deleted_contacts` ADD COLUMN `deletedAtTxIds` TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /**
         * v20 -> v21: adds `contacts.notificationOverride` (nullable [com.kachat.app.models.ContactNotificationMode]
         * name, null = follow Settings > Notifications) backing the per-contact "Incoming
         * Notifications" picker in Chat Info — same shape of change as v18->v19's
         * `photoAutoDisplayOverride`, so the same plain `ALTER TABLE ... ADD COLUMN` suffices.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Idempotent — see MIGRATION_18_19.
                if (!columnExists(db, "contacts", "notificationOverride")) {
                    db.execSQL("ALTER TABLE `contacts` ADD COLUMN `notificationOverride` TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * v21 -> v22: adds `swap_transactions` — local history of ChangeNOW-powered swaps this
         * device has started (see [SwapTransactionEntity]). Purely additive, same shape of change
         * as v16->v17/v17->v18's new tables.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `swap_transactions` (" +
                        "`id` TEXT NOT NULL, `fromTicker` TEXT NOT NULL, `fromNetwork` TEXT NOT NULL, " +
                        "`toTicker` TEXT NOT NULL, `toNetwork` TEXT NOT NULL, `fromAmount` TEXT NOT NULL, " +
                        "`toAmount` TEXT NOT NULL, `payinAddress` TEXT NOT NULL, `payoutAddress` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `kasSendTxId` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v22 -> v23: adds `swap_transactions.addedToPortfolio` so the Swap History detail view's
         * "Add to Portfolio" action can only fire once per swap (otherwise reopening a finished
         * swap and tapping it again would double-count the KAS in the portfolio's holdings math).
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `swap_transactions` ADD COLUMN `addedToPortfolio` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v23 -> v24: adds `groups` and `group_messages` — group chat metadata and message
         * cache (see [GroupEntity]/[GroupMessageEntity]). Purely additive, same shape of change
         * as the v16->v17/v17->v18/v21->v22 new-table migrations. Secret key material (group
         * seed/root epoch/blinding key) deliberately isn't here — it lives in
         * [com.kachat.app.services.GroupSecretStore]'s own encrypted prefs, not this database.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `groups` (" +
                        "`groupId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`adminAddress` TEXT NOT NULL, `adminXOnlyPubKeyHex` TEXT NOT NULL, `currentEpoch` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `isAdmin` INTEGER NOT NULL, `membersJson` TEXT NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `walletAddress`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_messages` (" +
                        "`txId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                        "`senderAddress` TEXT, `senderIdHex` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`msgIdHex` TEXT NOT NULL, `contentEncryptedHex` TEXT NOT NULL, `blockTimestamp` INTEGER NOT NULL, " +
                        "`isOutgoing` INTEGER NOT NULL, `deliveryStatus` TEXT NOT NULL, " +
                        "PRIMARY KEY(`txId`, `walletAddress`))"
                )
            }
        }

        /**
         * v24 -> v25: adds `group_sync_cursors`, tracking how far into the indexer's new
         * `group-messages/by-blinded-group-id`/`group-control/by-sender` streams this wallet has
         * synced (see [GroupSyncCursorEntity]) - group chat catch-up, mirroring
         * `message_sync_cursors` (v16->v17) for 1:1 contextual messages. Purely additive.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_sync_cursors` (" +
                        "`syncKey` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `lastBlockTime` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`syncKey`, `walletAddress`))"
                )
            }
        }

        /**
         * v25 -> v26: adds `group_sync_cursors.cursor` - the indexer's opaque lossless pagination
         * cursor, replacing plain `block_time` for group catch-up sync (multiple items can share
         * a `block_time`, which a numeric-only cursor can't disambiguate - see
         * docs/GROUP_CHAT_API.md). `lastBlockTime` is left in place unused rather than dropped;
         * nothing was ever shipped against the v25-only shape.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `group_sync_cursors` ADD COLUMN `cursor` TEXT DEFAULT NULL")
            }
        }

        /** Backs the Group Chats tab's unread badge - see GroupEntity.lastReadAt's doc comment. */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `lastReadAt` INTEGER DEFAULT NULL")
            }
        }

        /**
         * Scopes the portfolio ledger per wallet - see PortfolioTransactionEntity's doc comment.
         * Existing rows get walletAddress='' (a non-null default, since SQLite's ALTER TABLE ADD
         * COLUMN NOT NULL requires one on a non-empty table) and are claimed for a real address
         * lazily by PortfolioRepository, not here - a Migration only has the raw database, not
         * WalletManager's active-account state.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `portfolio_transactions` ADD COLUMN `walletAddress` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds the "up to 5 named portfolios per wallet" split - see PortfolioEntity's doc
         * comment. New `portfolios` table plus a `portfolioId` column on `portfolio_transactions`
         * (existing rows get '', a non-null default for the same ALTER TABLE reason as
         * MIGRATION_27_28's `walletAddress`) - claimed for the wallet's default portfolio lazily
         * by PortfolioRepository/PortfolioManager, not here.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `portfolios` (
                        `id` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))"""
                )
                db.execSQL("ALTER TABLE `portfolio_transactions` ADD COLUMN `portfolioId` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds "Add Kaspa Address" auto-import support - see PortfolioTransactionEntity's doc
         * comment. Both columns are genuinely nullable (not a migrated-in requirement like the
         * two ALTER TABLEs above), so no lazy-claim step is needed here or in PortfolioRepository.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `portfolio_transactions` ADD COLUMN `sourceAddress` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `portfolio_transactions` ADD COLUMN `sourceTxId` TEXT DEFAULT NULL")
            }
        }

        /**
         * Adds `reactions` - tapback-style reactions sent/received for both 1:1 and group
         * messages (one row per (targetTxId, walletAddress, reactorAddress); see
         * [com.kachat.app.models.ReactionEntity]). Purely additive, no data to backfill.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `reactions` (
                        `targetTxId` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, `reactorAddress` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL, `reactionTxId` TEXT, `blockTimestamp` INTEGER NOT NULL,
                        `contactId` TEXT, `groupId` TEXT,
                        PRIMARY KEY(`targetTxId`, `walletAddress`, `reactorAddress`))"""
                )
            }
        }

        /**
         * Adds reaction send-status columns so a reaction that fails to send can show the red error
         * icon + Retry (see [com.kachat.app.models.ReactionEntity]). Purely additive; existing rows
         * default to "sent".
         */
        /**
         * v32 -> v33: broadcast hidden senders become PER-ROOM - adds `channelName` to
         * `broadcast_hidden_senders` (default "" = every room, so existing hides keep their
         * old app-wide effect) and widens the primary key to include it. Table rebuild, same
         * portable pattern as MIGRATION_15_16.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `broadcast_hidden_senders_new` (" +
                        "`senderAddress` TEXT NOT NULL, `walletAddress` TEXT NOT NULL, " +
                        "`channelName` TEXT NOT NULL DEFAULT '', `hiddenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`senderAddress`, `walletAddress`, `channelName`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `broadcast_hidden_senders_new` (senderAddress, walletAddress, channelName, hiddenAt) " +
                        "SELECT senderAddress, walletAddress, '', hiddenAt FROM `broadcast_hidden_senders`"
                )
                db.execSQL("DROP TABLE `broadcast_hidden_senders`")
                db.execSQL("ALTER TABLE `broadcast_hidden_senders_new` RENAME TO `broadcast_hidden_senders`")
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reactions` ADD COLUMN `deliveryStatus` TEXT NOT NULL DEFAULT 'sent'")
                db.execSQL("ALTER TABLE `reactions` ADD COLUMN `failedAction` TEXT DEFAULT NULL")
            }
        }

        /**
         * v33 -> v34: adds `contacts.systemContactPhotoUri` — the device address-book photo of a
         * linked phone contact, so it can stand in wherever a KNS avatar would render. A single
         * nullable column, so the plain `ALTER TABLE ... ADD COLUMN` form is enough (same shape as
         * v18->v19/v20->v21). Existing linked contacts start `NULL` and are backfilled lazily by
         * `ChatViewModel.syncSystemContacts`, which now re-reads the photo URI for already-linked
         * contacts too — no migration-time ContactsContract access (a Migration has only the raw
         * database, and READ_CONTACTS may not even be granted at upgrade time).
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Guarded like the 26->27/27->28/29->30 additions above: a device that somehow
                // already has the column (a destructively recreated DB stamped at an older
                // version) would otherwise hit "duplicate column name" here, and a *registered*
                // migration that throws is NOT caught by fallbackToDestructiveMigration.
                if (!columnExists(db, "contacts", "systemContactPhotoUri")) {
                    db.execSQL("ALTER TABLE `contacts` ADD COLUMN `systemContactPhotoUri` TEXT DEFAULT NULL")
                }
            }
        }

        // Cross-platform contact photo carried in the shared backup (base64 JPEG), an avatar
        // fallback when there is no KNS or device-contact photo.
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "contacts", "backupPhotoBase64")) {
                    db.execSQL("ALTER TABLE `contacts` ADD COLUMN `backupPhotoBase64` TEXT DEFAULT NULL")
                }
            }
        }

        // Admin-set group photo (hex of a compressed JPEG), distributed to members via gctl_photo.
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "groups", "photoHex")) {
                    db.execSQL("ALTER TABLE `groups` ADD COLUMN `photoHex` TEXT DEFAULT NULL")
                }
            }
        }
    }
}
