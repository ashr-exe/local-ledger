package dev.localledger.ui

/**
 * Stable IDs are the dashboard extension API. Add a spec and renderer branch in MainActivity
 * to ship a custom local-only graph without touching storage or ingestion.
 */
data class DashboardModuleSpec(
    val id: String,
    val title: String,
    val defaultVisible: Boolean = true,
)

object DashboardModules {
    val all = listOf(
        DashboardModuleSpec("balance", "Balance"),
        DashboardModuleSpec("cash_flow", "Cash flow"),
        DashboardModuleSpec("trend", "Spending pace"),
        DashboardModuleSpec("categories", "Categories"),
        DashboardModuleSpec("sankey", "Money flow"),
        DashboardModuleSpec("budgets", "Budget watch"),
        DashboardModuleSpec("insights", "Insights"),
        DashboardModuleSpec("recent", "Recent activity"),
    )
    val defaultOrder: List<String> = all.map { it.id }
}
