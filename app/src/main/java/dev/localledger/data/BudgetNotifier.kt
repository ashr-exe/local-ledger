package dev.localledger.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.localledger.ui.MainActivity

object BudgetNotifier {
    private const val CHANNEL_ID = "budget_watch"

    fun notify(context: Context, database: LedgerDatabase) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val alerts = database.claimBudgetAlerts()
        if (alerts.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Budget watch", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One quiet alert per budget period when a budget needs attention"
                setShowBadge(false)
            })
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alerts.forEach { progress ->
            val message = when (progress.state) {
                BudgetState.OVER -> "The limit has been crossed for this " +
                    progress.budget.period.name.lowercase() + "."
                BudgetState.PROJECTED_OVER -> "Current pace is projected to cross the limit."
                BudgetState.WATCH -> "Spending reached " + progress.budget.alertPercent + "% of the limit."
                BudgetState.ON_TRACK -> return@forEach
            }
            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(dev.localledger.R.drawable.ic_app)
                .setContentTitle(progress.budget.label + " budget")
                .setContentText(message).setContentIntent(intent).setAutoCancel(true)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE).build()
            manager.notify((10_000L + progress.budget.id).toInt(), notification)
        }
    }
}
