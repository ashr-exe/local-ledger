package dev.localledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.roundToLong

class LedgerDatabase(context: Context) : SQLiteOpenHelper(context, "local-ledger.db", null, 2) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT, bank_id TEXT NOT NULL UNIQUE,
            bank_name TEXT NOT NULL, opening_balance_minor INTEGER NOT NULL, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE categories (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            color INTEGER NOT NULL, monthly_budget_minor INTEGER)""")
        db.execSQL("""CREATE TABLE merchant_rules (
            merchant_key TEXT PRIMARY KEY, nickname TEXT,
            category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL)""")
        db.execSQL("""CREATE TABLE transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, source_key TEXT NOT NULL UNIQUE,
            bank_id TEXT NOT NULL REFERENCES accounts(bank_id) ON DELETE CASCADE,
            occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
            amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
            direction TEXT NOT NULL CHECK(direction IN ('DEBIT','CREDIT')),
            merchant TEXT NOT NULL, merchant_key TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'POSTED', sender_header TEXT NOT NULL,
            used_sms_time INTEGER NOT NULL DEFAULT 0, note TEXT,
            origin TEXT NOT NULL DEFAULT 'SMS', affects_balance INTEGER NOT NULL DEFAULT 1,
            reference TEXT, parser_id TEXT NOT NULL DEFAULT 'generic-v2',
            confidence INTEGER NOT NULL DEFAULT 70)""")
        db.execSQL("CREATE INDEX tx_time_idx ON transactions(occurred_at DESC)")
        db.execSQL("CREATE INDEX tx_bank_idx ON transactions(bank_id, occurred_at DESC)")
        db.execSQL("CREATE INDEX tx_merchant_idx ON transactions(merchant_key)")
        db.execSQL("CREATE UNIQUE INDEX tx_reference_idx ON transactions(bank_id,direction,reference) " +
            "WHERE reference IS NOT NULL")
        db.execSQL("""CREATE TABLE unparsed_messages (
            source_key TEXT PRIMARY KEY, bank_id TEXT NOT NULL, sender_header TEXT NOT NULL,
            received_at INTEGER NOT NULL, reason TEXT NOT NULL)""")
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createExtendedSchema(db)
        seedCategories(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN origin TEXT NOT NULL DEFAULT 'SMS'")
            db.execSQL("ALTER TABLE transactions ADD COLUMN affects_balance INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE transactions ADD COLUMN reference TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN parser_id TEXT NOT NULL DEFAULT 'legacy-v1'")
            db.execSQL("ALTER TABLE transactions ADD COLUMN confidence INTEGER NOT NULL DEFAULT 70")
            db.execSQL("CREATE UNIQUE INDEX tx_reference_idx ON transactions(bank_id,direction,reference) " +
                "WHERE reference IS NOT NULL")
            createExtendedSchema(db)
            db.execSQL("""INSERT INTO budgets(scope_type,scope_key,label,amount_minor,period,alert_percent,enabled)
                SELECT 'CATEGORY',CAST(id AS TEXT),name,monthly_budget_minor,'MONTH',80,1
                FROM categories WHERE monthly_budget_minor IS NOT NULL""")
        }
    }

    private fun createExtendedSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS diagnostic_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, recorded_at INTEGER NOT NULL,
            outcome TEXT NOT NULL, sender_header TEXT, bank_id TEXT, detail TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS diagnostic_time_idx ON diagnostic_events(recorded_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tags (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            color INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS transaction_tags (
            transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
            tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY(transaction_id,tag_id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS merchant_tags (
            merchant_key TEXT NOT NULL, tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY(merchant_key,tag_id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS category_tags (
            category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
            tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY(category_id,tag_id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS budgets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            scope_type TEXT NOT NULL CHECK(scope_type IN ('CATEGORY','MERCHANT','TAG')),
            scope_key TEXT NOT NULL, label TEXT NOT NULL,
            amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
            period TEXT NOT NULL CHECK(period IN ('DAY','WEEK','MONTH','YEAR')),
            alert_percent INTEGER NOT NULL DEFAULT 80 CHECK(alert_percent BETWEEN 1 AND 100),
            enabled INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS budget_alerts (
            budget_id INTEGER NOT NULL REFERENCES budgets(id) ON DELETE CASCADE,
            cycle_key TEXT NOT NULL, created_at INTEGER NOT NULL,
            PRIMARY KEY(budget_id,cycle_key))""")
    }

    fun isOnboarded(): Boolean = getSetting("tracking_started") != null
    fun trackingStartedAt(): Long = getSetting("tracking_started")?.toLongOrNull() ?: Long.MAX_VALUE
    fun setting(key: String): String? = getSetting(key)
    fun saveSetting(key: String, value: String) = putSetting(writableDatabase, key, value)

    fun replaceAccounts(accounts: List<Pair<BankDefinition, Long>>, startedAt: Long) {
        writableDatabase.inTransaction {
            delete("transactions", null, null)
            delete("unparsed_messages", null, null)
            delete("accounts", null, null)
            accounts.forEach { (bank, balance) ->
                insertOrThrow("accounts", null, ContentValues().apply {
                    put("bank_id", bank.id); put("bank_name", bank.name)
                    put("opening_balance_minor", balance); put("created_at", startedAt)
                })
            }
            putSetting(this, "tracking_started", startedAt.toString())
        }
    }

    fun hasAccount(bankId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM accounts WHERE bank_id=? LIMIT 1", arrayOf(bankId)
    ).use { it.moveToFirst() }

    fun accounts(): List<Account> {
        val result = mutableListOf<Account>()
        readableDatabase.rawQuery("SELECT * FROM accounts ORDER BY bank_name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += Account(
                c.long("id"), c.string("bank_id"), c.string("bank_name"),
                c.long("opening_balance_minor"), c.long("created_at"))
        }
        return result
    }

    fun insertTransaction(
        sourceKey: String, bankId: String, parsed: ParsedTransaction,
        senderHeader: String, receivedAt: Long,
    ): Boolean = insertTransactionInternal(
        sourceKey, bankId, parsed, senderHeader, receivedAt, null, TransactionOrigin.SMS, true
    ) != null

    fun addManualTransaction(
        bankId: String, parsed: ParsedTransaction, note: String?,
        affectsBalance: Boolean, tagIds: Set<Long> = emptySet(),
    ): Long? {
        val id = insertTransactionInternal(
            "manual:" + UUID.randomUUID(), bankId, parsed, "MANUAL", System.currentTimeMillis(),
            note?.trim()?.takeIf { it.isNotEmpty() }, TransactionOrigin.MANUAL, affectsBalance
        ) ?: return null
        setRelationIds("transaction_tags", "transaction_id", id.toString(), tagIds)
        return id
    }

    private fun insertTransactionInternal(
        sourceKey: String, bankId: String, parsed: ParsedTransaction,
        senderHeader: String, receivedAt: Long, note: String?,
        origin: TransactionOrigin, affectsBalance: Boolean,
    ): Long? = runCatching {
        writableDatabase.insertOrThrow("transactions", null, ContentValues().apply {
            put("source_key", sourceKey); put("bank_id", bankId)
            put("occurred_at", parsed.occurredAt); put("received_at", receivedAt)
            put("amount_minor", parsed.amountMinor); put("direction", parsed.direction.name)
            put("merchant", parsed.merchant); put("merchant_key", parsed.merchantKey)
            put("status", parsed.status); put("sender_header", senderHeader)
            put("used_sms_time", if (parsed.usedSmsTime) 1 else 0)
            if (note == null) putNull("note") else put("note", note)
            put("origin", origin.name); put("affects_balance", if (affectsBalance) 1 else 0)
            if (parsed.reference == null) putNull("reference") else put("reference", parsed.reference)
            put("parser_id", parsed.parserId); put("confidence", parsed.confidence)
        })
    }.getOrNull()?.takeIf { it >= 0 }

    fun noteUnparsed(sourceKey: String, bankId: String, sender: String, receivedAt: Long) {
        writableDatabase.insertWithOnConflict(
            "unparsed_messages", null, ContentValues().apply {
                put("source_key", sourceKey); put("bank_id", bankId)
                put("sender_header", sender); put("received_at", receivedAt)
                put("reason", "No supported completed-transaction pattern")
            }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun recordDiagnostic(outcome: String, sender: String?, bankId: String?, detail: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.insert("diagnostic_events", null, ContentValues().apply {
            put("recorded_at", now); put("outcome", outcome.take(40))
            if (sender == null) putNull("sender_header") else put("sender_header", sender.take(24))
            if (bankId == null) putNull("bank_id") else put("bank_id", bankId.take(64))
            put("detail", detail.take(240))
        })
        db.delete("diagnostic_events", "recorded_at<?", arrayOf((now - DIAGNOSTIC_RETENTION_MS).toString()))
        db.execSQL("DELETE FROM diagnostic_events WHERE id NOT IN " +
            "(SELECT id FROM diagnostic_events ORDER BY recorded_at DESC LIMIT " + DIAGNOSTIC_MAX_ROWS + ")")
    }

    fun diagnostics(limit: Int = DIAGNOSTIC_MAX_ROWS): List<DiagnosticEvent> {
        val result = mutableListOf<DiagnosticEvent>()
        readableDatabase.rawQuery(
            "SELECT recorded_at,outcome,sender_header,bank_id,detail FROM diagnostic_events ORDER BY recorded_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) result += DiagnosticEvent(
                c.long("recorded_at"), c.string("outcome"), c.nullableString("sender_header"),
                c.nullableString("bank_id"), c.string("detail"))
        }
        return result
    }

    fun dashboard(filter: DashboardFilter = DashboardFilter(), now: Long = System.currentTimeMillis()): DashboardData {
        val periodStart = filter.startAt ?: monthStart(now)
        val periodEnd = filter.endAt ?: Long.MAX_VALUE
        val allTransactions = transactions(Int.MAX_VALUE)
        val month = allTransactions.filter {
            it.occurredAt in periodStart until periodEnd && it.matches(filter)
        }
        val accounts = accountBalances().filter { filter.bankId == null || it.account.bankId == filter.bankId }
        val counted = month.filter { it.categoryName != "Transfer" }
        val categoryMap = categories().associateBy { it.id }
        val categorySpend = counted.asSequence().filter { it.direction == TransactionDirection.DEBIT }
            .groupBy { it.categoryId ?: 0L }
            .map { (id, txs) ->
                CategorySpend(
                    categoryMap[id] ?: Category(0, "Uncategorised", 0xFF89928D.toInt(), null),
                    txs.sumOf { it.amountMinor })
            }.sortedByDescending { it.spentMinor }
        val unparsed = readableDatabase.rawQuery("SELECT COUNT(*) FROM unparsed_messages", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val zone = ZoneId.systemDefault()
        val daily = counted.asSequence().filter { it.direction == TransactionDirection.DEBIT }
            .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate().toEpochDay() }
            .map { DailyTotal(it.key, it.value.sumOf(LedgerTransaction::amountMinor)) }
            .sortedBy(DailyTotal::epochDay)
        return DashboardData(
            totalBalanceMinor = accounts.sumOf { it.balanceMinor },
            monthDebitsMinor = counted.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountMinor },
            monthCreditsMinor = counted.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountMinor },
            accounts = accounts, recent = month.take(20), categorySpend = categorySpend,
            budgetProgress = budgetProgress(now, allTransactions), tags = tags(), dailySpend = daily,
            unparsedMessageCount = unparsed)
    }

    fun transactions(limit: Int = 2_000): List<LedgerTransaction> {
        val base = mutableListOf<LedgerTransaction>()
        readableDatabase.rawQuery(
            """SELECT t.*,a.bank_name,r.nickname,c.id category_id,c.name category_name,c.color category_color
               FROM transactions t JOIN accounts a ON a.bank_id=t.bank_id
               LEFT JOIN merchant_rules r ON r.merchant_key=t.merchant_key
               LEFT JOIN categories c ON c.id=r.category_id
               ORDER BY t.occurred_at DESC LIMIT ?""", arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) base += c.toTransactionBase() }
        if (base.isEmpty()) return base
        val tags = tags().associateBy { it.id }
        val effective = effectiveTagsForTransactions(base.map { it.id }.toSet())
        return base.map { tx -> tx.copy(tags = effective[tx.id].orEmpty().mapNotNull(tags::get)) }
    }

    fun categories(): List<Category> {
        val result = mutableListOf<Category>()
        readableDatabase.rawQuery(
            "SELECT id,name,color,monthly_budget_minor FROM categories ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) result += Category(
                c.long("id"), c.string("name"), c.int("color"),
                if (c.isNull(c.getColumnIndexOrThrow("monthly_budget_minor"))) null
                else c.long("monthly_budget_minor"))
        }
        return result
    }

    fun addCategory(name: String, color: Int): Long = writableDatabase.insertOrThrow(
        "categories", null, ContentValues().apply { put("name", name.trim()); put("color", color) })

    /** Compatibility bridge for v0.1 category budgets; the new UI writes generic budgets directly. */
    fun setCategoryBudget(categoryId: Long, budgetMinor: Long?) {
        writableDatabase.inTransaction {
            update("categories", ContentValues().apply {
                if (budgetMinor == null) putNull("monthly_budget_minor")
                else put("monthly_budget_minor", budgetMinor)
            }, "id=?", arrayOf(categoryId.toString()))
            delete("budgets", "scope_type='CATEGORY' AND scope_key=?",
                arrayOf(categoryId.toString()))
            if (budgetMinor != null) {
                val categoryName = rawQuery("SELECT name FROM categories WHERE id=?",
                    arrayOf(categoryId.toString())).use { if (it.moveToFirst()) it.getString(0) else "Category" }
                insertOrThrow("budgets", null, ContentValues().apply {
                    put("scope_type", BudgetScopeType.CATEGORY.name)
                    put("scope_key", categoryId.toString()); put("label", categoryName)
                    put("amount_minor", budgetMinor); put("period", BudgetPeriod.MONTH.name)
                    put("alert_percent", 80); put("enabled", 1)
                })
            }
        }
    }

    fun tags(): List<Tag> {
        val result = mutableListOf<Tag>()
        readableDatabase.rawQuery("SELECT id,name,color FROM tags ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += Tag(c.long("id"), c.string("name"), c.int("color"))
        }
        return result
    }

    fun addTag(name: String, color: Int): Long = writableDatabase.insertOrThrow(
        "tags", null, ContentValues().apply { put("name", name.trim()); put("color", color) })

    fun merchants(): List<MerchantSummary> {
        val result = linkedMapOf<String, MerchantSummary>()
        readableDatabase.rawQuery(
            """SELECT t.merchant_key,MAX(t.merchant) official_name,r.nickname,r.category_id
               FROM transactions t LEFT JOIN merchant_rules r ON r.merchant_key=t.merchant_key
               GROUP BY t.merchant_key ORDER BY COALESCE(r.nickname,MAX(t.merchant)) COLLATE NOCASE""", null
        ).use { c ->
            while (c.moveToNext()) {
                val official = c.string("official_name")
                val item = MerchantSummary(c.string("merchant_key"), official,
                    c.nullableString("nickname")?.takeIf { it.isNotBlank() } ?: official,
                    c.nullableLong("category_id"))
                result[item.merchantKey] = item
            }
        }
        readableDatabase.rawQuery(
            """SELECT r.merchant_key,r.nickname,r.category_id FROM merchant_rules r
               WHERE NOT EXISTS(SELECT 1 FROM transactions t WHERE t.merchant_key=r.merchant_key)""", null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.string("merchant_key")
                val display = c.nullableString("nickname")?.takeIf { it.isNotBlank() } ?: key
                result[key] = MerchantSummary(key, display, display, c.nullableLong("category_id"))
            }
        }
        return result.values.sortedBy { it.displayName.lowercase() }
    }

    fun createMerchant(name: String, nickname: String?, categoryId: Long?): String {
        val key = dev.localledger.sms.TransactionParser.normalizeMerchant(name)
        saveMerchantRule(key, nickname ?: name, categoryId)
        return key
    }

    fun saveMerchantRule(merchantKey: String, nickname: String?, categoryId: Long?) {
        writableDatabase.insertWithOnConflict(
            "merchant_rules", null, ContentValues().apply {
                put("merchant_key", merchantKey)
                if (nickname.isNullOrBlank()) putNull("nickname") else put("nickname", nickname.trim())
                if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun effectiveTagIds(transactionId: Long): Set<Long> =
        effectiveTagsForTransactions(setOf(transactionId))[transactionId].orEmpty()

    private fun effectiveTagsForTransactions(ids: Set<Long>): Map<Long, Set<Long>> {
        if (ids.isEmpty()) return emptyMap()
        val result = linkedMapOf<Long, MutableSet<Long>>()
        ids.chunked(300).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map(Long::toString).toTypedArray()
            readableDatabase.rawQuery(
                """SELECT transaction_id,tag_id FROM transaction_tags WHERE transaction_id IN ($placeholders)
                   UNION SELECT t.id,mt.tag_id FROM transactions t JOIN merchant_tags mt ON mt.merchant_key=t.merchant_key
                        WHERE t.id IN ($placeholders)
                   UNION SELECT t.id,ct.tag_id FROM transactions t JOIN merchant_rules mr ON mr.merchant_key=t.merchant_key
                        JOIN category_tags ct ON ct.category_id=mr.category_id WHERE t.id IN ($placeholders)""",
                args + args + args
            ).use { c ->
                while (c.moveToNext()) result.getOrPut(c.getLong(0)) { linkedSetOf() } += c.getLong(1)
            }
        }
        return result
    }

    fun directTransactionTagIds(transactionId: Long) =
        relationIds("transaction_tags", "transaction_id", transactionId.toString())
    fun merchantTagIds(merchantKey: String) = relationIds("merchant_tags", "merchant_key", merchantKey)
    fun categoryTagIds(categoryId: Long) = relationIds("category_tags", "category_id", categoryId.toString())
    fun setTransactionTags(transactionId: Long, ids: Set<Long>) =
        setRelationIds("transaction_tags", "transaction_id", transactionId.toString(), ids)
    fun setMerchantTags(merchantKey: String, ids: Set<Long>) =
        setRelationIds("merchant_tags", "merchant_key", merchantKey, ids)
    fun setCategoryTags(categoryId: Long, ids: Set<Long>) =
        setRelationIds("category_tags", "category_id", categoryId.toString(), ids)

    private fun relationIds(table: String, keyColumn: String, key: String): Set<Long> {
        val result = linkedSetOf<Long>()
        readableDatabase.rawQuery("SELECT tag_id FROM " + table + " WHERE " + keyColumn + "=?", arrayOf(key))
            .use { c -> while (c.moveToNext()) result += c.getLong(0) }
        return result
    }

    private fun setRelationIds(table: String, keyColumn: String, key: String, ids: Set<Long>) {
        writableDatabase.inTransaction {
            delete(table, keyColumn + "=?", arrayOf(key))
            ids.forEach { id ->
                insertOrThrow(table, null, ContentValues().apply {
                    put(keyColumn, key); put("tag_id", id)
                })
            }
        }
    }

    fun budgets(): List<Budget> {
        val result = mutableListOf<Budget>()
        readableDatabase.rawQuery("SELECT * FROM budgets ORDER BY label COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += c.toBudget()
        }
        return result
    }

    fun saveBudget(
        id: Long?, scopeType: BudgetScopeType, scopeKey: String, label: String,
        amountMinor: Long, period: BudgetPeriod, alertPercent: Int,
    ): Long {
        val values = ContentValues().apply {
            put("scope_type", scopeType.name); put("scope_key", scopeKey)
            put("label", label.trim()); put("amount_minor", amountMinor)
            put("period", period.name); put("alert_percent", alertPercent.coerceIn(1, 100))
            put("enabled", 1)
        }
        return if (id == null) writableDatabase.insertOrThrow("budgets", null, values)
        else {
            writableDatabase.update("budgets", values, "id=?", arrayOf(id.toString()))
            id
        }
    }

    fun deleteBudget(id: Long) {
        writableDatabase.delete("budgets", "id=?", arrayOf(id.toString()))
    }

    fun budgetProgress(now: Long = System.currentTimeMillis()): List<BudgetProgress> {
        return budgetProgress(now, transactions(Int.MAX_VALUE))
    }

    private fun budgetProgress(
        now: Long,
        transactions: List<LedgerTransaction>,
    ): List<BudgetProgress> {
        return budgets().filter { it.enabled }.map { budget ->
            val (start, end) = periodBounds(budget.period, now)
            val spent = transactions.asSequence()
                .filter { it.direction == TransactionDirection.DEBIT && it.occurredAt in start until end }
                .filter { tx ->
                    when (budget.scopeType) {
                        BudgetScopeType.CATEGORY -> tx.categoryId?.toString() == budget.scopeKey
                        BudgetScopeType.MERCHANT -> tx.merchantKey == budget.scopeKey
                        BudgetScopeType.TAG -> tx.tags.any { it.id.toString() == budget.scopeKey }
                    }
                }.sumOf { it.amountMinor }
            val elapsed = ((now - start).toDouble() / (end - start).coerceAtLeast(1))
                .coerceIn(0.01, 1.0)
            val projected = (spent / elapsed).roundToLong()
            val state = when {
                spent >= budget.amountMinor -> BudgetState.OVER
                elapsed >= 0.20 && spent >= budget.amountMinor / 5 && projected > budget.amountMinor ->
                    BudgetState.PROJECTED_OVER
                spent.toDouble() / budget.amountMinor >= budget.alertPercent / 100.0 -> BudgetState.WATCH
                else -> BudgetState.ON_TRACK
            }
            BudgetProgress(budget, spent, start, end, elapsed, projected, state)
        }
    }

    fun claimBudgetAlerts(now: Long = System.currentTimeMillis()): List<BudgetProgress> {
        val pending = mutableListOf<BudgetProgress>()
        budgetProgress(now).filter {
            it.spentMinor.toDouble() / it.budget.amountMinor >= it.budget.alertPercent / 100.0
        }.forEach { progress ->
            val inserted = writableDatabase.insertWithOnConflict(
                "budget_alerts", null, ContentValues().apply {
                    put("budget_id", progress.budget.id)
                    put("cycle_key", progress.periodStart.toString())
                    put("created_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted >= 0) pending += progress
        }
        return pending
    }

    private fun accountBalances(): List<AccountBalance> {
        val result = mutableListOf<AccountBalance>()
        readableDatabase.rawQuery(
            """SELECT a.*,a.opening_balance_minor+COALESCE(SUM(
                    CASE WHEN t.affects_balance=0 THEN 0
                         WHEN t.direction='CREDIT' THEN t.amount_minor ELSE -t.amount_minor END),0) balance
               FROM accounts a LEFT JOIN transactions t ON t.bank_id=a.bank_id AND t.status='POSTED'
               GROUP BY a.id ORDER BY a.bank_name COLLATE NOCASE""", null
        ).use { c ->
            while (c.moveToNext()) {
                val account = Account(c.long("id"), c.string("bank_id"), c.string("bank_name"),
                    c.long("opening_balance_minor"), c.long("created_at"))
                result += AccountBalance(account, c.long("balance"))
            }
        }
        return result
    }

    private fun periodBounds(period: BudgetPeriod, now: Long): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDate = when (period) {
            BudgetPeriod.DAY -> current
            BudgetPeriod.WEEK -> current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            BudgetPeriod.MONTH -> current.withDayOfMonth(1)
            BudgetPeriod.YEAR -> current.withDayOfYear(1)
        }
        val endDate = when (period) {
            BudgetPeriod.DAY -> startDate.plusDays(1)
            BudgetPeriod.WEEK -> startDate.plusWeeks(1)
            BudgetPeriod.MONTH -> startDate.plusMonths(1)
            BudgetPeriod.YEAR -> startDate.plusYears(1)
        }
        return startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
            endDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun monthStart(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return LocalDate.of(date.year, date.month, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun getSetting(key: String): String? = readableDatabase.rawQuery(
        "SELECT value FROM settings WHERE key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun seedCategories(db: SQLiteDatabase) {
        listOf(
            "Food" to 0xFFE76F51.toInt(), "Rent" to 0xFF457B9D.toInt(),
            "Shopping" to 0xFFF4A261.toInt(), "Transport" to 0xFF2A9D8F.toInt(),
            "Bills" to 0xFF8D6E63.toInt(), "Health" to 0xFFE63946.toInt(),
            "Entertainment" to 0xFF7B61A8.toInt(), "Salary" to 0xFF2D6A4F.toInt(),
            "Transfer" to 0xFF6C757D.toInt(), "Other" to 0xFF89928D.toInt()
        ).forEach { (name, color) ->
            db.insert("categories", null, ContentValues().apply { put("name", name); put("color", color) })
        }
    }

    private fun putSetting(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("settings", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun LedgerTransaction.matches(filter: DashboardFilter): Boolean =
        (filter.bankId == null || bankId == filter.bankId) &&
            (filter.categoryId == null || categoryId == filter.categoryId) &&
            (filter.merchantKey == null || merchantKey == filter.merchantKey) &&
            (filter.tagId == null || tags.any { it.id == filter.tagId }) &&
            (filter.direction == null || direction == filter.direction) &&
            (filter.startAt == null || occurredAt >= filter.startAt) &&
            (filter.endAt == null || occurredAt < filter.endAt)

    private fun Cursor.toTransactionBase(): LedgerTransaction {
        val official = string("merchant")
        return LedgerTransaction(
            id = long("id"), bankId = string("bank_id"), bankName = string("bank_name"),
            occurredAt = long("occurred_at"), receivedAt = long("received_at"),
            amountMinor = long("amount_minor"),
            direction = TransactionDirection.valueOf(string("direction")),
            merchant = official, merchantKey = string("merchant_key"),
            displayMerchant = nullableString("nickname")?.takeIf { it.isNotBlank() } ?: official,
            categoryId = nullableLong("category_id"),
            categoryName = nullableString("category_name") ?: "Uncategorised",
            categoryColor = nullableInt("category_color") ?: 0xFF89928D.toInt(),
            senderHeader = string("sender_header"), note = nullableString("note"),
            origin = runCatching { TransactionOrigin.valueOf(string("origin")) }
                .getOrDefault(TransactionOrigin.SMS),
            affectsBalance = int("affects_balance") != 0,
            reference = nullableString("reference"),
            parserId = string("parser_id"), confidence = int("confidence"))
    }

    private fun Cursor.toBudget() = Budget(
        long("id"), BudgetScopeType.valueOf(string("scope_type")), string("scope_key"),
        string("label"), long("amount_minor"), BudgetPeriod.valueOf(string("period")),
        int("alert_percent"), int("enabled") != 0)

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullableString(name: String): String? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.nullableLong(name: String): Long? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.nullableInt(name: String): Int? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getInt(it) }

    companion object {
        private const val DIAGNOSTIC_MAX_ROWS = 120
        private const val DIAGNOSTIC_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
