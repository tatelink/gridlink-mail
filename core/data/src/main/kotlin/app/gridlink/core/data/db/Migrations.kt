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

/** The two `myRights` columns added to `mailboxes` in v21; shared with the JVM test. */
const val MAILBOXES_ADD_MAY_RENAME_SQL: String =
    "ALTER TABLE `mailboxes` ADD COLUMN `mayRename` INTEGER"

const val MAILBOXES_ADD_MAY_DELETE_SQL: String =
    "ALTER TABLE `mailboxes` ADD COLUMN `mayDelete` INTEGER"

/**
 * Additive 20→21: what the server says this account may do to each mailbox.
 *
 * Two nullable columns on `mailboxes`, holding JMAP's `myRights.mayRename` and `mayDelete`. Every
 * existing row gets NULL, which is deliberate and is why the columns are nullable at all: NULL means
 * "never asked", not "not allowed", and the folder tree falls back to the rule it used before this
 * migration existed. Defaulting to 0 would have taken Rename and Delete off every folder in the
 * account until the next sync; defaulting to 1 would have promised a right the server may refuse.
 *
 * A migration rather than the destructive fallback for the same reason as every other one here: the
 * fallback takes the outbox (unsent mail) with it, and learning a folder's permissions is not a
 * reason to lose a queued message.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MAILBOXES_ADD_MAY_RENAME_SQL)
        db.execSQL(MAILBOXES_ADD_MAY_DELETE_SQL)
    }
}

/**
 * The v22 `CREATE VIRTUAL TABLE`: [EMAIL_FTS_CREATE_SQL] with `notindexed=preview` gone, so the
 * tokenizer indexes the one column on this table that has ever held body text.
 *
 * 🔴 A SEPARATE constant rather than an edit to [EMAIL_FTS_CREATE_SQL], which is not a description
 * of the current table: it is the exact statement the 14→15 rebuild has to emit, frozen at the shape
 * v15 shipped with, and `EmailsMigrationSqlTest` seeds a v15 database from it. Editing it in place
 * would quietly rewrite history and make that test assert against a schema no released build ever
 * had. Both statements are correct, for different versions, and both are needed.
 *
 * ⚠️ Column order, backticks and clause order are copied from Room's own `createAllTables` output,
 * for [EMAIL_FTS_CREATE_SQL]'s reason: any drift throws "Migration didn't properly handle email_fts"
 * at startup, on every device, after the upgrade rather than during the build.
 */
const val EMAIL_FTS_CREATE_SQL_V22: String =
    "CREATE VIRTUAL TABLE IF NOT EXISTS `email_fts` USING FTS4(" +
        "`emailId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, `subject` TEXT NOT NULL, `sender` TEXT NOT NULL, `body` TEXT NOT NULL, " +
        "`preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, `hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, tokenize=unicode61 `remove_diacritics=1`, " +
        "notindexed=`emailId`, notindexed=`accountId`, notindexed=`mailboxId`, " +
        "notindexed=`threadId`, notindexed=`receivedAt`, " +
        "notindexed=`fromName`, notindexed=`fromEmail`, notindexed=`seen`, notindexed=`flagged`, " +
        "notindexed=`hasAttachment`, notindexed=`sortKey`)"

/**
 * 21→22: rebuild `email_fts` with `preview` indexed, so a local search matches the opening of the
 * body and not only the subject and the sender.
 *
 * Which column set is tokenized is baked into the table's CREATE statement, exactly like the
 * tokenizer argument 14→15 had to change, so there is no ALTER for this either: drop and recreate.
 * The same reasoning applies verbatim — the FTS4 shadow tables go with the drop, and the index is a
 * disposable derivative of `emails` plus the background crawl, never user data. A destructive
 * fallback would instead take the outbox (unsent mail) with it, which is why this exists at all.
 *
 * 🔴 Repopulated WITH the Trash/Junk/Spam exclusion, which 14→15 left out. That omission is the
 * documented source of the mislabelled rows `EmailFtsDao.search`'s folder filter exists to catch
 * (see its KDoc), and repeating it here would seed a fresh crop of them on every upgrade. The roles
 * are spelled out as literals because a `Migration` runs on raw SQL with no bound parameters and
 * cannot reach `NOT_SEARCHED_ROLES`; `SearchIndexMigrationRolesTest` is what keeps this list and
 * that set from drifting apart, and it is the only thing that does.
 *
 * ⚠️ Crawled-only rows — mail older than the display cache's window, which only the background crawl
 * ever indexed — are lost on the drop and re-indexed by that crawl's next run. Search is narrower
 * than usual for as long as that takes, and the seed below is what keeps it from being EMPTY in the
 * meantime.
 */
