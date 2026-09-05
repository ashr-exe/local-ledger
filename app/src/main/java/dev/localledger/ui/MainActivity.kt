package dev.localledger.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.localledger.LocalLedgerApp
import dev.localledger.R
import dev.localledger.data.AccountBalance
import dev.localledger.data.BankDefinition
import dev.localledger.data.Category
import dev.localledger.data.CategorySpend
import dev.localledger.data.DashboardFilter
import dev.localledger.data.DashboardData
import dev.localledger.data.Budget
import dev.localledger.data.BudgetPeriod
import dev.localledger.data.BudgetProgress
import dev.localledger.data.BudgetScopeType
import dev.localledger.data.BudgetState
import dev.localledger.data.Tag
import dev.localledger.data.Account
import dev.localledger.data.MerchantSummary
import dev.localledger.data.ParsedTransaction
import dev.localledger.data.ReportExporter
import dev.localledger.data.LedgerRepository
import dev.localledger.data.LedgerTransaction
import dev.localledger.data.TransactionDirection
import dev.localledger.data.TransactionOrigin
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var repository: LedgerRepository
    private lateinit var root: FrameLayout
    private var screen = Screen.DASHBOARD
    private var currentFilter = DashboardFilter()
    private var activitySort = ActivitySort.NEWEST
    private var pendingExport: ExportType? = null
    private var exportTransactions: List<LedgerTransaction> = emptyList()
    private val refreshListener: () -> Unit = { if (::root.isInitialized) renderCurrent() }
    private val preferences by lazy { getSharedPreferences("dashboard", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as LocalLedgerApp).repository
        root = FrameLayout(this)
        root.setBackgroundColor(paper)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        repository.addListener(refreshListener)
        if (repository.database.isOnboarded()) {
            renderShell()
            recoverMissedMessages()
        } else {
            renderOnboarding()
        }
    }

    override fun onDestroy() {
        repository.removeListener(refreshListener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_REQUEST) {
            renderShell()
            if (hasSmsPermissions()) repository.scanInbox()
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            repository.backgroundExecutor.execute {
                dev.localledger.data.BudgetNotifier.notify(this, repository.database)
            }
        }
    }

    @Deprecated("Activity result retained to avoid adding an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK || data?.data == null) return
        val type = pendingExport ?: return
        runCatching {
            if (type == ExportType.CSV) ReportExporter.writeCsv(contentResolver, data.data!!, exportTransactions)
            else ReportExporter.writePdf(contentResolver, data.data!!, exportTransactions)
        }.onSuccess {
            Toast.makeText(this, "Report saved locally", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not save report: " + it.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
        pendingExport = null
        exportTransactions = emptyList()
    }

    private fun renderOnboarding() {
        root.removeAllViews()
        val selected = mutableListOf<Pair<BankDefinition, Long>>()
        val content = vertical(padding = 24)
        content.addView(label("LOCAL LEDGER", 13, fern, bold = true).apply { letterSpacing = .12f })
        content.addView(label("Your money,\nkept on your phone.", 34, ink, bold = true), matchWrap(top = 14))
        content.addView(label("Choose each bank and enter its balance right now. Tracking starts when you finish.", 16, muted), matchWrap(top = 12))
        content.addView(privacyPill(), matchWrap(top = 18))

        val bankInput = AutoCompleteTextView(this).apply {
            hint = "Search bank"
            threshold = 1
            setSingleLine()
            setTextColor(ink)
            setHintTextColor(muted)
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, repository.registry.banks.map { it.name }))
            background = fieldBackground()
            setPadding(dp(14), 0, dp(14), 0)
        }
        val balanceInput = EditText(this).apply {
            hint = "Current balance (₹)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            setTextColor(ink)
            setHintTextColor(muted)
            background = fieldBackground()
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(bankInput, matchHeight(54, top = 26))
        content.addView(balanceInput, matchHeight(54, top = 10))

        val accountsBox = vertical()
        content.addView(accountsBox, matchWrap(top = 14))

        fun refreshAccounts() {
            accountsBox.removeAllViews()
            selected.forEach { (bank, balance) ->
                accountsBox.addView(accountSetupRow(bank.name, balance) {
                    selected.removeAll { it.first.id == bank.id }
                    refreshAccounts()
                }, matchWrap(bottom = 8))
            }
        }

        content.addView(primaryButton("Add account") {
            val bank = repository.registry.banks.firstOrNull { it.name.equals(bankInput.text.toString().trim(), true) }
            val balance = parseMoney(balanceInput.text.toString())
            when {
                bank == null -> bankInput.error = "Select a bank from the list"
                balance == null -> balanceInput.error = "Enter a valid balance"
                selected.any { it.first.id == bank.id } -> bankInput.error = "Only one account per bank for now"
                else -> {
                    selected += bank to balance
                    bankInput.setText("")
                    balanceInput.setText("")
                    refreshAccounts()
                }
            }
        }, matchHeight(52, top = 4))

        content.addView(secondaryButton("Start tracking") {
            if (selected.isEmpty()) {
                Toast.makeText(this, "Add at least one bank account", Toast.LENGTH_SHORT).show()
                return@secondaryButton
            }
            val start = System.currentTimeMillis()
            repository.mutate({ replaceAccounts(selected.toList(), start) }) {
                requestSmsPermissions()
            }
        }, matchHeight(52, top = 10, bottom = 22))

        root.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun renderShell() {
        root.removeAllViews()
        val shell = vertical()
        val content = FrameLayout(this).apply { id = CONTENT_ID }
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(bottomNavigation(), LinearLayout.LayoutParams(-1, dp(68)))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        renderCurrent()
    }

    private fun renderCurrent() {
        if (!repository.database.isOnboarded()) return
        val target = root.findViewById<FrameLayout>(CONTENT_ID) ?: return
        target.removeAllViews()
        target.addView(centeredLoading())
        when (screen) {
            Screen.DASHBOARD -> repository.query({ dashboard(currentFilter) }) {
                if (screen == Screen.DASHBOARD) showDashboard(target, it)
            }
            Screen.ACTIVITY -> repository.query({ ScreenData(transactions()) }) {
                if (screen == Screen.ACTIVITY) showActivity(target, it)
            }
            Screen.BUDGETS -> repository.query({ ScreenData(budgets = budgetProgress()) }) {
                if (screen == Screen.BUDGETS) showBudgets(target, it)
            }
            Screen.SETTINGS -> repository.query({ ScreenData(transactions()) }) {
                if (screen == Screen.SETTINGS) showSettings(target, it)
            }
        }
    }

    private fun showDashboard(target: FrameLayout, source: DashboardData) {
        target.removeAllViews()
        val data = when (privacyMode()) {
            PrivacyMode.DEMO -> demoDashboardData()
            PrivacyMode.HIDE -> source.copy(accounts = privateAccountRows(source.accounts))
            PrivacyMode.OFF -> source
        }
        val content = vertical(padding = 20)
        val displayCategories = privateCategorySpend(data.categorySpend)
        val displayBalance = if (privacyMode() == PrivacyMode.OFF) data.totalBalanceMinor
            else data.accounts.mapIndexed { index, item ->
                privateValue(item.balanceMinor, 100 + index)
            }.sum()
        content.addView(dashboardHeader())

        if (!hasSmsPermissions()) content.addView(permissionCard(), matchWrap(top = 14))
        if (!isDefaultFilter()) {
            content.addView(horizontalCard(0xFFFFF1D6.toInt()).apply {
                addView(label("FILTERED  " + filterSummary(), 12, 0xFF7A5414.toInt(), bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(textButton("Clear") { currentFilter = DashboardFilter(); renderCurrent() })
            }, matchWrap(top = 12))
        }

        dashboardModuleIds().forEach { module ->
            when (module) {
                "balance" -> content.addView(
                    metricCard(
                        "TOTAL BALANCE", if (privacyMode() == PrivacyMode.HIDE) "₹••••" else money(displayBalance),
                        when (privacyMode()) {
                            PrivacyMode.OFF -> "Across " + data.accounts.size + " account" +
                                (if (data.accounts.size == 1) "" else "s")
                            PrivacyMode.HIDE -> "Across hidden accounts"
                            PrivacyMode.DEMO -> "Across demo accounts"
                        },
                        fernDark
                    ).apply { setOnClickListener { accountSplitDialog(data.accounts) } },
                    matchWrap(top = 16))
                "cash_flow" -> {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(smallMetric("Spent", privateMoney(data.monthDebitsMinor, 2), coral),
                        LinearLayout.LayoutParams(0, dp(108), 1f).apply { marginEnd = dp(6) })
                    row.addView(smallMetric("Inflow", privateMoney(data.monthCreditsMinor, 3), fern),
                        LinearLayout.LayoutParams(0, dp(108), 1f).apply { marginStart = dp(6) })
                    content.addView(row, matchWrap(top = 12))
                }
                "trend" -> {
                    content.addView(moduleTitle("Spending pace", "Daily outflow"), matchWrap(top = 24))
                    content.addView(SpendTrendView(this).apply {
                        submit(privateDailySpend(data.dailySpend))
                        background = rounded(surface, 18f, line)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                    }, matchHeight(120, top = 9))
                }
                "categories" -> {
                    content.addView(moduleTitle("Spending by category", "Tap Activity to edit rules"), matchWrap(top = 24))
                    val chartRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                    chartRow.addView(CategoryDonutView(this).apply { submit(displayCategories) },
                        LinearLayout.LayoutParams(dp(132), dp(132)))
                    val legend = vertical(padding = 4)
                    displayCategories.take(5).forEach { legend.addView(categoryLegend(it)) }
                    if (data.categorySpend.isEmpty()) legend.addView(label("Transactions will appear here.", 14, muted))
                    chartRow.addView(legend, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                    content.addView(chartRow, matchWrap(top = 10))
                }
                "sankey" -> {
                    content.addView(moduleTitle("Money flow", "Income into this period's spending"), matchWrap(top = 24))
                    content.addView(CashFlowSankeyView(this).apply {
                        submit(privateValue(data.monthCreditsMinor, 20), displayCategories,
                            privacyMode() != PrivacyMode.OFF)
                        background = rounded(surface, 18f, line)
                    }, matchHeight(230, top = 9))
                }
                "budgets" -> {
                    content.addView(moduleTitle("Budget watch", "Pace-aware and quiet"), matchWrap(top = 24))
                    if (data.budgetProgress.isEmpty()) {
                        content.addView(emptyCard("No limits yet. Add one from Budgets."), matchWrap(top = 9))
                    } else data.budgetProgress.take(3).forEach {
                        content.addView(budgetProgressRow(it) { screen = Screen.BUDGETS; renderShell() }, matchWrap(top = 8))
                    }
                }
                "insights" -> {
                    content.addView(moduleTitle("Insights", "A quick read on this period"), matchWrap(top = 24))
                    content.addView(insightCard(data), matchWrap(top = 9))
                }
                "recent" -> {
                    content.addView(moduleTitle("Recent activity", "Newest first"), matchWrap(top = 24))
                    if (data.recent.isEmpty()) {
                        content.addView(emptyCard("Waiting for your first verified bank transaction SMS."), matchWrap(top = 10))
                    } else data.recent.take(6).forEach { tx ->
                        content.addView(transactionRow(tx) { editTransaction(tx) }, matchWrap(top = 8))
                    }
                }
            }
        }
        if (data.unparsedMessageCount > 0) {
            content.addView(label(data.unparsedMessageCount.toString() + " possible transaction alert" +
                (if (data.unparsedMessageCount == 1) "" else "s") +
                    " could not be parsed. Check Settings → Diagnostics.", 12, muted),
                matchWrap(top = 16, bottom = 20))
        } else content.addView(Space(this), matchHeight(20))
        target.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showActivity(target: FrameLayout, data: ScreenData) {
        target.removeAllViews()
        val outer = vertical(padding = 20)
        outer.addView(pageHeader("Activity", "Filter, sort, tag or edit any entry"))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(compactButton("Filter") { openFilterDialog() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        actions.addView(compactButton("Sort") { sortDialog() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        actions.addView(primaryButton("+ Add") { openManualTransaction() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        outer.addView(actions, matchWrap(top = 12))
        if (!isDefaultFilter()) outer.addView(label("Showing: " + filterSummary(), 12, fernDark), matchWrap(top = 9))
        val sourceTransactions = if (privacyMode() == PrivacyMode.DEMO)
            demoTransactions() else data.transactions
        val transactions = sortedTransactions(sourceTransactions.filter(::matchesCurrentFilter))
        if (transactions.isEmpty()) {
            outer.addView(emptyCard("No digital transactions captured yet."), matchWrap(top = 18))
            target.addView(outer)
            return
        }
        val list = ListView(this).apply {
            divider = null
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(12))
            adapter = TransactionAdapter(transactions)
            setOnItemClickListener { _, _, position, _ -> editTransaction(transactions[position]) }
        }
        outer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        target.addView(outer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showBudgets(target: FrameLayout, data: ScreenData) {
        target.removeAllViews()
        val content = vertical(padding = 20)
        content.addView(pageHeader("Budgets", "Flexible limits with pace-aware guidance"))
        content.addView(guideCard(), matchWrap(top = 14))
        val budgets = if (privacyMode() == PrivacyMode.DEMO) demoBudgetProgress() else data.budgets
        if (budgets.isEmpty()) {
            content.addView(emptyCard("Create a daily, weekly, monthly or yearly limit for a category, merchant or tag."),
                matchWrap(top = 14))
        } else {
            budgets.forEach { progress ->
                content.addView(budgetProgressRow(progress) { openBudget(progress.budget) }, matchWrap(top = 10))
            }
        }
        content.addView(primaryButton("Add budget") { openBudget(null) }, matchHeight(50, top = 14))
        content.addView(secondaryButton("Manage categories & tags") { openLabels() },
            matchHeight(50, top = 10, bottom = 24))
        target.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showSettings(target: FrameLayout, data: ScreenData) {
        target.removeAllViews()
        val content = vertical(padding = 20)
        content.addView(pageHeader("Settings", "Local by design"))
        content.addView(sectionTitle("Accounts"), matchWrap(top = 20))
        val accountRows = vertical().apply { tag = "account_rows" }
        content.addView(accountRows)
        repository.query({ dashboard().accounts }) { rows ->
            if (screen == Screen.SETTINGS && accountRows.parent != null) {
                accountRows.removeAllViews()
                privateAccountRows(rows).forEach { accountRows.addView(accountBalanceRow(it), matchWrap(top = 8)) }
            }
        }
        content.addView(sectionTitle("Privacy display"), matchWrap(top = 22))
        content.addView(label("Hide replaces values with dots. Demo keeps believable, scrambled values and graphs for showing the app.", 13, muted), matchWrap(top = 6))
        content.addView(secondaryButton("Mode: " + privacyMode().label) { privacyModeDialog() }, matchHeight(48, top = 10))
        content.addView(sectionTitle("Dashboard"), matchWrap(top = 22))
        content.addView(secondaryButton("Choose & reorder modules") { customizeDashboardDialog() }, matchHeight(48, top = 10))
        content.addView(sectionTitle("Manual data"), matchWrap(top = 22))
        content.addView(secondaryButton("Add transaction") { openManualTransaction() }, matchHeight(48, top = 10))
        content.addView(secondaryButton("Categories, tags & merchants") { openLabels() }, matchHeight(48, top = 8))
        content.addView(sectionTitle("SMS access"), matchWrap(top = 22))
        content.addView(label(if (hasSmsPermissions()) "Enabled · live capture and recovery scan" else "Disabled · transactions cannot be captured", 14, if (hasSmsPermissions()) fern else coral), matchWrap(top = 8))
        if (!hasSmsPermissions()) content.addView(primaryButton("Grant SMS access") { requestSmsPermissions() }, matchHeight(48, top = 10))
        else content.addView(secondaryButton("Scan for missed messages") {
            repository.scanInbox { count -> Toast.makeText(this, "Imported " + count + " new transaction" +
                (if (count == 1) "" else "s"), Toast.LENGTH_SHORT).show() }
        }, matchHeight(48, top = 10))
        content.addView(secondaryButton("Copy privacy-safe diagnostics") { openDiagnostics() }, matchHeight(48, top = 8))
        content.addView(label("Diagnostics retain at most 120 pipeline events for 7 days. They contain no SMS bodies or financial values.", 12, muted), matchWrap(top = 7))
        content.addView(sectionTitle("Reports & backup"), matchWrap(top = 22))
        content.addView(label("Export is the only backup path. Files are written only where you choose; there is no cloud backup.", 13, muted), matchWrap(top = 6))
        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(secondaryButton("CSV") { openExport(ExportType.CSV, data.transactions) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        exportRow.addView(secondaryButton("PDF") { openExport(ExportType.PDF, data.transactions) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        content.addView(exportRow, matchWrap(top = 10))
        content.addView(sectionTitle("Privacy"), matchWrap(top = 22))
        content.addView(label("The installed app has no INTERNET permission, network libraries, analytics, login or cloud code. Android therefore denies it internet sockets. Full SMS bodies are never stored.", 14, muted), matchWrap(top = 8))
        content.addView(label("Credit-card tracking is not supported yet; it is planned.", 13, muted), matchWrap(top = 9))
        content.addView(label("Sender registry: " + repository.registry.sourceName + ", published " +
            repository.registry.sourceDate + ".", 12, muted), matchWrap(top = 12, bottom = 26))
        target.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun bottomNavigation(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(surface)
            listOf(
                Screen.DASHBOARD to "Overview", Screen.ACTIVITY to "Activity",
                Screen.BUDGETS to "Budgets", Screen.SETTINGS to "Settings",
            ).forEach { (destination, title) ->
                addView(Button(this@MainActivity).apply {
                    text = title
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(if (screen == destination) fernDark else muted)
                    typeface = if (screen == destination) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    background = if (screen == destination) rounded(mint, 16f) else null
                    setOnClickListener {
                        screen = destination
                        renderShell()
                    }
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            }
        }
    }

    private fun pageHeader(title: String, subtitle: String) = vertical().apply {
        addView(label(title, 29, ink, bold = true))
        addView(label(subtitle, 14, muted), matchWrap(top = 3))
    }

    private fun dashboardHeader() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(pageHeader("Overview", monthLabel()), LinearLayout.LayoutParams(0, -2, 1f))
        addView(compactButton(privacyMode().shortLabel) {
            val next = when (privacyMode()) {
                PrivacyMode.OFF -> PrivacyMode.HIDE
                PrivacyMode.HIDE -> PrivacyMode.DEMO
                PrivacyMode.DEMO -> PrivacyMode.OFF
            }
            preferences.edit().putString("privacy_mode", next.name).apply()
            currentFilter = DashboardFilter()
            renderCurrent()
        }, LinearLayout.LayoutParams(dp(74), dp(42)).apply { marginEnd = dp(6) })
        addView(compactButton("Filter") { openFilterDialog() }, LinearLayout.LayoutParams(dp(74), dp(42)))
    }

    private fun moduleTitle(title: String, subtitle: String) = LinearLayout(this).apply {
        gravity = Gravity.BOTTOM
        addView(label(title, 17, ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(subtitle, 11, muted))
    }

    private fun dashboardModuleIds(): List<String> {
        val known = DashboardModules.all.map { it.id }.toSet()
        val saved = preferences.getString("dashboard_order", null)
            ?.split(",")?.filter { it in known }.orEmpty()
        val order = (saved + DashboardModules.defaultOrder).distinct()
        val hidden = preferences.getStringSet("dashboard_hidden", emptySet()).orEmpty()
        return order.filterNot(hidden::contains)
    }

    private fun customizeDashboardDialog() {
        val order = (preferences.getString("dashboard_order", null)?.split(",").orEmpty() +
            DashboardModules.defaultOrder).filter { id -> DashboardModules.all.any { it.id == id } }.distinct().toMutableList()
        val hidden = preferences.getStringSet("dashboard_hidden", emptySet()).orEmpty().toMutableSet()
        val rows = vertical(padding = 4)
        lateinit var rebuild: () -> Unit
        fun persist() {
            preferences.edit().putString("dashboard_order", order.joinToString(","))
                .putStringSet("dashboard_hidden", hidden).apply()
        }
        rebuild = {
            rows.removeAllViews()
            order.forEachIndexed { index, id ->
                val spec = DashboardModules.all.first { it.id == id }
                rows.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val toggle = Switch(this@MainActivity).apply {
                        text = spec.title; textSize = 14f; setTextColor(ink)
                        isChecked = id !in hidden
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) hidden.remove(id) else hidden.add(id)
                            persist()
                        }
                    }
                    addView(toggle, LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(textButton("↑") {
                        if (index > 0) {
                            val moved = order.removeAt(index); order.add(index - 1, moved)
                            persist(); rebuild()
                        }
                    })
                    addView(textButton("↓") {
                        if (index < order.lastIndex) {
                            val moved = order.removeAt(index); order.add(index + 1, moved)
                            persist(); rebuild()
                        }
                    })
                })
            }
        }
        rebuild()
        AlertDialog.Builder(this).setTitle("Dashboard modules")
            .setMessage("Switch modules on or off and set their order. IDs are stable for forked custom modules.")
            .setView(ScrollView(this).apply { addView(rows) })
            .setPositiveButton("Done") { _, _ -> renderCurrent() }.show()
    }

    private fun privacyMode(): PrivacyMode = runCatching {
        PrivacyMode.valueOf(preferences.getString("privacy_mode", PrivacyMode.OFF.name)!!)
    }.getOrDefault(PrivacyMode.OFF)

    private fun privacyModeDialog() {
        val modes = PrivacyMode.entries
        AlertDialog.Builder(this).setTitle("Privacy display")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(privacyMode())) { dialog, which ->
                preferences.edit().putString("privacy_mode", modes[which].name).apply()
                currentFilter = DashboardFilter()
                dialog.dismiss(); renderCurrent()
            }.show()
    }

    private fun demoDashboardData(): DashboardData {
        val accounts = demoAccounts()
        return DashboardData(
            totalBalanceMinor = accounts.sumOf { it.balanceMinor },
            monthDebitsMinor = 186_450, monthCreditsMinor = 520_000,
            accounts = accounts, recent = demoTransactions(), categorySpend = demoCategorySpend(),
            budgetProgress = demoBudgetProgress(), dailySpend = demoDailySpend(),
        )
    }

    private fun demoAccounts(): List<AccountBalance> = listOf(
        AccountBalance(Account(9_001, "demo-bank-1", "Demo Bank One", 8_400_000, 0), 8_400_000),
        AccountBalance(Account(9_002, "demo-bank-2", "Demo Bank Two", 4_350_000, 0), 4_350_000),
    )

    private fun privateAccountRows(real: List<AccountBalance>): List<AccountBalance> = when (privacyMode()) {
        PrivacyMode.OFF -> real
        PrivacyMode.DEMO -> demoAccounts()
        PrivacyMode.HIDE -> listOf(
            AccountBalance(Account(9_009, "hidden", "Hidden account", 0, 0), 0))
    }

    private fun demoTransactions(): List<LedgerTransaction> {
        val now = System.currentTimeMillis()
        val samples = listOf(
            Triple("Transit pass", 4_250L, "Transport"),
            Triple("Corner cafe", 18_900L, "Food"),
            Triple("Home supplies", 37_500L, "Shopping"),
            Triple("Monthly salary", 520_000L, "Salary"),
        )
        return samples.mapIndexed { index, (merchant, amount, category) ->
            val credit = category == "Salary"
            LedgerTransaction(
                id = 9_100L + index, bankId = currentFilter.bankId ?: "demo-bank", bankName = "Demo Bank",
                occurredAt = now - index * 86_400_000L, receivedAt = now - index * 86_400_000L,
                amountMinor = amount, direction = if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
                merchant = merchant, merchantKey = currentFilter.merchantKey ?: merchant.lowercase(Locale.ROOT),
                displayMerchant = merchant,
                categoryId = currentFilter.categoryId ?: (9_200L + index), categoryName = category,
                categoryColor = CATEGORY_COLORS[index % CATEGORY_COLORS.size], senderHeader = "DEMOBNK",
                tags = currentFilter.tagId?.let { listOf(Tag(it, "Personal", fern)) }
                    ?: if (credit) emptyList() else listOf(Tag(9_300L + index, "Personal", fern)),
                reference = "DEMO00000000" + index, parserId = "demo-profile-v1", confidence = 95,
            )
        }
    }

    private fun demoCategorySpend(): List<CategorySpend> = listOf(
        CategorySpend(Category(9_201, "Food", CATEGORY_COLORS[0], null), 64_500),
        CategorySpend(Category(9_202, "Shopping", CATEGORY_COLORS[1], null), 52_300),
        CategorySpend(Category(9_203, "Transport", CATEGORY_COLORS[2], null), 41_250),
        CategorySpend(Category(9_204, "Bills", CATEGORY_COLORS[3], null), 28_400),
    )

    private fun demoDailySpend(): List<dev.localledger.data.DailyTotal> {
        val today = java.time.LocalDate.now().toEpochDay()
        return listOf(22_000L, 31_500L, 18_750L, 45_200L, 29_100L, 39_900L).mapIndexed { index, amount ->
            dev.localledger.data.DailyTotal(today - 5 + index, amount)
        }
    }

    private fun demoBudgetProgress(): List<BudgetProgress> {
        val now = System.currentTimeMillis()
        val budget = Budget(9_401, BudgetScopeType.CATEGORY, "9201", "Food",
            100_000, BudgetPeriod.MONTH, 80, true)
        return listOf(BudgetProgress(budget, 64_500, now - 15 * 86_400_000L,
            now + 15 * 86_400_000L, .5, 129_000, BudgetState.PROJECTED_OVER))
    }

    private fun privateValue(value: Long, seed: Int): Long {
        val sign = if (value < 0) -1 else 1
        val absolute = kotlin.math.abs(value)
        return sign * when (privacyMode()) {
            PrivacyMode.OFF -> absolute
            PrivacyMode.HIDE -> seed.coerceAtLeast(1) * 10_000L
            PrivacyMode.DEMO -> (absolute * (72 + ((seed * 37) and 55)) / 100L).coerceAtLeast(100)
        }
    }

    private fun privateMoney(value: Long, seed: Int): String =
        if (privacyMode() == PrivacyMode.HIDE) "₹••••" else money(privateValue(value, seed))

    private fun privateLabel(value: String, kind: String, seed: Int): String = when (privacyMode()) {
        PrivacyMode.OFF -> value
        PrivacyMode.HIDE -> "Hidden $kind"
        PrivacyMode.DEMO -> "Demo $kind " + ((seed and Int.MAX_VALUE) % 7 + 1)
    }

    private fun privateCategorySpend(items: List<CategorySpend>): List<CategorySpend> =
        items.mapIndexed { index, item -> item.copy(
            category = item.category.copy(name = privateLabel(item.category.name, "category", index)),
            spentMinor = privateValue(item.spentMinor, 31 + index)) }

    private fun privateDailySpend(items: List<dev.localledger.data.DailyTotal>) =
        items.mapIndexed { index, item -> item.copy(amountMinor = privateValue(item.amountMinor, 61 + index)) }

    private fun accountSplitDialog(accounts: List<AccountBalance>) {
        val content = vertical(padding = 4)
        accounts.forEachIndexed { index, item ->
            content.addView(LinearLayout(this).apply {
                setPadding(dp(4), dp(12), dp(4), dp(12)); gravity = Gravity.CENTER_VERTICAL
                addView(label(privateLabel(item.account.bankName, "account", index), 14, ink, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(privateMoney(item.balanceMinor, 100 + index), 14, fernDark, bold = true))
            })
        }
        AlertDialog.Builder(this).setTitle("Balance by bank").setView(content)
            .setPositiveButton("Done", null).show()
    }

    private fun insightCard(data: DashboardData) = vertical(padding = 16).apply {
        background = rounded(surface, 18f, line)
        val shownCredits = privateValue(data.monthCreditsMinor, 220)
        val shownDebits = privateValue(data.monthDebitsMinor, 221)
        val net = shownCredits - shownDebits
        val rate = if (shownCredits > 0) ((net.toDouble() / shownCredits) * 100).toInt() else 0
        val top = data.categorySpend.maxByOrNull { it.spentMinor }
        addView(label("Net cash flow  " + if (privacyMode() == PrivacyMode.HIDE) "₹••••" else money(net), 16,
            if (net >= 0) fern else coral, bold = true))
        addView(label("Savings rate  " + if (privacyMode() == PrivacyMode.HIDE) "••%" else rate.toString() + "%",
            13, muted), matchWrap(top = 7))
        addView(label("Largest spend  " + (top?.category?.name?.let { privateLabel(it, "category", 9) } ?: "—") +
            if (top == null) "" else " · " + privateMoney(top.spentMinor, 211), 13, muted), matchWrap(top = 5))
    }

    private fun isDefaultFilter() = currentFilter == DashboardFilter()

    private fun filterSummary(): String {
        if (privacyMode() != PrivacyMode.OFF) return "Private filter"
        return buildList {
        currentFilter.bankId?.let { add("bank=" + it) }
        currentFilter.categoryId?.let { add("category#" + it) }
        currentFilter.merchantKey?.let { add("merchant=" + it) }
        currentFilter.tagId?.let { add("tag#" + it) }
        currentFilter.direction?.let { add(it.name.lowercase()) }
        currentFilter.startAt?.let { add(if (it == 0L) "all time" else "custom period") }
        }.ifEmpty { listOf("Current month") }.joinToString(" · ")
    }

    private fun matchesCurrentFilter(tx: LedgerTransaction): Boolean =
        (currentFilter.bankId == null || tx.bankId == currentFilter.bankId) &&
            (currentFilter.categoryId == null || tx.categoryId == currentFilter.categoryId) &&
            (currentFilter.merchantKey == null || tx.merchantKey == currentFilter.merchantKey) &&
            (currentFilter.tagId == null || tx.tags.any { it.id == currentFilter.tagId }) &&
            (currentFilter.direction == null || tx.direction == currentFilter.direction) &&
            (currentFilter.startAt == null || tx.occurredAt >= currentFilter.startAt!!) &&
            (currentFilter.endAt == null || tx.occurredAt < currentFilter.endAt!!)

    private fun sortedTransactions(items: List<LedgerTransaction>) = when (activitySort) {
        ActivitySort.NEWEST -> items.sortedByDescending { it.occurredAt }
        ActivitySort.OLDEST -> items.sortedBy { it.occurredAt }
        ActivitySort.HIGHEST -> items.sortedByDescending { it.amountMinor }
        ActivitySort.LOWEST -> items.sortedBy { it.amountMinor }
        ActivitySort.MERCHANT -> items.sortedBy { it.displayMerchant.lowercase(Locale.ROOT) }
    }

    private fun sortDialog() {
        val values = ActivitySort.entries
        AlertDialog.Builder(this).setTitle("Sort activity")
            .setSingleChoiceItems(values.map { it.label }.toTypedArray(), values.indexOf(activitySort)) { dialog, index ->
                activitySort = values[index]; dialog.dismiss(); renderCurrent()
            }.show()
    }

    private fun openFilterDialog() {
        repository.query({ FilterData(accounts(), categories(), tags(), merchants()) }) { source ->
            val data = if (privacyMode() == PrivacyMode.DEMO) demoFilterData() else source
            val form = vertical(padding = 4)
            val bank = spinner(listOf("All banks") + data.accounts.mapIndexed { index, item ->
                privateLabel(item.bankName, "account", index)
            })
            val category = spinner(listOf("All categories") + data.categories.mapIndexed { index, item ->
                privateLabel(item.name, "category", index)
            })
            val merchant = spinner(listOf("All merchants") + data.merchants.mapIndexed { index, item ->
                privateLabel(item.displayName, "merchant", index)
            })
            val tag = spinner(listOf("All tags") + data.tags.mapIndexed { index, item ->
                privateLabel(item.name, "tag", index)
            })
            val direction = spinner(listOf("All directions", "Debit", "Credit"))
            val period = spinner(listOf("Current month", "Last 7 days", "Last 30 days", "All time"))
            bank.setSelection((data.accounts.indexOfFirst { it.bankId == currentFilter.bankId } + 1).coerceAtLeast(0))
            category.setSelection((data.categories.indexOfFirst { it.id == currentFilter.categoryId } + 1).coerceAtLeast(0))
            merchant.setSelection((data.merchants.indexOfFirst { it.merchantKey == currentFilter.merchantKey } + 1).coerceAtLeast(0))
            tag.setSelection((data.tags.indexOfFirst { it.id == currentFilter.tagId } + 1).coerceAtLeast(0))
            direction.setSelection(when (currentFilter.direction) {
                TransactionDirection.DEBIT -> 1; TransactionDirection.CREDIT -> 2; null -> 0
            })
            listOf("Bank" to bank, "Category" to category, "Merchant" to merchant,
                "Tag" to tag, "Direction" to direction, "Period" to period).forEach { (title, field) ->
                form.addView(label(title, 12, muted, bold = true), matchWrap(top = 7))
                form.addView(field, matchHeight(44))
            }
            AlertDialog.Builder(this).setTitle("Filter")
                .setView(ScrollView(this).apply { addView(form) })
                .setNegativeButton("Reset") { _, _ -> currentFilter = DashboardFilter(); renderCurrent() }
                .setPositiveButton("Apply") { _, _ ->
                    val now = System.currentTimeMillis()
                    val start = when (period.selectedItemPosition) {
                        1 -> now - 7L * 24 * 60 * 60 * 1000
                        2 -> now - 30L * 24 * 60 * 60 * 1000
                        3 -> 0L
                        else -> null
                    }
                    currentFilter = DashboardFilter(
                        bankId = data.accounts.getOrNull(bank.selectedItemPosition - 1)?.bankId,
                        categoryId = data.categories.getOrNull(category.selectedItemPosition - 1)?.id,
                        merchantKey = data.merchants.getOrNull(merchant.selectedItemPosition - 1)?.merchantKey,
                        tagId = data.tags.getOrNull(tag.selectedItemPosition - 1)?.id,
                        direction = when (direction.selectedItemPosition) {
                            1 -> TransactionDirection.DEBIT; 2 -> TransactionDirection.CREDIT; else -> null
                        }, startAt = start)
                    renderCurrent()
                }.show()
        }
    }

    private fun demoFilterData(): FilterData = FilterData(
        accounts = demoAccounts().map { it.account },
        categories = demoCategorySpend().map { it.category },
        tags = listOf(Tag(9_301, "Personal", fern), Tag(9_302, "Shared", coral)),
        merchants = listOf(
            MerchantSummary("demo-merchant-1", "Corner cafe", "Corner cafe", 9_201),
            MerchantSummary("demo-merchant-2", "Transit pass", "Transit pass", 9_203),
        ),
    )

    private fun privacyPill() = horizontalCard(mint).apply {
        addView(label("OFFLINE", 11, fernDark, bold = true))
        addView(label("  No login · no cloud · no internet permission", 13, fernDark))
    }

    private fun permissionCard() = vertical(padding = 16).apply {
        background = rounded(0xFFFFEEE9.toInt(), 18f)
        addView(label("SMS access is off", 16, coral, bold = true))
        addView(label("Enable it to capture verified transaction alerts.", 13, muted), matchWrap(top = 4))
        addView(primaryButton("Enable") { requestSmsPermissions() }, matchHeight(44, top = 12))
    }

    private fun metricCard(kicker: String, value: String, caption: String, accent: Int) = vertical(padding = 20).apply {
        background = rounded(accent, 24f)
        addView(label(kicker, 11, 0xFFCCE3D5.toInt(), bold = true).apply { letterSpacing = .12f })
        addView(label(value, 32, Color.WHITE, bold = true), matchWrap(top = 7))
        addView(label(caption, 13, 0xFFDCE9E1.toInt()), matchWrap(top = 5))
    }

    private fun smallMetric(title: String, value: String, accent: Int) = vertical(padding = 16).apply {
        background = rounded(surface, 20f, line)
        addView(label(title, 13, muted))
        addView(label(value, 20, accent, bold = true), matchWrap(top = 8))
    }

    private fun categoryLegend(item: CategorySpend) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@MainActivity).apply { background = rounded(item.category.color, 4f) }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
        addView(label(item.category.name, 13, ink), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(if (privacyMode() == PrivacyMode.HIDE) "₹••" else
            moneyCompact(item.spentMinor), 13, muted, bold = true))
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun transactionRow(tx: LedgerTransaction, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, 17f, line)
        val marker = TextView(this@MainActivity).apply {
            text = if (privacyMode() == PrivacyMode.OFF) tx.displayMerchant.take(1).uppercase() else "•"
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tx.categoryColor)
            background = rounded(withAlpha(tx.categoryColor, 0x22), 14f)
        }
        addView(marker, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        addView(vertical().apply {
            addView(label(privateLabel(tx.displayMerchant, "merchant", tx.id.toInt()), 15, ink, bold = true)
                .apply { maxLines = 1 })
            val source = if (tx.origin == TransactionOrigin.MANUAL) "Manual" else
                privateLabel(tx.bankName, "account", tx.bankId.hashCode())
            val category = privateLabel(tx.categoryName, "category", tx.categoryId?.toInt() ?: 0)
            val tagText = if (tx.tags.isEmpty()) "" else " · #" + tx.tags.take(2).joinToString(" #") {
                privateLabel(it.name, "tag", it.id.toInt())
            }
            val shownDate = if (privacyMode() == PrivacyMode.OFF) formatDate(tx.occurredAt) else "Private date"
            addView(label(source + " · " + category + tagText + " · " + shownDate,
                11, muted).apply { maxLines = 1 }, matchWrap(top = 3))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val amountColor = if (tx.direction == TransactionDirection.CREDIT) fern else ink
        val sign = if (tx.direction == TransactionDirection.CREDIT) "+" else "−"
        addView(label(sign + privateMoney(tx.amountMinor, tx.id.toInt()), 15, amountColor, bold = true))
        when (privacyMode()) {
            PrivacyMode.OFF -> setOnClickListener { click() }
            PrivacyMode.DEMO -> setOnClickListener { demoTransactionDialog(tx) }
            PrivacyMode.HIDE -> Unit
        }
    }

    private fun budgetProgressRow(item: BudgetProgress, click: () -> Unit) = vertical(padding = 15).apply {
        background = rounded(surface, 18f, line)
        val shownSpent = privateValue(item.spentMinor, item.budget.id.toInt())
        val shownLimit = privateValue(item.budget.amountMinor, item.budget.id.toInt() + 1).coerceAtLeast(1)
        val shownRatio = shownSpent.toDouble() / shownLimit
        val stateColor = when (privacyMode()) {
            PrivacyMode.HIDE -> muted
            PrivacyMode.DEMO -> when {
                shownRatio >= 1 -> coral
                shownRatio >= .8 -> 0xFFC18420.toInt()
                else -> fern
            }
            PrivacyMode.OFF -> when (item.state) {
                BudgetState.ON_TRACK -> fern
                BudgetState.WATCH -> 0xFFC18420.toInt()
                BudgetState.PROJECTED_OVER, BudgetState.OVER -> coral
            }
        }
        val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(vertical().apply {
            addView(label(privateLabel(item.budget.label, "budget", item.budget.id.toInt()), 15, ink, bold = true))
            addView(label(item.budget.scopeType.name.lowercase().replaceFirstChar(Char::uppercase) +
                " · " + item.budget.period.name.lowercase(), 11, muted), matchWrap(top = 2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label(if (privacyMode() == PrivacyMode.HIDE) "₹•••• / ₹••••" else
            money(shownSpent) + " / " + money(shownLimit), 12, muted))
        addView(top)
        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (shownRatio * 1000)
                .toInt().coerceIn(0, 1000)
            progressTintList = android.content.res.ColorStateList.valueOf(stateColor)
        }, matchHeight(8, top = 10))
        val guidance = when (privacyMode()) {
            PrivacyMode.HIDE -> "Budget status hidden"
            PrivacyMode.DEMO -> when {
                shownRatio >= 1 -> "Demo limit crossed"
                shownRatio >= .8 -> "Demo watch"
                else -> "Demo on track"
            }
            PrivacyMode.OFF -> when (item.state) {
                BudgetState.ON_TRACK -> "On track"
                BudgetState.WATCH -> "Reached " + item.budget.alertPercent + "% alert point"
                BudgetState.PROJECTED_OVER -> "At this pace: " + money(item.projectedMinor)
                BudgetState.OVER -> "Limit crossed"
            }
        }
        addView(label(guidance, 12, stateColor, bold = true), matchWrap(top = 7))
        when (privacyMode()) {
            PrivacyMode.OFF -> setOnClickListener { click() }
            PrivacyMode.DEMO -> setOnClickListener { demoBudgetDialog(item.budget) }
            PrivacyMode.HIDE -> Unit
        }
    }

    private fun guideCard() = vertical(padding = 16).apply {
        background = rounded(mint, 18f)
        addView(label("A useful starting point", 14, fernDark, bold = true))
        addView(label("Set limits where they matter: categories, merchants or cross-cutting tags. Daily, weekly, monthly and yearly periods are supported. Alerts fire once per period; the dashboard always compares spending pace with time elapsed.", 13, fernDark), matchWrap(top = 5))
    }

    private fun emptyCard(message: String) = label(message, 14, muted).apply {
        setPadding(dp(16), dp(20), dp(16), dp(20))
        background = rounded(surface, 18f, line)
    }

    private fun accountSetupRow(name: String, balance: Long, remove: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(11), dp(8), dp(11))
        background = rounded(surface, 16f, line)
        addView(vertical().apply {
            addView(label(name, 14, ink, bold = true))
            addView(label(money(balance), 12, muted), matchWrap(top = 2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Button(this@MainActivity).apply { setText(R.string.remove); isAllCaps = false; setTextColor(coral); background = null; setOnClickListener { remove() } })
    }

    private fun accountBalanceRow(item: AccountBalance) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(surface, 16f, line)
        addView(label(privateLabel(item.account.bankName, "account", item.account.id.toInt()), 14, ink, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(privateMoney(item.balanceMinor, item.account.id.toInt()), 15, fernDark, bold = true))
    }

    private fun editTransaction(tx: LedgerTransaction) {
        repository.query({
            EditData(categories(), tags(), directTransactionTagIds(tx.id), merchantTagIds(tx.merchantKey))
        }) { data ->
            val form = vertical(padding = 4)
            val nickname = EditText(this).apply {
                hint = "Nickname (optional)"
                setText(if (tx.displayMerchant == tx.merchant) "" else tx.displayMerchant)
                setSingleLine()
            }
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("Uncategorised") + data.categories.map { it.name })
                setSelection((data.categories.indexOfFirst { it.id == tx.categoryId } + 1).coerceAtLeast(0))
            }
            val directTags = linkedSetOf<Long>().apply { addAll(data.directTagIds) }
            val merchantTags = linkedSetOf<Long>().apply { addAll(data.merchantTagIds) }
            form.addView(label("Official name: " + tx.merchant, 13, muted), matchWrap(bottom = 8))
            form.addView(nickname, matchHeight(52))
            form.addView(spinner, matchHeight(52, top = 8))
            form.addView(label("Only this payment", 12, muted, bold = true), matchWrap(top = 10))
            form.addView(tagChecklist(data.tags, directTags))
            form.addView(label("All payments for this merchant", 12, muted, bold = true), matchWrap(top = 10))
            form.addView(tagChecklist(data.tags, merchantTags))
            AlertDialog.Builder(this)
                .setTitle("Edit transaction")
                .setView(ScrollView(this).apply { addView(form) })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val category = data.categories.getOrNull(spinner.selectedItemPosition - 1)
                    repository.mutate({
                        saveMerchantRule(tx.merchantKey, nickname.text.toString(), category?.id)
                        setTransactionTags(tx.id, directTags)
                        setMerchantTags(tx.merchantKey, merchantTags)
                    })
                }.show()
        }
    }

    private fun addCategoryDialog() {
        val input = EditText(this).apply { hint = "Category name"; setSingleLine(); setPadding(dp(20), 0, dp(20), 0) }
        AlertDialog.Builder(this)
            .setTitle("New category")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) repository.mutate({ runCatching { addCategory(name, CATEGORY_COLORS[(name.hashCode() and Int.MAX_VALUE) % CATEGORY_COLORS.size]) } })
            }.show()
    }

    private fun addTagDialog() {
        val input = EditText(this).apply { hint = "Tag name"; setSingleLine(); setPadding(dp(20), 0, dp(20), 0) }
        AlertDialog.Builder(this).setTitle("New tag").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) repository.mutate({
                    runCatching { addTag(name, CATEGORY_COLORS[(name.hashCode() and Int.MAX_VALUE) % CATEGORY_COLORS.size]) }
                })
            }.show()
    }

    private fun addMerchantDialog() {
        repository.query({ categories() }) { categories ->
            val form = vertical(padding = 4)
            val official = EditText(this).apply { hint = "Merchant name (required)"; setSingleLine() }
            val nickname = EditText(this).apply { hint = "Nickname (optional)"; setSingleLine() }
            val category = spinner(listOf("Uncategorised") + categories.map { it.name })
            form.addView(official, matchHeight(50)); form.addView(nickname, matchHeight(50, top = 7))
            form.addView(category, matchHeight(50, top = 7))
            AlertDialog.Builder(this).setTitle("New merchant").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                    val name = official.text.toString().trim()
                    if (name.isNotEmpty()) repository.mutate({
                        createMerchant(name, nickname.text.toString(),
                            categories.getOrNull(category.selectedItemPosition - 1)?.id)
                    })
                }.show()
        }
    }

    private fun openManualTransaction() = when (privacyMode()) {
        PrivacyMode.OFF -> manualTransactionDialog()
        PrivacyMode.DEMO -> demoManualTransactionDialog()
        PrivacyMode.HIDE -> privateActionUnavailable()
    }

    private fun openLabels() = when (privacyMode()) {
        PrivacyMode.OFF -> manageLabelsDialog()
        PrivacyMode.DEMO -> demoLabelsDialog()
        PrivacyMode.HIDE -> privateActionUnavailable()
    }

    private fun openBudget(existing: Budget?) = when (privacyMode()) {
        PrivacyMode.OFF -> budgetDialog(existing)
        PrivacyMode.DEMO -> demoBudgetDialog(existing)
        PrivacyMode.HIDE -> privateActionUnavailable()
    }

    private fun openDiagnostics() = when (privacyMode()) {
        PrivacyMode.OFF -> copyDiagnostics()
        PrivacyMode.DEMO -> demoDiagnosticsDialog()
        PrivacyMode.HIDE -> privateActionUnavailable()
    }

    private fun openExport(type: ExportType, transactions: List<LedgerTransaction>) {
        if (privacyMode() == PrivacyMode.OFF) startExport(type, transactions)
        else AlertDialog.Builder(this).setTitle("Local report export")
            .setMessage("CSV and PDF reports are written through Android's local document picker. Export is disabled while privacy display is active so a demo cannot accidentally reveal exact ledger data.")
            .setPositiveButton("Done", null).show()
    }

    private fun privateActionUnavailable() {
        Toast.makeText(this, "Switch to Demo for safe previews or Visible to edit real data", Toast.LENGTH_LONG).show()
    }

    private fun demoTransactionDialog(tx: LedgerTransaction) {
        val seed = tx.id.toInt()
        val content = vertical(padding = 4)
        listOf(
            "Merchant" to privateLabel(tx.displayMerchant, "merchant", seed),
            "Amount" to privateMoney(tx.amountMinor, seed),
            "Account" to privateLabel(tx.bankName, "account", tx.bankId.hashCode()),
            "Category" to privateLabel(tx.categoryName, "category", tx.categoryId?.toInt() ?: seed),
            "Tags" to if (tx.tags.isEmpty()) "Demo tag 1" else tx.tags.joinToString { privateLabel(it.name, "tag", it.id.toInt()) },
            "Date" to "5 Sep, 8:14 pm",
            "Reference" to "DEMO000000001",
        ).forEach { (title, value) ->
            content.addView(label(title.uppercase(Locale.ROOT), 10, muted, bold = true), matchWrap(top = 9))
            content.addView(label(value, 15, ink, bold = true), matchWrap(top = 2))
        }
        content.addView(label("In Visible mode this sheet edits the nickname, category, payment tags and merchant-wide tags.",
            12, muted), matchWrap(top = 14))
        AlertDialog.Builder(this).setTitle("Demo transaction").setView(content)
            .setPositiveButton("Done", null).show()
    }

    private fun demoManualTransactionDialog() {
        val form = vertical(padding = 4)
        listOf(
            "Direction" to spinner(listOf("Debit / expense", "Credit / income")),
            "Amount" to EditText(this).apply { setText(R.string.demo_amount); isEnabled = false },
            "Merchant" to EditText(this).apply { setText(R.string.demo_merchant); isEnabled = false },
            "Account" to spinner(listOf("Demo account 1", "Demo account 2")),
            "Category" to spinner(listOf("Demo category 1", "Demo category 2")),
            "Tags" to spinner(listOf("Demo tag 1", "Demo tag 2")),
        ).forEach { (title, field) ->
            form.addView(label(title, 11, muted, bold = true), matchWrap(top = 7))
            form.addView(field, matchHeight(46))
        }
        form.addView(label("The real form also offers date/time, a note, and whether the entry adjusts the account balance.",
            12, muted), matchWrap(top = 12))
        AlertDialog.Builder(this).setTitle("Demo manual entry").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Preview only", null).show()
    }

    private fun demoLabelsDialog() {
        val content = vertical(padding = 4)
        listOf(
            "Demo category 1  ·  monthly essentials",
            "Demo category 2  ·  flexible spending",
            "#Demo tag 1  ·  inherited from a merchant",
            "#Demo tag 2  ·  attached to one payment",
            "Demo merchant 3  →  Demo category 1",
        ).forEach { content.addView(label(it, 14, ink), matchWrap(top = 10)) }
        content.addView(label("Visible mode can create categories, tags and merchant rules. Tags may apply to one transaction or inherit from a merchant/category.",
            12, muted), matchWrap(top = 16))
        AlertDialog.Builder(this).setTitle("Demo organisation").setView(content)
            .setPositiveButton("Done", null).show()
    }

    private fun demoBudgetDialog(existing: Budget?) {
        val content = vertical(padding = 4)
        val targetLabel = existing?.label?.let { privateLabel(it, "budget", existing.id.toInt()) } ?: "Demo category 1"
        listOf(
            "Target" to targetLabel,
            "Scope" to (existing?.scopeType?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Category"),
            "Period" to (existing?.period?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Month"),
            "Limit" to "₹5,000",
            "Alert" to "80% · once per period",
            "Projection" to "Demo on track",
        ).forEach { (title, value) ->
            content.addView(label(title, 11, muted, bold = true), matchWrap(top = 8))
            content.addView(label(value, 15, ink), matchWrap(top = 2))
        }
        AlertDialog.Builder(this).setTitle(if (existing == null) "Demo new budget" else "Demo budget")
            .setView(content).setPositiveButton("Preview only", null).show()
    }

    private fun demoDiagnosticsDialog() {
        val report = """Local Ledger diagnostic report (DEMO)
SMS permissions: receive=true, read=true
Privacy: no message or financial values included

12:01:04 | IMPORTED | sender=DEMOBNK | parser=demo-debit-v1;confidence=95
11:58:22 | NOT_PARSED | sender=DEMOBNK | amount=true,direction=true,template=none
11:54:10 | IGNORED_PROMOTIONAL | sender=DEMOBNK"""
        AlertDialog.Builder(this).setTitle("Demo diagnostics").setMessage(report)
            .setNegativeButton("Done", null).setPositiveButton("Copy demo") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Local Ledger demo diagnostics", report))
            }.show()
    }

    private fun manageLabelsDialog() {
        AlertDialog.Builder(this).setTitle("Manual data")
            .setItems(arrayOf("Add category", "Add tag", "Add merchant", "Assign tags to category")) { _, which ->
                when (which) {
                    0 -> addCategoryDialog()
                    1 -> addTagDialog()
                    2 -> addMerchantDialog()
                    3 -> tagCategoryDialog()
                }
            }.setNegativeButton("Done", null).show()
    }

    private fun tagCategoryDialog() {
        repository.query({ categories() to tags() }) { pair ->
            val categories = pair.first
            val tags = pair.second
            if (categories.isEmpty() || tags.isEmpty()) {
                Toast.makeText(this, "Add a category and tag first", Toast.LENGTH_SHORT).show()
                return@query
            }
            val category = spinner(categories.map { it.name })
            val selected = linkedSetOf<Long>()
            val form = vertical(padding = 4)
            form.addView(category, matchHeight(50))
            val tagBox = vertical()
            form.addView(tagBox)
            fun refreshTags() {
                repository.query({ categoryTagIds(categories[category.selectedItemPosition].id) }) { ids ->
                    selected.clear(); selected.addAll(ids)
                    tagBox.removeAllViews(); tagBox.addView(tagChecklist(tags, selected))
                }
            }
            category.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshTags()
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            AlertDialog.Builder(this).setTitle("Category tags").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                    repository.mutate({ setCategoryTags(categories[category.selectedItemPosition].id, selected) })
                }.show()
        }
    }

    private fun manualTransactionDialog() {
        repository.query({ FilterData(accounts(), categories(), tags(), merchants()) }) { data ->
            if (data.accounts.isEmpty()) {
                Toast.makeText(this, "Add a bank account first", Toast.LENGTH_SHORT).show()
                return@query
            }
            val form = vertical(padding = 4)
            val direction = spinner(listOf("Debit / expense", "Credit / income"))
            val amount = EditText(this).apply {
                hint = "Amount ₹ (required)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            val merchant = EditText(this).apply { hint = "Merchant or description (optional)"; setSingleLine() }
            val account = spinner(data.accounts.map { it.bankName })
            val category = spinner(listOf("Uncategorised") + data.categories.map { it.name })
            val occurred = EditText(this).apply {
                hint = "Date and time"; setText(MANUAL_TIME.format(java.time.LocalDateTime.now())); setSingleLine()
            }
            val note = EditText(this).apply { hint = "Note (optional)"; setSingleLine() }
            val affects = Switch(this).apply {
                setText(R.string.adjust_bank_balance); isChecked = true; setTextColor(ink)
            }
            val selectedTags = linkedSetOf<Long>()
            listOf(direction, amount, merchant, account, category, occurred, note, affects).forEachIndexed { index, view ->
                form.addView(view, if (index == 0) matchHeight(50) else matchHeight(50, top = 7))
            }
            form.addView(label("Tags", 12, muted, bold = true), matchWrap(top = 10))
            form.addView(tagChecklist(data.tags, selectedTags))
            AlertDialog.Builder(this).setTitle("Manual transaction")
                .setMessage("Amount and account are required. Everything else has a fast default.")
                .setView(ScrollView(this).apply { addView(form) })
                .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                    val value = parseMoney(amount.text.toString())
                    val time = runCatching {
                        java.time.LocalDateTime.parse(occurred.text.toString().trim(), MANUAL_TIME)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                    if (value == null || value <= 0 || time == null) {
                        Toast.makeText(this, "Check amount and date", Toast.LENGTH_LONG).show()
                    } else {
                        val name = merchant.text.toString().trim().ifEmpty {
                            if (direction.selectedItemPosition == 0) "Cash expense" else "Manual income"
                        }
                        val categoryId = data.categories.getOrNull(category.selectedItemPosition - 1)?.id
                        repository.mutate({
                            val key = dev.localledger.sms.TransactionParser.normalizeMerchant(name)
                            saveMerchantRule(key, null, categoryId)
                            addManualTransaction(
                                data.accounts[account.selectedItemPosition].bankId,
                                ParsedTransaction(value,
                                    if (direction.selectedItemPosition == 0) TransactionDirection.DEBIT else TransactionDirection.CREDIT,
                                    time, name, key, usedSmsTime = false),
                                note.text.toString(), affects.isChecked, selectedTags)
                        })
                    }
                }.show()
        }
    }

    private fun budgetDialog(existing: Budget?) {
        repository.query({ FilterData(accounts(), categories(), tags(), merchants()) }) { data ->
            val form = vertical(padding = 4)
            val scope = spinner(BudgetScopeType.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })
            val target = spinner(emptyList())
            val amount = EditText(this).apply {
                hint = "Limit ₹"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(existing?.amountMinor?.let { BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() }.orEmpty())
            }
            val period = spinner(BudgetPeriod.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })
            val threshold = EditText(this).apply {
                hint = "Alert percent"; inputType = InputType.TYPE_CLASS_NUMBER
                setText(String.format(java.util.Locale.ROOT, "%d", existing?.alertPercent ?: 80))
            }
            var targetKeys = emptyList<String>()
            var targetLabels = emptyList<String>()
            fun updateTargets(index: Int) {
                when (BudgetScopeType.entries[index]) {
                    BudgetScopeType.CATEGORY -> {
                        targetKeys = data.categories.map { it.id.toString() }; targetLabels = data.categories.map { it.name }
                    }
                    BudgetScopeType.MERCHANT -> {
                        targetKeys = data.merchants.map { it.merchantKey }; targetLabels = data.merchants.map { it.displayName }
                    }
                    BudgetScopeType.TAG -> {
                        targetKeys = data.tags.map { it.id.toString() }; targetLabels = data.tags.map { it.name }
                    }
                }
                target.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targetLabels)
                existing?.let {
                    val selected = targetKeys.indexOf(it.scopeKey)
                    if (selected >= 0) target.setSelection(selected)
                }
            }
            scope.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateTargets(position)
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            existing?.let {
                scope.setSelection(BudgetScopeType.entries.indexOf(it.scopeType))
                period.setSelection(BudgetPeriod.entries.indexOf(it.period))
            }
            listOf("Limit type" to scope, "For" to target, "Amount" to amount,
                "Period" to period, "Alert at %" to threshold).forEach { (title, view) ->
                form.addView(label(title, 12, muted, bold = true), matchWrap(top = 7))
                form.addView(view, matchHeight(48))
            }
            val builder = AlertDialog.Builder(this).setTitle(if (existing == null) "New budget" else "Edit budget")
                .setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val limit = parseMoney(amount.text.toString())
                    val percent = threshold.text.toString().toIntOrNull()?.coerceIn(1, 100)
                    if (limit == null || limit <= 0 || percent == null || targetKeys.isEmpty()) {
                        Toast.makeText(this, "Choose a target and valid limit", Toast.LENGTH_LONG).show()
                    } else {
                        val index = target.selectedItemPosition.coerceAtLeast(0)
                        repository.mutate({
                            saveBudget(existing?.id, BudgetScopeType.entries[scope.selectedItemPosition],
                                targetKeys[index], targetLabels[index], limit,
                                BudgetPeriod.entries[period.selectedItemPosition], percent)
                        }) { requestNotificationPermissionIfNeeded() }
                    }
                }
            if (existing != null) builder.setNeutralButton("Delete") { _, _ ->
                repository.mutate({ deleteBudget(existing.id) })
            }
            builder.show()
        }
    }

    private fun tagChecklist(tags: List<Tag>, selected: MutableSet<Long>) = vertical().apply {
        if (tags.isEmpty()) addView(label("No tags yet.", 12, muted))
        tags.forEach { tag ->
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = tag.name; setTextColor(ink); isChecked = tag.id in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(tag.id) else selected.remove(tag.id)
                }
            })
        }
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun copyDiagnostics() {
        repository.diagnosticReport { report ->
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Local Ledger diagnostics", report))
            Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExport(type: ExportType, transactions: List<LedgerTransaction>) {
        pendingExport = type; exportTransactions = transactions
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = if (type == ExportType.CSV) "text/csv" else "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "local-ledger-" + java.time.LocalDate.now() +
                if (type == ExportType.CSV) ".csv" else ".pdf")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this).setTitle("Enable quiet budget alerts?")
                .setMessage("Optional. Local Ledger sends at most one alert per budget period. You can still see every status on the dashboard without this permission.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Enable") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
                }.show()
        }
    }

    private fun primaryButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(fern, 16f)
        setOnClickListener { action() }
    }

    private fun compactButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title; textSize = 12f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
        setTextColor(fernDark); background = rounded(surface, 14f, line)
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { action() }
    }

    private fun textButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title; textSize = 12f; isAllCaps = false
        setTextColor(fernDark); background = null
        minWidth = dp(42); minimumWidth = dp(42); setOnClickListener { action() }
    }

    private fun secondaryButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fernDark)
        background = rounded(surface, 16f, fern)
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String) = label(value, 17, ink, bold = true)

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.08f)
    }

    private fun vertical(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (padding > 0) setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    private fun horizontalCard(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = rounded(color, 14f)
    }

    private fun centeredLoading() = FrameLayout(this).apply {
        addView(ProgressBar(this@MainActivity), FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun fieldBackground() = rounded(surface, 14f, line)

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }

    private fun matchHeight(height: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, dp(height)).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun parseMoney(value: String): Long? = runCatching {
        BigDecimal(value.replace(",", "").trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()

    private fun money(minor: Long): String = currencyFormatter.format(BigDecimal(minor).movePointLeft(2))
    private fun moneyCompact(minor: Long): String = when {
        minor >= 10_000_000 -> "₹%.1fL".format(Locale.ENGLISH, minor / 10_000_000.0)
        minor >= 100_000 -> "₹%.1fk".format(Locale.ENGLISH, minor / 100_000.0)
        else -> money(minor)
    }

    private fun formatDate(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault()).format(DATE_FORMAT)

    private fun monthLabel(): String = java.time.ZonedDateTime.now().format(MONTH_FORMAT)

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun requestSmsPermissions() {
        if (hasSmsPermissions()) {
            repository.scanInbox()
            if (root.findViewById<View>(CONTENT_ID) == null) renderShell() else renderCurrent()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Allow SMS access?")
                .setMessage("Android grants access to the SMS inbox. Local Ledger will examine messages only from TRAI-listed senders belonging to the banks you selected, keep only parsed transaction fields, and never send anything off this phone. Receive access captures new alerts; read access recovers alerts received after tracking started.")
                .setNegativeButton("Not now") { _, _ ->
                    if (root.findViewById<View>(CONTENT_ID) == null) renderShell()
                }
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), SMS_PERMISSION_REQUEST)
                }
                .show()
        }
    }

    private fun hasSmsPermissions() = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun recoverMissedMessages() { if (hasSmsPermissions()) repository.scanInbox() }

    private inner class TransactionAdapter(private val items: List<LedgerTransaction>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            transactionRow(items[position]) { editTransaction(items[position]) }.apply {
                layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(72))
                val params = paddingLeft
                setPadding(params, paddingTop, paddingRight, paddingBottom)
            }
    }

    private data class ScreenData(
        val transactions: List<LedgerTransaction> = emptyList(),
        val accounts: List<Account> = emptyList(),
        val categories: List<Category> = emptyList(),
        val tags: List<Tag> = emptyList(),
        val merchants: List<MerchantSummary> = emptyList(),
        val budgets: List<BudgetProgress> = emptyList())
    private data class FilterData(
        val accounts: List<Account>, val categories: List<Category>,
        val tags: List<Tag>, val merchants: List<MerchantSummary>)
    private data class EditData(
        val categories: List<Category>, val tags: List<Tag>,
        val directTagIds: Set<Long>, val merchantTagIds: Set<Long>)
    private enum class PrivacyMode(val label: String, val shortLabel: String) {
        OFF("Visible", "Visible"), HIDE("Hidden", "Hidden"), DEMO("Demo values", "Demo")
    }
    private enum class ActivitySort(val label: String) {
        NEWEST("Newest first"), OLDEST("Oldest first"), HIGHEST("Highest amount"),
        LOWEST("Lowest amount"), MERCHANT("Merchant A–Z")
    }
    private enum class ExportType { CSV, PDF }
    private enum class Screen { DASHBOARD, ACTIVITY, BUDGETS, SETTINGS }

    companion object {
        private const val CONTENT_ID = 0x2204
        private const val SMS_PERMISSION_REQUEST = 41
        private const val NOTIFICATION_PERMISSION_REQUEST = 42
        private const val EXPORT_REQUEST = 43
        private val paper = 0xFFF7F6F0.toInt()
        private val surface = Color.WHITE
        private val ink = 0xFF17211B.toInt()
        private val fern = 0xFF2D6A4F.toInt()
        private val fernDark = 0xFF1B4332.toInt()
        private val mint = 0xFFD8F3DC.toInt()
        private val muted = 0xFF66736B.toInt()
        private val line = 0xFFE3E7E3.toInt()
        private val coral = 0xFFC95B4A.toInt()
        private val currencyFormatter = NumberFormat.getCurrencyInstance(
            Locale.Builder().setLanguage("en").setRegion("IN").build()).apply {
            maximumFractionDigits = 2; minimumFractionDigits = 0
        }
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM, h:mm a")
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")
        private val MANUAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        private val CATEGORY_COLORS = intArrayOf(
            0xFF2A9D8F.toInt(), 0xFFE76F51.toInt(), 0xFF457B9D.toInt(),
            0xFFF4A261.toInt(), 0xFF7B61A8.toInt(), 0xFF6D8B74.toInt(),
        )
    }
}
