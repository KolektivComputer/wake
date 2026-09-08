package capital.yuri.yuriplayer.desktop.player

import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider

/**
 * vlcj ServiceLoader hook. Default Linux providers only search FHS paths;
 * this one feeds NixOS / Guix / PATH-resolved VLC lib dirs in first.
 */
class NixLibVlcDirectoryProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = 1_000

    override fun supported(): Boolean = true

    override fun directories(): Array<String> {
        val home = LibVlcLocator.find() ?: return emptyArray()
        return arrayOf(home.libDir.absolutePath)
    }
}
