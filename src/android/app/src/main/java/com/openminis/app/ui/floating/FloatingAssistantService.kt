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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * [T-floating-system] System-wide floating assistant — Foreground Service
 * with WindowManager (TYPE_APPLICATION_OVERLAY) so the bubble + panel float
 * OVER all apps, not just inside the app. Drag/resize are direct
 * WindowManager.LayoutParams updates (60fps, no Compose recomposition lag).
 *
 * The ComposeView inside uses Lifecycle + SavedStateRegistry so it survives
 * service restarts correctly.
 *
 * [FA-BUG-01] The ViewModel is created ONCE through [ViewModelProvider] with
 * this service as the [ViewModelStoreOwner] — never inside `setContent`.
 * Recomposition cannot recreate it; the store is cleared in [onDestroy].
 */
class FloatingAssistantService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _viewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore
        get() = _viewModelStore

    /**
     * Created lazily but exactly once — ViewModelProvider.get() is idempotent
     * against this service's store, so the conversation survives recomposition
     * and the store clear in onDestroy releases the scope.
     */
    private val assistantViewModel: FloatingAssistantViewModel by lazy {
        ViewModelProvider(this, FloatingAssistantViewModel.factory(applicationContext))
            .get(FloatingAssistantViewModel::class.java)
    }

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
        // [F-A1 manifest] The service now declares foregroundServiceType=
        // specialUse (Android 14+ requirement) — see AndroidManifest.xml.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
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
        // Touch the lazy delegate BEFORE setContent so the VM exists (and its
        // model-loading poller is running) before the first frame renders.
        val vm = service.assistantViewModel
        floatingView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(service)
            setViewTreeViewModelStoreOwner(service)
            this.setViewTreeSavedStateRegistryOwner(service)
            setContent {
                val repo = (application as? com.openminis.app.MinisApp)?.providerRepositoryOrNull
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
        floatingView = null
        _viewModelStore.clear()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
