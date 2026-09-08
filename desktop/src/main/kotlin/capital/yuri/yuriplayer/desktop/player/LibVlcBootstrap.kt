package capital.yuri.yuriplayer.desktop.player

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.core.platform.HostOs
import capital.yuri.yuriplayer.core.platform.hostOs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import java.io.File

/**
 * Points JNA / LibVLC at a usable native install before
 * [uk.co.caprica.vlcj.factory.MediaPlayerFactory] loads.
 *
 * Order: `-Dyuri.libvlc.dir`, `YURI_LIBVLC`, Compose app-resources, then
 * system VLC (NixOS profiles, FHS, PATH wrappers).
 *
 * [System.setProperty] is **not** enough for plugins — LibVLC reads
 * `VLC_PLUGIN_PATH` via `getenv`, so we also `setenv`.
 */
object LibVlcBootstrap {
    private val log = yuriLog("LibVlc")

    fun install(): File? {
        val home = LibVlcLocator.find()
        if (home == null) {
            log.w { "no LibVLC directory found (NixOS: add vlc to systemPackages / home.packages, or set YURI_LIBVLC)" }
            return null
        }
        val path = home.libDir.canonicalPath
        prependJnaPath(path)
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), path)
        home.pluginDir?.let { plugins ->
            setEnv("VLC_PLUGIN_PATH", plugins.canonicalPath)
            log.i { "VLC_PLUGIN_PATH=${plugins.canonicalPath}" }
        }
        log.i { "LibVLC at $path" }
        return home.libDir
    }

    fun isNixOs(): Boolean =
        File("/etc/NIXOS").exists() ||
            !System.getenv("NIX_PATH").isNullOrBlank() ||
            !System.getenv("NIX_STORE").isNullOrBlank()

    private fun prependJnaPath(path: String) {
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) path else "$path${File.pathSeparator}$existing"
        )
    }

    private fun setEnv(name: String, value: String) {
        runCatching {
            if (hostOs() == HostOs.WINDOWS) {
                Native.load("msvcrt", WinC::class.java)._putenv("$name=$value")
            } else {
                Native.load("c", PosixC::class.java).setenv(name, value, 1)
            }
        }.onFailure { log.w(it) { "could not setenv $name" } }
    }

    private interface PosixC : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    private interface WinC : Library {
        fun _putenv(env: String): Int
    }
}
