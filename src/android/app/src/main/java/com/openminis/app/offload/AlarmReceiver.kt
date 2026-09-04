package com.openminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openminis.app.logging.AppLogger

/**
 * BroadcastReceiver that fires when an alarm or timer triggers.
 * Shows a high-priority notification with the alarm label.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val NOTIFICATION_GROUP = "minis_alarm_group"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED arrives with no extras; it just keeps the receiver
        // resident so the AlarmManager re-fires the persisted alarms after
        // a reboot. Nothing to clean up in that branch.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLogger.info(TAG, "BOOT_COMPLETED — alarm receiver resident")
            // [T-android-scheduled-tasks-design] Re-register every enabled
            // ScheduledTask with AlarmManager. The OS drops all pending
            // alarms on reboot, so persisted tasks would silently stop
            // firing without this.
            // [F-A2 fix / SCHED-LEGACY-BOOT-01] The legacy AlarmOffloadManager
            // entries (android-alarm CLI: DAILY/WEEKDAYS timers + one-shots)
            // are RAM-only too — setRepeating/setExactAndAllowWhileIdle do NOT
            // survive a reboot. The old comment claimed they "DO survive via
            // the OS", which is wrong; now they are re-registered here as well.
            try {
                com.openminis.app.scheduled.ScheduledTaskManager(context).rescheduleAll()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "scheduled-task rescheduleAll failed: ${t.message}")
            }
            try {
                com.openminis.app.offload.AlarmOffloadManager(context).rescheduleAllOnBoot()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "legacy alarm rescheduleAllOnBoot failed: ${t.message}")
            }
            // [F-A1 boot-resume] Floating assistant used to stay dead after a
            // reboot even though the settings toggle still showed ON — the
            // assistantEnabled pref was written by AssistantSettingsScreen but
            // NEVER read at startup. BOOT_COMPLETED is a foreground-service
            // start exemption on Android 12+, so this is the one place we can
            // bring the overlay assistant back up unattended.
            try {
                val prefs = com.openminis.app.automation.AutomationPrefs.get(context)
                if (prefs.assistantEnabled &&
                    (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                        android.provider.Settings.canDrawOverlays(context))
                ) {
                    val svc = Intent(context, com.openminis.app.ui.floating.FloatingAssistantService::class.java)
                    androidx.core.content.ContextCompat.startForegroundService(context, svc)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "floating assistant boot-resume failed: ${t.message}")
            }
            return
        }

        val alarmId = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_ID) ?: "unknown"
        val label = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_LABEL) ?: "Alarm"

        AppLogger.debug(TAG, "Alarm triggered: id=$alarmId label=$label")

        // [F-A1 hardening] The receiver is exported (required to receive the
        // protected BOOT_COMPLETED broadcast), which means other apps can fire
        // custom actions at it. Only honor an id that AlarmOffloadManager
        // actually knows about — a spoofed broadcast with a fabricated id used
        // to get through to the notification below with attacker-chosen text.
        if (alarmId != "unknown" && !AlarmOffloadManager(context).hasAlarm(alarmId)) {
            AppLogger.warning(TAG, "dropping alarm broadcast with unknown id=$alarmId (possible spoof)")
            return
        }

        // Drop the entry from prefs for ONCE alarms / timers so the next
        // `android-alarm list` call no longer surfaces them. DAILY/WEEKDAYS
        // are kept (the OS re-fires them).
        if (alarmId != "unknown") {
            try {
                AlarmOffloadManager(context).onAlarmFired(alarmId)
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "onAlarmFired($alarmId) failed: ${e.message}")
            }
        }

        val notificationId = alarmId.hashCode() and 0x7FFFFFFF

        // Launch intent – open the app when the notification is tapped
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val notification = NotificationCompat.Builder(context, AlarmOffloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Minis Alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot post notification – missing POST_NOTIFICATIONS permission: ${e.message}")
        }
    }
}
