package capital.yuri.yuriplayer.desktop.player

import java.io.File

/**
 * Find a directory that actually contains LibVLC + LibVLCCore.
 *
 * vlcj's default Linux search is FHS-only (`/usr/lib`, `/usr/lib64`, …). NixOS,
 * Guix, and a lot of IDE/Gradle launches never put those on disk — VLC lives
 * under `/nix/store` and is exposed through `/run/current-system/sw` or a
 * profile. IntelliJ often has a stripped PATH, so we look at those locations
 * even when `which vlc` would fail.
 */
internal object LibVlcLocator {

    data class Home(
        val libDir: File,
        val pluginDir: File?
    )

    fun find(): Home? {
        val homes = explicitHomes() + systemLibDirs().mapNotNull { homeIfLibDir(it) }
        return homes.firstOrNull()
    }

    internal fun looksLikeLibDir(names: Collection<String>): Boolean {
        val hasVlc = names.any { LIBVLC_NAME.matches(it) }
        val hasCore = names.any { LIBVLCCORE_NAME.matches(it) }
        return hasVlc && hasCore
    }

    internal fun pluginDir(libDir: File): File? = sequenceOf(
        File(libDir, "vlc/plugins"),
        File(libDir, "plugins")
    ).firstOrNull { it.isDirectory }

    /**
     * Nix `wrapProgram` scripts export `VLC_PLUGIN_PATH` and exec a store
     * binary. Pull the real lib dir out of that so JNA can load `libvlc.so`.
     */
    internal fun libDirFromWrapper(text: String): File? {
        pluginPathFromWrapper(text)?.let { plugin ->
            val pluginFile = File(plugin)
            homeIfLibDir(pluginFile.parentFile?.parentFile)?.libDir?.let { return it }
            homeIfLibDir(pluginFile.parentFile)?.libDir?.let { return it }
        }
        ldLibraryDirs(text).forEach { dir ->
            homeIfLibDir(dir)?.libDir?.let { return it }
        }
        wrappedBinary(text)?.let { bin ->
            val prefix = bin.parentFile?.parentFile
            homeIfLibDir(prefix?.let { File(it, "lib") })?.libDir?.let { return it }
            homeIfLibDir(bin.parentFile)?.libDir?.let { return it }
        }
        return null
    }

    internal fun pluginPathFromWrapper(text: String): String? =
        ENV_ASSIGN.find(text, "VLC_PLUGIN_PATH")

    private fun explicitHomes(): List<Home> = listOfNotNull(
        System.getProperty("yuri.libvlc.dir"),
        System.getenv("YURI_LIBVLC"),
        System.getProperty("compose.application.resources.dir")
    ).map(::File).mapNotNull { homeIfLibDir(it) }

    private fun systemLibDirs(): List<File> {
        val out = ArrayList<File>()
        fun add(file: File?) {
            if (file != null) out += file
        }

        pathDirs().forEach { dir ->
            VLC_BINARIES.forEach { name ->
                val bin = File(dir, name)
                if (bin.isFile) locateFromBinary(bin)?.let { add(it) }
            }
        }

        wellKnownBins().forEach { bin ->
            if (bin.isFile) locateFromBinary(bin)?.let { add(it) }
        }

        wellKnownLibDirs().forEach(::add)

        System.getenv("LD_LIBRARY_PATH")
            ?.split(File.pathSeparator)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { add(File(it)) }

        nixProfileLibs().forEach(::add)
        return out.distinctBy { it.absolutePath }
    }

    private fun locateFromBinary(bin: File): File? {
        runCatching { bin.readText() }.getOrNull()
            ?.takeIf { looksLikeWrapper(it) }
            ?.let { libDirFromWrapper(it) }
            ?.let { return it }

        val real = runCatching { bin.canonicalFile }.getOrDefault(bin)
        val parent = real.parentFile ?: return null
        homeIfLibDir(parent)?.libDir?.let { return it }
        val prefix = parent.parentFile ?: return null
        return homeIfLibDir(File(prefix, "lib"))?.libDir
            ?: homeIfLibDir(File(prefix, "lib64"))?.libDir
    }

    private fun homeIfLibDir(dir: File?): Home? {
        if (dir == null || !dir.isDirectory) return null
        val names = dir.list()?.toList().orEmpty()
        if (!looksLikeLibDir(names)) return null
        return Home(dir, pluginDir(dir))
    }

    private fun wellKnownBins(): List<File> {
        val home = System.getProperty("user.home") ?: "."
        return listOf(
            File("/run/current-system/sw/bin/vlc"),
            File("/run/current-system/profile/bin/vlc"),
            File(home, ".nix-profile/bin/vlc"),
            File(home, ".guix-profile/bin/vlc"),
            File("/usr/bin/vlc"),
            File("/usr/local/bin/vlc"),
            File("/opt/homebrew/bin/vlc"),
            File("/usr/local/opt/vlc/bin/vlc")
        )
    }

    private fun wellKnownLibDirs(): List<File> {
        val home = System.getProperty("user.home") ?: "."
        return listOf(
            File("/run/current-system/sw/lib"),
            File("/run/current-system/profile/lib"),
            File(home, ".nix-profile/lib"),
            File(home, ".guix-profile/lib"),
            File("/usr/lib/x86_64-linux-gnu"),
            File("/usr/lib/aarch64-linux-gnu"),
            File("/usr/lib64"),
            File("/usr/local/lib64"),
            File("/usr/lib"),
            File("/usr/local/lib"),
            File("/opt/homebrew/lib"),
            File("/usr/local/opt/vlc/lib")
        )
    }

    private fun nixProfileLibs(): List<File> {
        val profiles = System.getenv("NIX_PROFILES") ?: return emptyList()
        return profiles.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it, "lib") }
    }

    private fun pathDirs(): List<File> {
        val path = System.getenv("PATH") ?: return emptyList()
        return path.split(File.pathSeparator).map { File(it) }
    }

    private fun looksLikeWrapper(text: String): Boolean {
        val head = text.take(200)
        return head.startsWith("#!") ||
            text.contains("VLC_PLUGIN_PATH") ||
            text.contains(".vlc-wrapped")
    }

    private fun ldLibraryDirs(text: String): List<File> {
        val raw = ENV_ASSIGN.find(text, "LD_LIBRARY_PATH") ?: return emptyList()
        return raw.split(File.pathSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::File)
    }

    private fun wrappedBinary(text: String): File? {
        val match = WRAPPED_BIN.find(text) ?: return null
        return File(match.groupValues[1])
    }

    private val LIBVLC_NAME = Regex("""libvlc(\.so(\.\d+)*|\.dylib|\.dll)""", RegexOption.IGNORE_CASE)
    private val LIBVLCCORE_NAME = Regex("""libvlccore(\.so(\.\d+)*|\.dylib|\.dll)""", RegexOption.IGNORE_CASE)
    private val WRAPPED_BIN = Regex("""(/nix/store/[^'"\s]+/bin/[^'"\s]+)""")

    private val VLC_BINARIES = listOf("vlc", "cvlc", "vlc.exe")
}

private object ENV_ASSIGN {
    fun find(text: String, name: String): String? {
        val quoted = Regex("""(?:export\s+)?$name=(['"])(.*?)\1""").find(text)
        if (quoted != null) return quoted.groupValues[2].trim()
        val bare = Regex("""(?:export\s+)?$name=([^\s]+)""").find(text)
        return bare?.groupValues?.get(1)?.trim()?.trim('\'', '"')
    }
}
