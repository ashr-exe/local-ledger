package dev.localledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LedgerDatabase(context: Context) : SQLiteOpenHelper(context, "local-ledger.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bank_id TEXT NOT NULL UNIQUE,
                bank_name TEXT NOT NULL,
                opening_balance_minor INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                color INTEGER NOT NULL,
                monthly_budget_minor INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE merchant_rules (
                merchant_key TEXT PRIMARY KEY,
                nickname TEXT,
                category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_key TEXT NOT NULL UNIQUE,
                bank_id TEXT NOT NULL REFERENCES accounts(bank_id) ON DELETE CASCADE,
                occurred_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                direction TEXT NOT NULL CHECK(direction IN ('DEBIT','CREDIT')),
                merchant TEXT NOT NULL,
                merchant_key TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'POSTED',
                sender_header TEXT NOT NULL,
                used_sms_time INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX tx_time_idx ON transactions(occurred_at DESC)")
        db.execSQL("CREATE INDEX tx_bank_idx ON transactions(bank_id, occurred_at DESC)")
        db.execSQL("CREATE INDEX tx_merchant_idx ON transactions(merchant_key)")
        db.execSQL("""
            CREATE TABLE unparsed_messages (
                source_key TEXT PRIMARY KEY,
                bank_id TEXT NOT NULL,
                sender_header TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                reason TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        seedCategories(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun isOnboarded(): Boolean = getSetting("tracking_started") != null

    fun trackingStartedAt(): Long = getSetting("tracking_started")?.toLongOrNull() ?: Long.MAX_VALUE

    fun replaceAccounts(accounts: List<Pair<BankDefinition, Long>>, startedAt: Long) {
        writableDatabase.inTransaction {
            delete("transactions", null, null)
            delete("unparsed_messages", null, null)
            delete("accounts", null, null)
            accounts.forEach { (bank, balance) ->
                insertOrThrow("accounts", null, ContentValues().apply {
                    put("bank_id", bank.id)
                    put("bank_name", bank.name)
                    put("opening_balance_minor", balance)
                    put("created_at", startedAt)
                })
            }
            putSetting(this, "tracking_started", startedAt.toString())
        }
    }

    fun hasAccount(bankId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM accounts WHERE bank_id=? LIMIT 1", arrayOf(bankId)
    ).use { it.moveToFirst() }

    fun insertTransaction(
        sourceKey: String,
        bankId: String,
        parsed: ParsedTransaction,
        senderHeader: String,
        receivedAt: Long,
    ): Boolean = runCatching {
        writableDatabase.insertOrThrow("transactions", null, ContentValues().apply {
            put("source_key", sourceKey)
            put("bank_id", bankId)
            put("occurred_at", parsed.occurredAt)
            put("received_at", receivedAt)
            put("amount_minor", parsed.amountMinor)
            put("direction", parsed.direction.name)
            put("merchant", parsed.merchant)
            put("merchant_key", parsed.merchantKey)
            put("status", parsed.status)
            put("sender_header", senderHeader)
            put("used_sms_time", if (parsed.usedSmsTime) 1 else 0)
        })
        true
    }.getOrDefault(false)

    fun noteUnparsed(sourceKey: String, bankId: String, sender: String, receivedAt: Long) {
        writableDatabase.insertWithOnConflict(
            "unparsed_messages", null, ContentValues().apply {
                put("source_key", sourceKey)
                put("bank_id", bankId)
                put("sender_header", sender)
                put("received_at", receivedAt)
                put("reason", "No supported completed-transaction pattern")
            }, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun dashboard(now: Long = System.currentTimeMillis()): DashboardData {
        val periodStart = monthStart(now)
        val accounts = accountBalances()
        var debits = 0L
        var credits = 0L
        readableDatabase.rawQuery(
            """SELECT t.direction, COALESCE(SUM(t.amount_minor),0) total
               FROM transactions t
               LEFT JOIN merchant_rules r ON r.merchant_key=t.merchant_key
               LEFT JOIN categories c ON c.id=r.category_id
               WHERE t.occurred_at>=? AND t.status='POSTED'
                 AND COALESCE(c.name,'')<>'Transfer'
               GROUP BY t.direction""",
            arrayOf(periodStart.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.string("direction") == "DEBIT") debits = cursor.long("total")
                else credits = cursor.long("total")
            }
        }
        val unparsed = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM unparsed_messages", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return DashboardData(
            totalBalanceMinor = accounts.sumOf { it.balanceMinor },
            monthDebitsMinor = debits,
            monthCreditsMinor = credits,
            accounts = accounts,
            recent = transactions(limit = 20),
            categorySpend = categorySpend(periodStart),
            unparsedMessageCount = unparsed,
        )
    }

    fun transactions(limit: Int = 200): List<LedgerTransaction> {
        val result = mutableListOf<LedgerTransaction>()
        readableDatabase.rawQuery(
            """SELECT t.*, a.bank_name, r.nickname, c.id category_id, c.name category_name,
                      c.color category_color
               FROM transactions t
               JOIN accounts a ON a.bank_id=t.bank_id
               LEFT JOIN merchant_rules r ON r.merchant_key=t.merchant_key
               LEFT JOIN categories c ON c.id=r.category_id
               ORDER BY t.occurred_at DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.toTransaction() }
        return result
    }

    fun categories(): List<Category> {
        val result = mutableListOf<Category>()
        readableDatabase.rawQuery(
            "SELECT id,name,color,monthly_budget_minor FROM categories ORDER BY name COLLATE NOCASE", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Category(
                    cursor.long("id"), cursor.string("name"), cursor.int("color"),
                    if (cursor.isNull(cursor.getColumnIndexOrThrow("monthly_budget_minor"))) null
                    else cursor.long("monthly_budget_minor")
                )
            }
        }
        return result
    }

    fun addCategory(name: String, color: Int): Long = writableDatabase.insertOrThrow(
        "categories", null, ContentValues().apply { put("name", name.trim()); put("color", color) }
    )

    fun setCategoryBudget(categoryId: Long, budgetMinor: Long?) {
        writableDatabase.update("categories", ContentValues().apply {
            if (budgetMinor == null) putNull("monthly_budget_minor") else put("monthly_budget_minor", budgetMinor)
        }, "id=?", arrayOf(categoryId.toString()))
    }

    fun saveMerchantRule(merchantKey: String, nickname: String?, categoryId: Long?) {
        writableDatabase.insertWithOnConflict(
            "merchant_rules", null, ContentValues().apply {
                put("merchant_key", merchantKey)
                if (nickname.isNullOrBlank()) putNull("nickname") else put("nickname", nickname.trim())
                if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            }, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun accountBalances(): List<AccountBalance> {
        val result = mutableListOf<AccountBalance>()
        readableDatabase.rawQuery(
            """SELECT a.*, a.opening_balance_minor + COALESCE(SUM(
                    CASE WHEN t.direction='CREDIT' THEN t.amount_minor ELSE -t.amount_minor END
                ),0) balance
               FROM accounts a LEFT JOIN transactions t
                 ON t.bank_id=a.bank_id AND t.status='POSTED'
               GROUP BY a.id ORDER BY a.bank_name COLLATE NOCASE""", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val account = Account(
                    cursor.long("id"), cursor.string("bank_id"), cursor.string("bank_name"),
                    cursor.long("opening_balance_minor"), cursor.long("created_at")
                )
                result += AccountBalance(account, cursor.long("balance"))
            }
        }
        return result
    }

    private fun categorySpend(periodStart: Long): List<CategorySpend> {
        val categories = categories().associateBy { it.id }
        val amounts = linkedMapOf<Long, Long>()
        readableDatabase.rawQuery(
            """SELECT COALESCE(c.id,0) category_id, COALESCE(SUM(t.amount_minor),0) spent
               FROM transactions t
               LEFT JOIN merchant_rules r ON r.merchant_key=t.merchant_key
               LEFT JOIN categories c ON c.id=r.category_id
               WHERE t.direction='DEBIT' AND t.status='POSTED' AND t.occurred_at>=?
                 AND COALESCE(c.name,'')<>'Transfer'
               GROUP BY COALESCE(c.id,0) ORDER BY spent DESC""",
            arrayOf(periodStart.toString())
        ).use { cursor -> while (cursor.moveToNext()) amounts[cursor.long("category_id")] = cursor.long("spent") }
        return amounts.map { (id, amount) ->
            val category = categories[id] ?: Category(0, "Uncategorised", 0xFF89928D.toInt(), null)
            CategorySpend(category, amount)
        }
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
            "Transfer" to 0xFF6C757D.toInt(), "Other" to 0xFF89928D.toInt(),
        ).forEach { (name, color) ->
            db.insert("categories", null, ContentValues().apply { put("name", name); put("color", color) })
        }
    }

    private fun monthStart(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return LocalDate.of(date.year, date.month, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun putSetting(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("settings", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    private fun Cursor.toTransaction(): LedgerTransaction {
        val nickname = nullableString("nickname")
        val categoryName = nullableString("category_name") ?: "Uncategorised"
        return LedgerTransaction(
            id = long("id"), bankId = string("bank_id"), bankName = string("bank_name"),
            occurredAt = long("occurred_at"), receivedAt = long("received_at"),
            amountMinor = long("amount_minor"),
            direction = TransactionDirection.valueOf(string("direction")),
            merchant = string("merchant"), merchantKey = string("merchant_key"),
            displayMerchant = nickname?.takeIf { it.isNotBlank() } ?: string("merchant"),
            categoryId = if (isNull(getColumnIndexOrThrow("category_id"))) null else long("category_id"),
            categoryName = categoryName,
            categoryColor = if (isNull(getColumnIndexOrThrow("category_color"))) 0xFF89928D.toInt() else int("category_color"),
            senderHeader = string("sender_header"),
        )
    }

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullableString(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
}
