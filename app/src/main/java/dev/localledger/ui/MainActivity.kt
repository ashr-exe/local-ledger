package dev.localledger.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import dev.localledger.data.DashboardData
import dev.localledger.data.LedgerRepository
import dev.localledger.data.LedgerTransaction
import dev.localledger.data.TransactionDirection
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
    private val refreshListener: () -> Unit = { if (::root.isInitialized) renderCurrent() }
    private val preferences by lazy { getSharedPreferences("dashboard", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as LocalLedgerApp).repository
        root = FrameLayout(this)
        root.setBackgroundColor(paper)
        setContentView(root)
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
        }
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
            Screen.DASHBOARD -> repository.query({ dashboard() }) { if (screen == Screen.DASHBOARD) showDashboard(target, it) }
            Screen.ACTIVITY -> repository.query({ transactions() }) { if (screen == Screen.ACTIVITY) showActivity(target, it) }
            Screen.BUDGETS -> repository.query({ dashboard() to categories() }) { if (screen == Screen.BUDGETS) showBudgets(target, it.first, it.second) }
            Screen.SETTINGS -> repository.query({ dashboard() }) { if (screen == Screen.SETTINGS) showSettings(target, it) }
        }
    }

    private fun showDashboard(target: FrameLayout, data: DashboardData) {
        target.removeAllViews()
        val content = vertical(padding = 20)
        content.addView(pageHeader("Overview", monthLabel()))

        if (!hasSmsPermissions()) content.addView(permissionCard(), matchWrap(top = 14))

        if (preferences.getBoolean("show_balance", true)) {
            content.addView(metricCard("TOTAL BALANCE", money(data.totalBalanceMinor), "Across ${data.accounts.size} account${if (data.accounts.size == 1) "" else "s"}", fernDark), matchWrap(top = 16))
        }
        val metricRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (preferences.getBoolean("show_spend", true)) {
            metricRow.addView(smallMetric("Spent", money(data.monthDebitsMinor), coral), LinearLayout.LayoutParams(0, dp(108), 1f).apply { marginEnd = dp(6) })
        }
        if (preferences.getBoolean("show_inflow", true)) {
            metricRow.addView(smallMetric("Inflow", money(data.monthCreditsMinor), fern), LinearLayout.LayoutParams(0, dp(108), 1f).apply { marginStart = dp(6) })
        }
        if (metricRow.childCount > 0) content.addView(metricRow, matchWrap(top = 12))

        content.addView(sectionTitle("Spending by category"), matchWrap(top = 24))
        val chartRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val chart = CategoryDonutView(this).apply { submit(data.categorySpend) }
        chartRow.addView(chart, LinearLayout.LayoutParams(dp(132), dp(132)))
        val legend = vertical(padding = 4)
        data.categorySpend.take(5).forEach { legend.addView(categoryLegend(it)) }
        if (data.categorySpend.isEmpty()) legend.addView(label("Transactions will appear here.", 14, muted))
        chartRow.addView(legend, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        content.addView(chartRow, matchWrap(top = 10))

        content.addView(sectionTitle("Recent activity"), matchWrap(top = 22))
        if (data.recent.isEmpty()) {
            content.addView(emptyCard("Waiting for your first verified bank transaction SMS."), matchWrap(top = 10))
        } else {
            data.recent.take(6).forEach { tx ->
                content.addView(transactionRow(tx) { editMerchant(tx) }, matchWrap(top = 8))
            }
        }
        if (data.unparsedMessageCount > 0) {
            content.addView(label("${data.unparsedMessageCount} possible transaction alert${if (data.unparsedMessageCount == 1) "" else "s"} could not be parsed. No message body was retained.", 12, muted), matchWrap(top = 16, bottom = 20))
        } else content.addView(Space(this), matchHeight(20))
        target.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showActivity(target: FrameLayout, transactions: List<LedgerTransaction>) {
        target.removeAllViews()
        val outer = vertical(padding = 20)
        outer.addView(pageHeader("Activity", "Tap a merchant to rename or categorise it"))
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
            setOnItemClickListener { _, _, position, _ -> editMerchant(transactions[position]) }
        }
        outer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        target.addView(outer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showBudgets(target: FrameLayout, dashboard: DashboardData, categories: List<Category>) {
        target.removeAllViews()
        val content = vertical(padding = 20)
        content.addView(pageHeader("Budgets", "Monthly category limits"))
        content.addView(guideCard(), matchWrap(top = 14))
        val spendById = dashboard.categorySpend.associate { it.category.id to it.spentMinor }
        categories.forEach { category ->
            content.addView(budgetRow(category, spendById[category.id] ?: 0L), matchWrap(top = 10))
        }
        content.addView(secondaryButton("Add category") { addCategoryDialog() }, matchHeight(50, top = 14, bottom = 24))
        target.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showSettings(target: FrameLayout, data: DashboardData) {
        target.removeAllViews()
        val content = vertical(padding = 20)
        content.addView(pageHeader("Settings", "Local by design"))
        content.addView(sectionTitle("Accounts"), matchWrap(top = 20))
        data.accounts.forEach { content.addView(accountBalanceRow(it), matchWrap(top = 8)) }
        content.addView(sectionTitle("Dashboard cards"), matchWrap(top = 22))
        content.addView(toggle("Total balance", "show_balance", true))
        content.addView(toggle("Monthly spending", "show_spend", true))
        content.addView(toggle("Monthly inflow", "show_inflow", true))
        content.addView(sectionTitle("SMS access"), matchWrap(top = 22))
        content.addView(label(if (hasSmsPermissions()) "Enabled · live capture and recovery scan" else "Disabled · transactions cannot be captured", 14, if (hasSmsPermissions()) fern else coral), matchWrap(top = 8))
        if (!hasSmsPermissions()) content.addView(primaryButton("Grant SMS access") { requestSmsPermissions() }, matchHeight(48, top = 10))
        else content.addView(secondaryButton("Scan for missed messages") {
            repository.scanInbox { count -> Toast.makeText(this, "Imported $count new transaction${if (count == 1) "" else "s"}", Toast.LENGTH_SHORT).show() }
        }, matchHeight(48, top = 10))
        content.addView(sectionTitle("Privacy"), matchWrap(top = 22))
        content.addView(label("No internet permission. No analytics. No account credentials. Full SMS bodies are never copied into Local Ledger.", 14, muted), matchWrap(top = 8))
        content.addView(label("Sender registry: ${repository.registry.sourceName}, published ${repository.registry.sourceDate}.", 12, muted), matchWrap(top = 12, bottom = 26))
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
        addView(label(moneyCompact(item.spentMinor), 13, muted, bold = true))
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun transactionRow(tx: LedgerTransaction, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, 17f, line)
        val marker = TextView(this@MainActivity).apply {
            text = tx.displayMerchant.take(1).uppercase()
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tx.categoryColor)
            background = rounded(withAlpha(tx.categoryColor, 0x22), 14f)
        }
        addView(marker, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        addView(vertical().apply {
            addView(label(tx.displayMerchant, 15, ink, bold = true).apply { maxLines = 1 })
            addView(label("${tx.bankName} · ${tx.categoryName} · ${formatDate(tx.occurredAt)}", 11, muted).apply { maxLines = 1 }, matchWrap(top = 3))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val amountColor = if (tx.direction == TransactionDirection.CREDIT) fern else ink
        val sign = if (tx.direction == TransactionDirection.CREDIT) "+" else "−"
        addView(label("$sign${money(tx.amountMinor)}", 15, amountColor, bold = true))
        setOnClickListener { click() }
    }

    private fun budgetRow(category: Category, spent: Long) = vertical(padding = 15).apply {
        background = rounded(surface, 18f, line)
        val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(category.name, 15, ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label(
            if (category.monthlyBudgetMinor == null) money(spent)
            else "${money(spent)} / ${money(category.monthlyBudgetMinor)}",
            13, muted,
        ))
        addView(top)
        if (category.monthlyBudgetMinor != null) {
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = ((spent.toDouble() / category.monthlyBudgetMinor.coerceAtLeast(1) * 1000).toInt()).coerceIn(0, 1000)
                progressTintList = android.content.res.ColorStateList.valueOf(if (spent > category.monthlyBudgetMinor) coral else category.color)
            }, matchHeight(8, top = 10))
        }
        setOnClickListener { budgetDialog(category) }
    }

    private fun guideCard() = vertical(padding = 16).apply {
        background = rounded(mint, 18f)
        addView(label("A useful starting point", 14, fernDark, bold = true))
        addView(label("Plan monthly, track as you spend, and review at month-end. The optional 50/30/20 guide uses take-home income: 50% needs, 30% wants, 20% savings and debt.", 13, fernDark), matchWrap(top = 5))
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
        addView(label(item.account.bankName, 14, ink, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(money(item.balanceMinor), 15, fernDark, bold = true))
    }

    private fun toggle(title: String, key: String, default: Boolean) = Switch(this).apply {
        text = title
        textSize = 15f
        setTextColor(ink)
        isChecked = preferences.getBoolean(key, default)
        setPadding(0, dp(7), 0, dp(7))
        setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean(key, checked).apply() }
    }

    private fun editMerchant(tx: LedgerTransaction) {
        repository.query({ categories() }) { categories ->
            val form = vertical(padding = 4)
            val nickname = EditText(this).apply {
                hint = "Nickname (optional)"
                setText(if (tx.displayMerchant == tx.merchant) "" else tx.displayMerchant)
                setSingleLine()
            }
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Uncategorised") + categories.map { it.name })
                setSelection((categories.indexOfFirst { it.id == tx.categoryId } + 1).coerceAtLeast(0))
            }
            form.addView(label("Official name: ${tx.merchant}", 13, muted), matchWrap(bottom = 8))
            form.addView(nickname, matchHeight(52))
            form.addView(spinner, matchHeight(52, top = 8))
            AlertDialog.Builder(this)
                .setTitle("Merchant rule")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val category = categories.getOrNull(spinner.selectedItemPosition - 1)
                    repository.mutate({ saveMerchantRule(tx.merchantKey, nickname.text.toString(), category?.id) })
                }.show()
        }
    }

    private fun budgetDialog(category: Category) {
        val input = EditText(this).apply {
            hint = "Monthly limit (₹)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(category.monthlyBudgetMinor?.let { BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() }.orEmpty())
            setPadding(dp(20), 0, dp(20), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("${category.name} budget")
            .setMessage("Leave blank to remove the limit.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::parseMoney)
                if (input.text.isBlank() || value != null) repository.mutate({ setCategoryBudget(category.id, value) })
            }.show()
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

    private fun primaryButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(fern, 16f)
        setOnClickListener { action() }
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
            transactionRow(items[position]) { editMerchant(items[position]) }.apply {
                layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(72))
                val params = paddingLeft
                setPadding(params, paddingTop, paddingRight, paddingBottom)
            }
    }

    private enum class Screen { DASHBOARD, ACTIVITY, BUDGETS, SETTINGS }

    companion object {
        private const val CONTENT_ID = 0x2204
        private const val SMS_PERMISSION_REQUEST = 41
        private val paper = 0xFFF7F6F0.toInt()
        private val surface = Color.WHITE
        private val ink = 0xFF17211B.toInt()
        private val fern = 0xFF2D6A4F.toInt()
        private val fernDark = 0xFF1B4332.toInt()
        private val mint = 0xFFD8F3DC.toInt()
        private val muted = 0xFF66736B.toInt()
        private val line = 0xFFE3E7E3.toInt()
        private val coral = 0xFFC95B4A.toInt()
        private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 2; minimumFractionDigits = 0
        }
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM, h:mm a")
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")
        private val CATEGORY_COLORS = intArrayOf(
            0xFF2A9D8F.toInt(), 0xFFE76F51.toInt(), 0xFF457B9D.toInt(),
            0xFFF4A261.toInt(), 0xFF7B61A8.toInt(), 0xFF6D8B74.toInt(),
        )
    }
}
