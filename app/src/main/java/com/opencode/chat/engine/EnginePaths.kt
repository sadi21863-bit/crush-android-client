package com.opencode.chat.engine

import android.content.Context
import java.io.File

/**
 * Every filesystem location the engine touches, resolved once.
 *
 * Crush reads XDG vars, but the CRUSH_* vars take priority and are checked
 * first, so we set those. All of it is redirected under the app's private
 * files dir: the engine is spawned by our UID and has no sandbox of its own,
 * so an unset XDG var would let it write outside our directory.
 */
class EnginePaths(
    context: Context,
    /**
     * Engine idle linger, from static auto-tuning or a user override.
     *
     * Crush shuts itself down once it has hosted no workspace for 60s
     * (internal/backend.DefaultIdleShutdownDelay), which on a phone kills the
     * engine while the user reads the previous reply and invalidates every
     * held workspace id. The linger also cancels itself as soon as a workspace
     * is created, so during real use it never fires.
     *
     * Mutable so a settings change reaches the next engine start without
     * needing a process restart. The value is read from env() at launch, so a
     * change applies to the NEXT start, not to an already-running engine.
     */
    @Volatile var idleTimeoutSec: Int = 3600
) {

    private val filesDir: File = context.filesDir

    /** Extracted engine binary. Must be lib*.so to be extracted at install. */
    val binary: File =
        File(context.applicationInfo.nativeLibraryDir, "libcrush.so")

    val workspace: File = File(filesDir, "workspace").apply { mkdirs() }
    val dataDir: File = File(filesDir, "crush/state").apply { mkdirs() }
    val globalConfig: File = File(filesDir, "crush/cfg").apply { mkdirs() }
    val globalData: File = File(filesDir, "crush/data").apply { mkdirs() }
    val cache: File = File(filesDir, "crush/cache").apply { mkdirs() }
    val skills: File = File(filesDir, "crush/skills").apply { mkdirs() }
    val tmp: File = File(filesDir, "crush/tmp").apply { mkdirs() }

    val isBinaryUsable: Boolean get() = binary.exists() && binary.canExecute()

    /**
     * Environment for the engine process.
     *
     * Note: Crush copies every CRUSH_-prefixed var onto its unprefixed name
     * during provider configuration. So a var named CRUSH_FOO clobbers FOO.
     * Nothing we set collides, but do not add names carelessly.
     */
    fun env(): MutableMap<String, String> = System.getenv().toMutableMap().apply {
        this["CRUSH_GLOBAL_CONFIG"] = globalConfig.absolutePath
        this["CRUSH_GLOBAL_DATA"] = globalData.absolutePath
        this["CRUSH_CACHE_DIR"] = cache.absolutePath
        this["CRUSH_SKILLS_DIR"] = skills.absolutePath
        this["HOME"] = filesDir.absolutePath
        this["TMPDIR"] = tmp.absolutePath
        this["TMP"] = tmp.absolutePath
        this["TEMP"] = tmp.absolutePath

        // Android has almost no PATH, and Go's os/user parses /etc/passuid which
        // does not exist. Crush degrades gracefully without rg/git/gh.
        this["PATH"] = "/system/bin"
        this["USER"] = "app"
        this["LOGNAME"] = "app"

        // Crush shuts itself down once it has hosted no workspace for 60s
        // (internal/backend.DefaultIdleShutdownDelay). On a phone that just
        // means the engine dies while the user is reading the previous reply,
        // the port changes, and every in-flight workspace id goes stale.
        // Value comes from static auto-tuning (see TuningPolicy).
        this["CRUSH_SERVER_IDLE_TIMEOUT"] = idleTimeoutSec.toString()
    }

    /** Engine log path. Crush writes here when stderr is not a tty, as on Android. */
    fun logFile(): File =
        File(cache, "server-tcp--127.0.0.1/crush.log")
}