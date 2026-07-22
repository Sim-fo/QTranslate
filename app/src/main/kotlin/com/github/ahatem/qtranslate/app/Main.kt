package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.events.AppEvent
import com.github.ahatem.qtranslate.ui.swing.main.MainAppFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import javax.swing.SwingUtilities

fun main() = runBlocking {

    var frame: MainAppFrame? = null
    var heartbeatMonitor: HeartbeatMonitor? = null

    if (!SingleInstanceGuard.tryLock(onFocusRequested = {
            SwingUtilities.invokeLater {
                frame?.apply {
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
        })) {
        return@runBlocking
    }

    AppUiSetup.setSystemProperties()
    AppUiSetup.setRenderingHints()

    val appData    = AppDataDirectory.resolve()
    val logFactory = FileLoggerFactory(File(appData, "logs"))
    val logger     = logFactory.getLogger("Main")

    Runtime.getRuntime().addShutdownHook(Thread {
        heartbeatMonitor?.stop()
        SingleInstanceGuard.release()
        logFactory.close()
    })

    logger.info("QTranslate ${AppConstants.APP_VERSION} starting...")
    logger.info("App data directory: ${appData.absolutePath}")

    val json         = Json { ignoreUnknownKeys = true; isLenient = true }
    val settingsRepo = SettingsRepository(appData, json, logFactory.getLogger("SettingsRepository"))

    val initialConfig = withTimeoutOrNull(AppConstants.CONFIG_LOAD_TIMEOUT_MS) {
        settingsRepo.loadInitialConfiguration()
    } ?: run {
        logger.warn("Configuration load timed out after ${AppConstants.CONFIG_LOAD_TIMEOUT_MS}ms — using defaults")
        Configuration.DEFAULT
    }

    logger.info("Configuration loaded: theme=${initialConfig.themeId}, scale=${initialConfig.uiScale}")

    val deps = buildDependencies(
        appData       = appData,
        loggerFactory = logFactory,
        settingsRepo  = settingsRepo,
        initialConfig = initialConfig
    )
    AppUiSetup.apply(initialConfig, deps.themeManager)

    // ---- Startup registration ----
    val jarPath = resolveJarPath()
    if (jarPath != null) {
        deps.startupManager.setEnabled(initialConfig.launchOnSystemStartup, jarPath)
        logger.info("Startup registration synced: enabled=${initialConfig.launchOnSystemStartup}")

        // Keep registry in sync when settings are saved.
        deps.appScope.launch {
            deps.settingsStore.state.collect { state ->
                if (!state.isSaving) {
                    withContext(Dispatchers.IO) {
                        deps.startupManager.setEnabled(
                            state.workingConfiguration.launchOnSystemStartup,
                            jarPath
                        )
                    }
                }
            }
        }
    }

    logger.info("Loading plugins...")
    runCatching { deps.pluginManager.loadAndProcessPlugins() }
        .onSuccess { logger.info("Plugins loaded successfully") }
        .onFailure { e -> logger.error("Failed to load plugins", e) }

    val savedLanguage = if (initialConfig.interfaceLanguage == LanguageCode.ENGLISH.tag) {
        OsLanguageDetector.detect(deps.localizationManager.availableLanguages)
    } else {
        LanguageCode(initialConfig.interfaceLanguage)
    }
    runCatching {
        deps.localizationManager.loadLanguage(savedLanguage)
        logger.info("Interface language loaded: ${initialConfig.interfaceLanguage}")
    }.onFailure { e ->
        logger.warn("Failed to load interface language '${initialConfig.interfaceLanguage}': ${e.message}")
    }

    SwingUtilities.invokeLater {
        frame = MainAppFrame(
            mainStore        = deps.mainStore,
            settingsStore    = deps.settingsStore,
            iconManager      = deps.iconManager,
            themeManager     = deps.themeManager,
            localizer        = deps.localizationManager,
            pluginManager    = deps.pluginManager,
            notificationBus  = deps.notificationBus,
            logger           = logFactory.getLogger("MainAppFrame")
        )
        logger.info("Main window launched")

        // Start heartbeat monitor — writes health status to log every 5 minutes
        heartbeatMonitor = HeartbeatMonitor(
            logger           = logFactory.getLogger("Heartbeat"),
            mainStore        = { frame?.let { deps.mainStore } },
            hotkeyHealthCheck = { frame?.isHotkeySystemAlive() ?: false },
            hotkeyRecovery   = { frame?.tryRecoverHotkeySystem() ?: false }
        ).also { it.start() }
    }

    Thread.currentThread().join()
}

/**
 * Resolves the path to the running QTranslate JAR file.
 * Returns null if running from IDE or test runner (no JAR).
 */
private fun resolveJarPath(): File? = runCatching {
    val uri = AppDataDirectory::class.java
        .protectionDomain
        .codeSource
        .location
        .toURI()
    File(uri).takeIf { it.extension == "jar" }
}.getOrNull()