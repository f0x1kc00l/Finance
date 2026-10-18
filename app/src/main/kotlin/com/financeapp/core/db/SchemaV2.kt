package com.financeapp.core.db

/**
 * SQL for the version 1 → 2 schema change, kept free of Android types so it can be
 * exercised against a real SQLite engine in tooling and reviewed in one place.
 *
 * What changes:
 *  - expenses.amount REAL (major units, binary floating point) → amountMinor INTEGER (exact
 *    minor units). Conversion: ROUND(amount * 100), the same rule as Money.fromLegacyDouble.
 *  - expenses.sourceKey TEXT NULL + UNIQUE index: de-duplicates auto-detected transactions.
 *  - indexes on timestamp (every period query) and category (category filters).
 *  - budgets.monthlyLimit REAL → monthlyLimitMinor INTEGER.
 *
 * Safety:
 *  - Rows are COPIED into new tables; ids, notes, categories, methods and timestamps are
 *    preserved exactly. Nothing is filtered or deleted.
 *  - Room runs migrations inside a transaction: any failure rolls back to the intact v1 DB.
 *  - Column definitions match Room's generated CREATE statements for the v2 entities
 *    (Room validates the result and refuses to open the DB on any mismatch).
 */
object SchemaV2 {

    const val CREATE_EXPENSES =
        "CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`amountMinor` INTEGER NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, " +
            "`paymentMethod` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `sourceKey` TEXT)"

    const val CREATE_BUDGETS =
        "CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER NOT NULL, `monthlyLimitMinor` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    val INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS `index_expenses_timestamp` ON `expenses` (`timestamp`)",
        "CREATE INDEX IF NOT EXISTS `index_expenses_category` ON `expenses` (`category`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_expenses_sourceKey` ON `expenses` (`sourceKey`)"
    )

    val MIGRATION_1_2: List<String> = listOf(
        CREATE_EXPENSES.replace("`expenses`", "`expenses_v2`"),
        "INSERT INTO `expenses_v2` (`id`, `amountMinor`, `category`, `note`, `paymentMethod`, `timestamp`, `sourceKey`) " +
            "SELECT `id`, CAST(ROUND(`amount` * 100) AS INTEGER), `category`, `note`, `paymentMethod`, `timestamp`, NULL FROM `expenses`",
        // Carry over the AUTOINCREMENT high-water mark so ids of deleted rows are never reused.
        "UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'expenses'), 0)) " +
            "WHERE name = 'expenses_v2'",
        "INSERT INTO sqlite_sequence (name, seq) SELECT 'expenses_v2', seq FROM sqlite_sequence WHERE name = 'expenses' " +
            "AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'expenses_v2')",
        "DROP TABLE `expenses`",
        "ALTER TABLE `expenses_v2` RENAME TO `expenses`",
        CREATE_BUDGETS.replace("`budgets`", "`budgets_v2`"),
        "INSERT INTO `budgets_v2` (`id`, `monthlyLimitMinor`) " +
            "SELECT `id`, CAST(ROUND(`monthlyLimit` * 100) AS INTEGER) FROM `budgets`",
        "DROP TABLE `budgets`",
        "ALTER TABLE `budgets_v2` RENAME TO `budgets`"
    ) + INDEXES
}
