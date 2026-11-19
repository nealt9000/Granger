package app.granger.online

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.granger.GrangerApp
import app.granger.R
import app.granger.core.downloads.NetworkMode

/**
 * The Local only quick settings tile (Plan O3 Task 2; builder's call 2): on while Local only is, a tap flips it. Under
 * Never go online (O5) it stays on and can't be turned off here.
 */
class LocalOnlyTileService : TileService() {
    override fun onStartListening() = show()

    override fun onClick() {
        val policy = (application as? GrangerApp)?.container?.networkPolicy ?: return
        when (policy.now()) {
            NetworkMode.ONLINE -> policy.set(NetworkMode.LOCAL_ONLY)
            NetworkMode.LOCAL_ONLY -> policy.set(NetworkMode.ONLINE)
            NetworkMode.NEVER -> Unit
        }
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val mode = (application as? GrangerApp)?.container?.networkPolicy?.now() ?: NetworkMode.ONLINE
        tile.state = when (mode) {
            NetworkMode.ONLINE -> Tile.STATE_INACTIVE
            NetworkMode.LOCAL_ONLY -> Tile.STATE_ACTIVE
            NetworkMode.NEVER -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.local_only)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_cloud_off)
        tile.updateTile()
    }
}
