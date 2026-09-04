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

data class ParsedTransaction(
    val amountMinor: Long,
    val direction: TransactionDirection,
    val occurredAt: Long,
    val merchant: String,
    val merchantKey: String,
    val status: String = "POSTED",
    val usedSmsTime: Boolean = false,
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

data class DashboardData(
    val totalBalanceMinor: Long = 0,
    val monthDebitsMinor: Long = 0,
    val monthCreditsMinor: Long = 0,
    val accounts: List<AccountBalance> = emptyList(),
    val recent: List<LedgerTransaction> = emptyList(),
    val categorySpend: List<CategorySpend> = emptyList(),
    val unparsedMessageCount: Int = 0,
)
