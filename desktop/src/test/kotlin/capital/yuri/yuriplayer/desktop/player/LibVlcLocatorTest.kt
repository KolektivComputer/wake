package capital.yuri.yuriplayer.desktop.player

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibVlcLocatorTest {

    @Test
    fun recognizesNixAndFhsLibraryNames() {
        assertTrue(
            LibVlcLocator.looksLikeLibDir(listOf("libvlc.so.5", "libvlccore.so.9", "vlc"))
        )
        assertTrue(
            LibVlcLocator.looksLikeLibDir(listOf("libvlc.dll", "libvlccore.dll", "plugins"))
        )
        assertTrue(
            LibVlcLocator.looksLikeLibDir(listOf("libvlc.dylib", "libvlccore.dylib"))
        )
        assertFalse(LibVlcLocator.looksLikeLibDir(listOf("libvlc.so.5")))
    }

    @Test
    fun parsesNixWrapProgramScript() {
        val text = """
            #! /nix/store/aaaa-bash-5.2/bin/bash -e
            export PATH='/nix/store/bbbb-coreutils/bin'
            export VLC_PLUGIN_PATH='/nix/store/cccc-vlc-3.0.21/lib/vlc/plugins'
            export LD_LIBRARY_PATH='/nix/store/cccc-vlc-3.0.21/lib:/nix/store/dddd-lib/lib'
            exec -a "$0" /nix/store/cccc-vlc-3.0.21/bin/.vlc-wrapped "$@"
        """.trimIndent()
        val plugin = LibVlcLocator.pluginPathFromWrapper(text)
        assertEquals("/nix/store/cccc-vlc-3.0.21/lib/vlc/plugins", plugin)
    }

    @Test
    fun libDirFromWrapperResolvesExistingLayout() {
        val root = createTempDirectory("vlc-nix").toFile()
        try {
            val lib = File(root, "lib").apply { mkdirs() }
            File(lib, "libvlc.so.5").writeText("")
            File(lib, "libvlccore.so.9").writeText("")
            File(lib, "vlc/plugins").mkdirs()
            val text = """
                #! /nix/store/aaaa-bash/bin/bash -e
                export VLC_PLUGIN_PATH='${File(lib, "vlc/plugins").absolutePath}'
                export LD_LIBRARY_PATH='${lib.absolutePath}'
                exec -a "${'$'}0" ${File(root, "bin/.vlc-wrapped").absolutePath}
            """.trimIndent()
            val found = LibVlcLocator.libDirFromWrapper(text)
            assertEquals(lib.canonicalFile, found?.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun describeNativeFailureMentionsOverride() {
        val msg = VlcjPlaybackEngine.describeNativeFailure(
            "Failed to properly initialise the native library",
            discovered = false
        )
        assertTrue(msg.contains("YURI_LIBVLC"))
        assertTrue(msg.contains("Failed to properly initialise the native library"))
    }
}
