package com.github.bluetrees2.novpn

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.*

class BootUpService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        internal var instance: BootUpService? = null

        fun cancelAndStop() {
            instance?.apply {
                GlobalScope.launch { ipTablesModel.cancel() }
                stopForeground(true)
                stopSelf()
                instance = null
                val notificationManager: NotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId)
            }
        }
    }

    private lateinit var ipTablesModel: IPTablesModelAsync
    private var statusSuccess = true
    private var selectedAppsCount = 0

    private val notificationId get() = resources.getInteger(R.integer.boot_notification_id)


    override fun onCreate() {
        super.onCreate()
        instance = this
        ipTablesModel = IPTablesModelAsync(this).apply {
            onErrorListener = { e -> withContext(Dispatchers.Main) { showError(e) } }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val database = SelectedAppsDatabase.newInstance(this)
        val installedApps = getInstalledAppsBlocking(packageManager)
        val selectedApps = mutableListOf<AppInfoModel>()
        val removedApps = mutableListOf<SelectedApp>()
        runBlocking {
            database.dao().getAll().forEach { row ->
                val appInfo = installedApps.find { row.name == it.name }
                if (appInfo == null)
                    removedApps.add(row)
                else
                    selectedApps.add(appInfo)
            }
        }
        if (removedApps.isNotEmpty())
            runBlocking { database.dao().deleteAll(*removedApps.toTypedArray()) }

        if (selectedApps.isEmpty()) {
            stopSelf()
            instance = null
            return START_NOT_STICKY
        }
        selectedAppsCount = selectedApps.size

        val builder = buildNotification()
            .setContentText(getString(R.string.applying_iptables_rules))
        startForeground(notificationId, builder.build())

        GlobalScope.launch {
            ipTablesModel.apply {
                cleanup()
                clearAndAddUIDs(selectedApps.map { it.uid })
                enable()
                masquerade()
                onCompletionListener = {
                    withContext(Dispatchers.Main) {
                        showSuccess()
                        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            stopForeground(STOP_FOREGROUND_DETACH)
                        else
                            stopForeground(false)
                        stopSelf()
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun showError(e: Throwable) {
        statusSuccess = false
        val lines = when {
            e is RootError -> listOf(getString(R.string.root_error))
            e is TimeoutCancellationException -> listOf(getString(R.string.timeout_error))
            e is IPTablesError || !ipTablesModel.iptablesOK -> listOf(getString(R.string.iptables_error))
            e is IP6TablesError -> listOf(
                resources.getQuantityString(R.plurals.n_apps_bypassing_vpn, selectedAppsCount)
                         .format(selectedAppsCount),
                when(ipTablesModel.ip6tablesRejectOK) {
                    Ternary.YES -> getString(R.string.ipv6_rejected_brief)
                    Ternary.NO -> getString(R.string.ipv6_leaking_brief)
                    Ternary.UNKNOWN -> getString(R.string.ipv6_unknown_brief)
                })
            else -> listOf(getString(R.string.unknown_error))
        }
        val iconRes = when(e) {
            is IP6TablesError -> R.drawable.ic_warning
            else -> R.drawable.ic_error
        }
        val builder = buildNotification()
            .setSmallIcon(iconRes)
            .setStyle(
                NotificationCompat
                .InboxStyle()
                    .also { style ->
                        lines.forEach { line ->
                            style.addLine(HtmlCompat.fromHtml(line, HtmlCompat.FROM_HTML_MODE_COMPACT))
                        }
                    }
            )

        NotificationManagerCompat.from(this)
            .notify(notificationId, builder.build())
    }

    private fun showSuccess() {
        if (!statusSuccess)
            return
        val builder = buildNotification()
            .setOnlyAlertOnce(true)
            .setContentText(resources
                .getQuantityString(R.plurals.n_apps_bypassing_vpn, selectedAppsCount)
                .format(selectedAppsCount))

        NotificationManagerCompat.from(this)
            .notify(notificationId, builder.build())
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val channelId = getString(R.string.notification_channel_id)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 0)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_novpn2)
            .setContentTitle(getString(R.string.app_name))
            .setDefaults(0)
            .setSound(null)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channelId = getString(R.string.notification_channel_id)
            val channel = NotificationChannel(channelId, name, importance).apply {
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                setImportance(NotificationManager.IMPORTANCE_LOW)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

class BootUpBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when(intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                startBootUpService(context)
            }
            else -> {}
        }
    }
}

internal fun startBootUpService(context: Context?) {
    val serviceIntent = Intent(context, BootUpService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        context?.startForegroundService(serviceIntent)
    else
        context?.startService(serviceIntent)
}

internal fun stopBootUpService() {
    BootUpService.cancelAndStop()
}