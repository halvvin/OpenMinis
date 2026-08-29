package com.openminis.app.ui.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * [T-floating-system] System-wide floating assistant — Foreground Service
 * with WindowManager (TYPE_APPLICATION_OVERLAY) so the bubble + panel float
 * OVER ALL apps, not just inside the app. Drag/resize are direct
 * WindowManager.LayoutParams updates (60fps, no Compose recomposition lag).
 *
 * The ComposeView inside uses Lifecycle + SavedStateRegistry so it survives
 * service restarts correctly.
 */
class FloatingAssistantService : LifecycleService(), SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /** Lazy access to the app's ProviderRepository (set in MinisApp.onCreate). */
    private val providerRepository: com.openminis.app.data.repository.ProviderRepository?
        get() = (application as? com.openminis.app.MinisApp)?.providerRepositoryOrNull

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        startForegroundService()
        setupFloatingWindow()
    }

    private fun startForegroundService() {
        val channelId = "floating_assistant_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "دستیار شناور هوشمند",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("دستیار هوشمند فعال است")
            .setContentText("دستیار آماده استفاده در تمامی برنامه‌ها می‌باشد")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NO_FAIL_FAST,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        floatingView = ComposeView(this).apply {
            ViewTreeLifecycleOwner.set(this, this@FloatingAssistantService)
            ViewTreeViewModelStoreOwner.set(this, this@FloatingAssistantService)
            this.setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setContent {
                val repo = providerRepository
                val vm = FloatingAssistantViewModel(this, repo)
                SystemFloatingAssistantContent(
                    viewModel = vm,
                    providerRepository = repo,
                    onDrag = { dx, dy ->
                        layoutParams.x += dx.toInt()
                        layoutParams.y += dy.toInt()
                        windowManager.updateViewLayout(this, layoutParams)
                    },
                    onFocusNeeded = { focusable ->
                        layoutParams.flags = if (focusable)
                            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        else
                            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        windowManager.updateViewLayout(this, layoutParams)
                    },
                    onResize = { w, h ->
                        layoutParams.width = w
                        layoutParams.height = h
                        windowManager.updateViewLayout(this, layoutParams)
                    }
                )
            }
        }
        windowManager.addView(floatingView, layoutParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}