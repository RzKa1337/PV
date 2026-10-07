package com.solartracker.pro

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.solartracker.pro.i18n.AppLocale
import com.solartracker.pro.update.UpdateManager
import com.solartracker.pro.update.UpdateRecovery
import com.solartracker.pro.update.UpdateStore
import com.solartracker.pro.energy.DailyReportWorker
import com.solartracker.pro.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SolarTrackerApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startedActivities = 0

    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        // Language for texts built outside the Activity (widget, workers, view models); Polish by default.
        AppLocale.apply(AppLocale.stored(this), resources.configuration.locales[0] ?: java.util.Locale.getDefault())
        // Must run before anything opens the settings DataStore (it may restore the settings file).
        val recovery = UpdateRecovery(this)
        val startResult = recovery.onAppStart(BuildConfig.VERSION_CODE.toLong())

        updateManager = UpdateManager(this, UpdateStore(this), recovery, appScope) { startedActivities > 0 }
        updateManager.onAppStart(startResult)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { updateManager.recordCrash() }
            previous?.uncaughtException(thread, throwable)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        appScope.launch { runCatching { updateManager.store.migrateToken() } }
        appScope.launch { UpdateWorker.schedule(this@SolarTrackerApplication, updateManager.store.snapshot().config) }
        DailyReportWorker.schedule(this)
    }
}
