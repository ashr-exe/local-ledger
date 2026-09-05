package dev.localledger.data

data class BankDefinition(
    val id: String,
    val name: String,
    val headers: Set<String>,
)

data class Account(
    val id: Long,
    val bankId: String,
    val bankName: String,
    val openingBalanceMinor: Long,
    val createdAt: Long,
)

enum class TransactionDirection { DEBIT, CREDIT }
enum class TransactionOrigin { SMS, MANUAL }

data class ParsedTransaction(
    val amountMinor: Long,
    val direction: TransactionDirection,
    val occurredAt: Long,
    val merchant: String,
    val merchantKey: String,
    val status: String = "POSTED",
    val usedSmsTime: Boolean = false,
    val reference: String? = null,
    val parserId: String = "generic-v2",
    val confidence: Int = 70,
)

data class LedgerTransaction(
    val id: Long,
    val bankId: String,
    val bankName: String,
    val occurredAt: Long,
    val receivedAt: Long,
    val amountMinor: Long,
    val direction: TransactionDirection,
    val merchant: String,
    val merchantKey: String,
    val displayMerchant: String,
    val categoryId: Long?,
    val categoryName: String,
    val categoryColor: Int,
    val senderHeader: String,
    val tags: List<Tag> = emptyList(),
    val note: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.SMS,
    val affectsBalance: Boolean = true,
    val reference: String? = null,
    val parserId: String = "generic-v2",
    val confidence: Int = 70,
)

data class Category(
    val id: Long,
    val name: String,
    val color: Int,
    val monthlyBudgetMinor: Long?,
)

data class AccountBalance(
    val account: Account,
    val balanceMinor: Long,
)

data class CategorySpend(
    val category: Category,
    val spentMinor: Long,
)

data class Tag(
    val id: Long,
    val name: String,
    val color: Int,
)

data class MerchantSummary(
    val merchantKey: String,
    val officialName: String,
    val displayName: String,
    val categoryId: Long?,
)

enum class BudgetScopeType { CATEGORY, MERCHANT, TAG }
enum class BudgetPeriod { DAY, WEEK, MONTH, YEAR }

data class Budget(
    val id: Long,
    val scopeType: BudgetScopeType,
    val scopeKey: String,
    val label: String,
    val amountMinor: Long,
    val period: BudgetPeriod,
    val alertPercent: Int,
    val enabled: Boolean,
)

enum class BudgetState { ON_TRACK, WATCH, PROJECTED_OVER, OVER }

data class BudgetProgress(
    val budget: Budget,
    val spentMinor: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val elapsedFraction: Double,
    val projectedMinor: Long,
    val state: BudgetState,
)

data class DiagnosticEvent(
    val recordedAt: Long,
    val outcome: String,
    val senderHeader: String?,
    val bankId: String?,
    val detail: String,
)

data class DashboardFilter(
    val bankId: String? = null,
    val categoryId: Long? = null,
    val merchantKey: String? = null,
    val tagId: Long? = null,
    val direction: TransactionDirection? = null,
    val startAt: Long? = null,
    val endAt: Long? = null,
)

data class DashboardData(
    val totalBalanceMinor: Long = 0,
    val monthDebitsMinor: Long = 0,
    val monthCreditsMinor: Long = 0,
    val accounts: List<AccountBalance> = emptyList(),
    val recent: List<LedgerTransaction> = emptyList(),
    val categorySpend: List<CategorySpend> = emptyList(),
    val budgetProgress: List<BudgetProgress> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val dailySpend: List<DailyTotal> = emptyList(),
    val unparsedMessageCount: Int = 0,
)

data class DailyTotal(val epochDay: Long, val amountMinor: Long)
