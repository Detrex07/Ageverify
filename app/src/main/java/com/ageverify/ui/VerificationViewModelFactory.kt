package com.ageverify.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.ageverify.config.Jurisdiction
import com.ageverify.config.VerificationConfig
import com.ageverify.core.AgeVerificationEngine
import com.ageverify.util.AppLog

/**
 * VerificationViewModelFactory
 *
 * Creates a VerificationViewModel with the right engine and config.
 * Config is resolved from Intent extras passed to MainActivity.
 */
class VerificationViewModelFactory(
    private val context: Context,
    private val config: VerificationConfig
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VerificationViewModel::class.java)) {
            val engine = AgeVerificationEngine(context.applicationContext)
            return VerificationViewModel(engine, config) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}

/**
 * AppViewModelStore
 *
 * Application-scoped ViewModelStore. Allows activities to share a single
 * VerificationViewModel instance across the liveness → ID scan → result flow.
 *
 * Why not use the activity's own ViewModelStore?
 *   - Each Activity gets its own ViewModelStore by default
 *   - A ViewModel created in MainActivity is destroyed when MainActivity finishes
 *   - LivenessActivity and IDScanActivity launch as separate activities,
 *     so they'd each get a fresh ViewModel — defeating the purpose
 *
 * This gives us one shared store that outlives individual activities, cleared
 * only when the Application is destroyed or we explicitly call clear().
 *
 * Usage:
 *   val vm = AppViewModelStore.get(context, config)
 */
object AppViewModelStore : ViewModelStoreOwner {

    private val store = ViewModelStore()

    override val viewModelStore: ViewModelStore
        get() = store

    private var currentConfig: VerificationConfig = VerificationConfig()
    private var applicationContext: Context? = null

    fun init(context: Context, config: VerificationConfig) {
        applicationContext = context.applicationContext
        currentConfig = config
    }

    fun get(context: Context, config: VerificationConfig? = null): VerificationViewModel {
        val resolvedConfig = config ?: currentConfig.also {
            if (applicationContext == null) {
                AppLog.w("AppViewModelStore",
                    "get() called before init() — using GLOBAL_DEFAULT (18+). " +
                    "Call AppViewModelStore.init() or pass config explicitly."
                )
            }
        }
        if (config != null) currentConfig = config
        applicationContext = context.applicationContext

        return ViewModelProvider(
            this,
            VerificationViewModelFactory(context.applicationContext, resolvedConfig)
        )[VerificationViewModel::class.java]
    }

    /**
     * Clear ViewModel and session. Call this when the user explicitly
     * exits the verification flow without completing, or on app logout.
     */
    fun clear() {
        store.clear()
        VerificationSessionRepository.clear()
    }
}

/**
 * Patch AgeVerificationEngine to expose its context so ViewModel
 * can retrieve the existing token manager without duplicating context storage.
 * Context is now exposed directly on the engine class.
 */