/**
 * The folder roles [MIGRATION_21_22]'s re-seed skips, as a SQL `IN` list.
 *
 * 🔴 A named constant so a test can read the SAME string the migration executes. Inlined, the only
 * way to check it would be a second hand-written copy in the test, which asserts that two copies
 * agree with each other and not that either agrees with `NOT_SEARCHED_ROLES` — a test that passes
 * while the bug it names is present.
 */
const val SEARCH_SEED_EXCLUDED_ROLES_SQL: String = "'trash', 'junk', 'spam'"

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `email_fts`")
        db.execSQL(EMAIL_FTS_CREATE_SQL_V22)
        db.execSQL(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "SELECT id, accountId, mailboxId, threadId, COALESCE(subject, ''), " +
                "TRIM(COALESCE(fromName, '') || ' ' || COALESCE(fromEmail, '')), '', " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey " +
                "FROM emails WHERE NOT EXISTS (SELECT 1 FROM mailboxes " +
                "WHERE mailboxes.id = emails.mailboxId AND mailboxes.accountId = emails.accountId " +
                "AND LOWER(TRIM(COALESCE(mailboxes.role, ''))) IN ($SEARCH_SEED_EXCLUDED_ROLES_SQL))",
        )
    }
}

/**
 * Additive 22→23: the per-folder IMAP sync point, so a refresh can ask "did anything happen here?"
 * and be told no (RFC 7162 CONDSTORE).
 *
 * Three nullable columns on `mailbox_uidvalidity`. Nullable is the whole design, not a convenience:
 * NULL means "no sync point", and every path that reads one falls back to the full folder re-read
 * the app did before this existed. So an upgraded install, a server without CONDSTORE and a folder
 * that has just been renumbered all take the same safe branch without anyone having to special-case
 * them. A non-null default would have been a fabricated watermark, and a fabricated watermark says
 * "nothing changed" about a folder nobody has looked at yet.
 *
 * 🔴 The columns are added to THIS table, beside `uidValidity`, because they are meaningless
 * without it: a renumbering resets the server's MODSEQ counter, so a MODSEQ compared across one can
 * match by coincidence and skip a folder where every message is new. Kept in the same row, the
 * REPLACE that records a new numbering clears them in the same statement, and
 * `MailboxUidValidityDao.recordSyncPoint` refuses to write against a numbering that has moved.
 *
 * A migration rather than the destructive fallback, like every other one here: the fallback takes
 * the outbox (unsent mail) with it.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MAILBOX_SYNC_POINT_SQL.forEach { db.execSQL(it) }
    }
}

/** The three columns [MIGRATION_22_23] adds; shared with the JVM test so it replays the real ones. */
val MAILBOX_SYNC_POINT_SQL: List<String> = listOf(
    "ALTER TABLE `mailbox_uidvalidity` ADD COLUMN `highestModSeq` INTEGER",
    "ALTER TABLE `mailbox_uidvalidity` ADD COLUMN `uidNext` INTEGER",
    "ALTER TABLE `mailbox_uidvalidity` ADD COLUMN `messageCount` INTEGER",
)

/**
 * Additive 23→24: the custom keywords (tags) a message carries, so a colour-coded tag survives
 * process death and can be filtered on in SQL ([EmailKeywords]).
 *
 * One nullable column, no backfill, and none is possible: the keywords aren't held anywhere else
 * on the device, they come from the server. An upgraded row therefore shows no chips until the
 * folder's next sync rewrites it, which is the same shape the v17 recipients column took.
 *
 * A migration rather than the destructive fallback, like every other one here: the fallback takes
 * the outbox (unsent mail) with it.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(EMAILS_KEYWORDS_SQL)
    }
}

/** The column [MIGRATION_23_24] adds; shared with the JVM test so it replays the real one. */
const val EMAILS_KEYWORDS_SQL: String = "ALTER TABLE `emails` ADD COLUMN `keywordsJson` TEXT"
