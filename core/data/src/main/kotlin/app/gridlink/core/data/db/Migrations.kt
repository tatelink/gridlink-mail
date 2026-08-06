package app.gridlink.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL that creates the `outbox` table. Kept as a constant so the 9→10 migration and a
 * plain JVM unit test execute the exact same statement.
 */
const val OUTBOX_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `outbox` (" +
        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
        "`accountId` TEXT NOT NULL, " +
        "`recipients` TEXT NOT NULL, " +
        "`cc` TEXT, " +
        "`bcc` TEXT, " +
        "`subject` TEXT NOT NULL, " +
        "`textBody` TEXT NOT NULL, " +
        "`htmlBody` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`inReplyTo` TEXT, " +
        "`references` TEXT, " +
        "`attachmentsJson` TEXT NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`notBeforeMillis` INTEGER NOT NULL, " +
        "`state` TEXT NOT NULL, " +
        "`attemptCount` INTEGER NOT NULL, " +
        "`lastError` TEXT, " +
        "`lastAttemptMillis` INTEGER)"

/**
 * Additive 9→10: add the persistent `outbox` table without touching any existing table.
 * The outbox holds unsent mail (user data), so it must never be destroyed on upgrade —
 * unlike the disposable cache, which can fall back to a destructive rebuild.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(OUTBOX_CREATE_SQL)
    }
}

/**
 * Additive 11→12: OpenPGP columns on `outbox` (mode + path of the pre-built
 * PGP/MIME entity file). Outbox rows are user data — never rebuilt destructively.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
    }
}

/**
 * 12→13: `mailboxes` gains `accountId` (composite key), so every account keeps its own
 * folder rows/counters. The table is a disposable server mirror — rebuild it in place
 * (repopulated on the next refresh) instead of letting the destructive fallback wipe the
 * whole DB, which would destroy queued outbox mail.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `mailboxes`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailboxes` (" +
                "`accountId` TEXT NOT NULL, " +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`role` TEXT, " +
                "`parentId` TEXT, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`totalEmails` INTEGER NOT NULL, " +
                "`unreadEmails` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `id`))",
        )
    }
}

/**
 * SQL that creates the `emails` table with the composite `(accountId, id)` primary key. Kept as a
 * constant so the 15→16 migration and a plain JVM unit test build the exact table that migration
 * must produce (column set, nullability and PK order; column order is irrelevant to Room's
 * validation, the PK column order is not).
 *
 * This is the **v16** shape, deliberately frozen: [MIGRATION_16_17] adds `recipientsJson` on top
 * with an `ALTER TABLE`, so folding that column in here would make the ALTER fail on a duplicate
 * column for anyone coming from v15. The table Room validates at open time is this plus the 16→17
 * column.
 */
const val EMAILS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `emails` (" +
        "`id` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, " +
        "`subject` TEXT, " +
        "`preview` TEXT, " +
        "`receivedAt` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, " +
        "`flagged` INTEGER NOT NULL, " +
        "`hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/** The index Room derives from `@Index("mailboxId")` on `emails` (name: `index_<table>_<column>`). */
const val EMAILS_MAILBOX_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_emails_mailboxId` ON `emails` (`mailboxId`)"

/** The `email_bodies` table with the composite key ([EmailBodyEntity]); shared with the JVM test. */
const val EMAIL_BODIES_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `email_bodies` (" +
        "`id` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`bodyJson` TEXT NOT NULL, " +
        "`inlineImagesJson` TEXT NOT NULL, " +
        "`fetchedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/** The `snoozed` table with the composite key ([SnoozedEntity]); shared with the JVM test. */
const val SNOOZED_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `snoozed` (" +
        "`emailId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`until` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `emailId`))"

/** Ordered column lists of the three rebuilt tables (identical in v13 and v14 — only the PK changes). */
const val EMAILS_COLUMNS: String =
    "`id`, `accountId`, `mailboxId`, `threadId`, `subject`, `preview`, `receivedAt`, " +
        "`fromName`, `fromEmail`, `seen`, `flagged`, `hasAttachment`, `sortKey`"
const val EMAIL_BODIES_COLUMNS: String = "`id`, `accountId`, `bodyJson`, `inlineImagesJson`, `fetchedAt`"
const val SNOOZED_COLUMNS: String = "`emailId`, `accountId`, `until`"

/**
 * Rebuild [table] under its new composite-key DDL [createSql], copying every row over by explicit
 * [columns] (never positionally). Old single-column keys were globally unique, so no collision is
 * possible on the way in.
 */
private fun SupportSQLiteDatabase.rebuildTable(table: String, createSql: String, columns: String) {
    execSQL(createSql.replace("`$table`", "`${table}_new`"))
    execSQL("INSERT INTO `${table}_new` ($columns) SELECT $columns FROM `$table`")
    execSQL("DROP TABLE `$table`")
    execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
}

/**
 * Additive 13→14 (released as 1.3.10): `outbox` and `scheduled_sends` gain `draftEmailId` — the
 * server draft an edited message came from, destroyed once the send succeeds so no duplicate
 * lingers in Drafts (#63). Both tables hold unsent user mail — never rebuilt destructively.
 *
 * This is the RELEASED v14 schema. The multi-account composite-key change (issue #31) is layered
 * on top as [MIGRATION_15_16], not folded in here.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftEmailId` TEXT")
    }
}

/**
 * Exact `CREATE VIRTUAL TABLE` Room generates for [EmailFtsEntity] with the
 * `remove_diacritics=1` tokenizer (copied verbatim from Room's own `createAllTables`
 * output). The 14→15 migration recreates the table with this string so Room's startup
 * identity check (`FtsTableInfo`) matches; any drift from Room's generated form would throw
 * "Migration didn't properly handle email_fts".
 */
const val EMAIL_FTS_CREATE_SQL: String =
    "CREATE VIRTUAL TABLE IF NOT EXISTS `email_fts` USING FTS4(" +
        "`emailId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, `subject` TEXT NOT NULL, `sender` TEXT NOT NULL, `body` TEXT NOT NULL, " +
        "`preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, `hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, tokenize=unicode61 `remove_diacritics=1`, " +
        "notindexed=`emailId`, notindexed=`accountId`, notindexed=`mailboxId`, " +
        "notindexed=`threadId`, notindexed=`preview`, notindexed=`receivedAt`, " +
        "notindexed=`fromName`, notindexed=`fromEmail`, notindexed=`seen`, notindexed=`flagged`, " +
        "notindexed=`hasAttachment`, notindexed=`sortKey`)"

/**
 * 14→15: rebuild the `email_fts` search index with `remove_diacritics=1`.
 *
 * The old table was created with `remove_diacritics=2`, which the unicode61 tokenizer only
 * understands on SQLite >= 3.27 (2019). Devices on Android <= 9 ship an older SQLite that
 * rejects the argument, so Room's `CREATE VIRTUAL TABLE` failed and the app crashed on first
 * DB access at startup (#71). `remove_diacritics=1` folds the common accents we need and is
 * supported everywhere.
 *
 * The tokenizer is baked into the table's CREATE statement, so we cannot ALTER it: drop and
 * recreate. `email_fts` is an FTS4 virtual table — dropping it also drops its shadow tables
 * (`email_fts_content/_segments/_segdir/_docsize/_stat`). The index is a disposable derivative
 * of `emails` (and the background crawl), never user data, so rebuilding it is safe; a
 * destructive fallback, by contrast, would also wipe the outbox (queued unsent mail).
 *
 * Repopulate from the cached `emails` rows so search is not empty right after the upgrade —
 * the exact query [EmailFtsDao.insertFromEmails] uses (headers only, `body` empty; the full
 * mailbox is re-covered by the background index crawl). Crawled-only rows that were not in the
 * display cache are lost on drop and re-indexed by that crawl.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `email_fts`")
        db.execSQL(EMAIL_FTS_CREATE_SQL)
        db.execSQL(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "SELECT id, accountId, mailboxId, threadId, COALESCE(subject, ''), " +
                "TRIM(COALESCE(fromName, '') || ' ' || COALESCE(fromEmail, '')), '', " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey " +
                "FROM emails",
        )
    }
}

/**
 * 15→16: widen the primary keys of `emails`, `email_bodies` and `snoozed` from the email id alone
 * to the composite `(accountId, id)` — a JMAP email id is unique only within its JMAP account
 * (RFC 8620 §1.6.2), so two accounts under one login (issue #31) can each hold a message with the
 * same id; a single-column key let one account's sync clobber (or serve back) the other's row.
 *
 * Layered on top of the RELEASED v15 schema (1.3.11's FTS rebuild), not folded into it: real
 * installs are on v15, and that is the upgrade path every user takes.
 *
 * Non-destructive: every existing row is copied over verbatim. The v15 tables already carry a
 * non-null, populated `accountId` column (present since before 1.3.10) — the single pre-multi-
 * account login wrote its own accountId into every cache row — so the rebuild simply promotes
 * that existing value into the composite key; no backfill of a missing column is needed. Old
 * single-column ids were globally unique, so no collision is possible on the way in. `snoozed`
 * is user data, so preserving it is mandatory; the caches merely avoid a pointless re-download.
 *
 * `email_fts` is left untouched: it is a standalone FTS4 table (no `content=` link to `emails`,
 * no triggers), so rebuilding `emails` under it neither drops nor invalidates the search index.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.rebuildTable("emails", EMAILS_CREATE_SQL, EMAILS_COLUMNS)
        db.execSQL(EMAILS_MAILBOX_INDEX_SQL)
        // The old accountId index is subsumed by the new key's prefix.
        db.execSQL("DROP INDEX IF EXISTS `index_email_bodies_accountId`")
        db.rebuildTable("email_bodies", EMAIL_BODIES_CREATE_SQL, EMAIL_BODIES_COLUMNS)
        db.rebuildTable("snoozed", SNOOZED_CREATE_SQL, SNOOZED_COLUMNS)
    }
}

/**
 * Additive 16→17: `emails` gains `recipientsJson`, the message's `To:` addresses
 * ([EmailRecipients], see [EmailEntity.recipientsJson]).
 *
 * Sent/Drafts rows show who the mail went TO instead of its sender (#59), but the recipients only
 * ever lived in a process-lifetime memo — so from a cold cache the row fell back to the sender and
 * was corrected a network round-trip later, which is the flicker #63 reported. Persisting them
 * makes the row right from the first frame, offline included.
 *
 * A plain `ALTER TABLE … ADD COLUMN`, layered on top of [MIGRATION_15_16] rather than folded into
 * it: v16 is already carried by test installs (and covered by `EmailsMigrationSqlTest` from a real
 * v15 schema), and rewriting a released migration in place would fail Room's identity check on the
 * next launch. Nullable with no default, so every existing row keeps its data and simply reads back
 * as "no recipients" — the pre-v17 behaviour. No backfill: the addresses are not held locally, so
 * an old row gains them at its next sync.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `recipientsJson` TEXT")
    }
}

/**
 * SQL that creates the `purge_snapshot` table ([PurgeSnapshotEntity]). Kept as a constant so the
 * 17→18 migration and a plain JVM unit test build the exact same table.
 */
const val PURGE_SNAPSHOT_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `purge_snapshot` (" +
        "`purgeId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`emailId` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`purgeId`, `accountId`, `emailId`))"

/**
 * Additive 17→18: the `purge_snapshot` table (Codeberg #99). An "Empty trash" now records the
 * exact messages the user confirmed destroying and the held-back purge destroys only those,
 * instead of re-reading the folder when it finally runs and taking along whatever arrived
 * meanwhile.
 *
 * Purely additive — no existing table is touched, so nothing can be lost on upgrade. The table
 * starts empty, which is the correct state: a purge confirmed on the previous version has no
 * snapshot, and a purge with no snapshot destroys nothing.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_CREATE_SQL)
    }
}

/**
 * The UIDVALIDITY a snapshot was taken under ([PurgeSnapshotEntity.uidValidity]). NULLABLE, and
 * the null is load-bearing: a snapshot written by the previous version carries no value, which
 * reads as "cannot be verified" and destroys NOTHING — the same choice `MessageDestroyWorker`
 * already makes for a purge enqueued before it knew about snapshots.
 */
const val PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL: String =
    "ALTER TABLE `purge_snapshot` ADD COLUMN `uidValidity` INTEGER"

/** The `mailbox_uidvalidity` table ([MailboxUidValidityEntity]); shared with the JVM test. */
const val MAILBOX_UIDVALIDITY_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `mailbox_uidvalidity` (" +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`uidValidity` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `mailboxId`))"

/**
 * Additive 18→19: remember which numbering an IMAP folder's UIDs belong to (Codeberg #99).
 *
 * A server that renumbers a folder (a migration, a rebuilt index, a restore) bumps its
 * UIDVALIDITY, and every UID cached for it then names a different message — or nothing.
 * Two records, two lifetimes: one carried by an Empty-trash snapshot, so a held-back purge can
 * refuse to destroy against a folder that has been renumbered under it; one per folder, so the
 * body cache — the one store no refresh prunes — can be dropped when the numbering moves.
 *
 * Purely additive: the new column is nullable with no default and the new table starts empty,
 * both of which read as "not known", which is the conservative state.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL)
        db.execSQL(MAILBOX_UIDVALIDITY_CREATE_SQL)
    }
}

/** The `dav_collections` table ([DavCollectionEntity]); shared with the JVM test. */
const val DAV_COLLECTIONS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `dav_collections` (" +
        "`accountId` TEXT NOT NULL, " +
        "`url` TEXT NOT NULL, " +
        "`kind` TEXT NOT NULL, " +
        "`displayName` TEXT, " +
        "`color` TEXT, " +
        "`syncToken` TEXT, " +
        "`sortOrder` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `url`))"

/** The `calendar_events` table ([CalendarEventEntity]); shared with the JVM test. */
const val CALENDAR_EVENTS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `calendar_events` (" +
        "`accountId` TEXT NOT NULL, " +
        "`href` TEXT NOT NULL, " +
        "`collectionUrl` TEXT NOT NULL, " +
        "`etag` TEXT, " +
        "`uid` TEXT NOT NULL, " +
        "`summary` TEXT, " +
        "`location` TEXT, " +
        "`organizerEmail` TEXT, " +
        "`startLocal` TEXT NOT NULL, " +
        "`endLocal` TEXT, " +
        "`zoneId` TEXT NOT NULL, " +
        "`allDay` INTEGER NOT NULL, " +
        "`cancelled` INTEGER NOT NULL, " +
        "`rrule` TEXT, " +
        "`exDates` TEXT NOT NULL, " +
        "`recurrenceId` TEXT, " +
        "`startDay` INTEGER NOT NULL, " +
        "`endDay` INTEGER, " +
        "`raw` TEXT NOT NULL, " +
        "PRIMARY KEY(`accountId`, `href`))"

/**
 * The index behind the month query. Without it every month change is a full table scan, which is
 * invisible on the 27-event account this was written against and is not on a shared calendar with
 * ten years of history in it.
 */
const val CALENDAR_EVENTS_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_calendar_events_accountId_startDay` " +
        "ON `calendar_events` (`accountId`, `startDay`)"

/** The `address_book_contacts` table ([AddressBookContactEntity]); shared with the JVM test. */
const val ADDRESS_BOOK_CONTACTS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `address_book_contacts` (" +
        "`accountId` TEXT NOT NULL, " +
        "`href` TEXT NOT NULL, " +
        "`collectionUrl` TEXT NOT NULL, " +
        "`etag` TEXT, " +
        "`uid` TEXT NOT NULL, " +
        "`displayName` TEXT NOT NULL, " +
        "`fileAsFamily` TEXT NOT NULL, " +
        "`fileAsGiven` TEXT NOT NULL, " +
        "`organization` TEXT, " +
        "`title` TEXT, " +
        "`isOrganization` INTEGER NOT NULL, " +
        "`primaryEmail` TEXT NOT NULL, " +
        "`emails` TEXT NOT NULL, " +
        "`raw` TEXT NOT NULL, " +
        "PRIMARY KEY(`accountId`, `href`))"

/** The index behind the address book's sort, so opening Contacts is not a sort of the whole table. */
const val ADDRESS_BOOK_CONTACTS_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_address_book_contacts_accountId_fileAsFamily` " +
        "ON `address_book_contacts` (`accountId`, `fileAsFamily`)"

/**
 * Additive 19→20: the CalDAV and CardDAV mirror.
 *
 * Purely additive — three new tables, nothing existing is touched. All three start empty, which is
 * the correct state: an account upgraded from the previous version has never run a DAV sync, and an
 * empty `dav_collections` is exactly what makes the next sync discover and fetch everything.
 *
 * Written as a migration rather than left to the destructive fallback because that fallback would
 * take the outbox (unsent mail) with it, and adding a calendar is not a reason to lose a queued
 * message.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(DAV_COLLECTIONS_CREATE_SQL)
        db.execSQL(CALENDAR_EVENTS_CREATE_SQL)
        db.execSQL(CALENDAR_EVENTS_INDEX_SQL)
        db.execSQL(ADDRESS_BOOK_CONTACTS_CREATE_SQL)
        db.execSQL(ADDRESS_BOOK_CONTACTS_INDEX_SQL)
    }
}
