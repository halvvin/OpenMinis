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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
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
        val service = this@FloatingAssistantService
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        layoutParams = params
        floatingView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(service)
            setViewTreeViewModelStoreOwner(service)
            this.setViewTreeSavedStateRegistryOwner(service)
            setContent {
                val repo = service.providerRepository
                val vm = FloatingAssistantViewModel(this@FloatingAssistantService, repo)
                SystemFloatingAssistantContent(
                    viewModel = vm,
                    providerRepository = repo,
                    onDrag = { dx, dy ->
                        service.layoutParams.x += dx.toInt()
                        service.layoutParams.y += dy.toInt()
                        service.windowManager.updateViewLayout(this@FloatingAssistantService.floatingView, service.layoutParams)
                    },
                    onFocusNeeded = { focusable ->
                        val p = service.layoutParams
                        p.flags = if (focusable)
                            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        else
                            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        service.windowManager.updateViewLayout(this@FloatingAssistantService.floatingView, p)
                    },
                    onResize = { w, h ->
                        val p = service.layoutParams
                        p.width = w
                        p.height = h
                        service.windowManager.updateViewLayout(this@FloatingAssistantService.floatingView, p)
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