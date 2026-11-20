package app.granger.explore

import android.content.res.AssetManager

/** [RoomDescriptions] read from the app's `explore/rooms.json` (Android; the iPhone reads its bundled copy). */
fun RoomDescriptions(assets: AssetManager): RoomDescriptions =
    RoomDescriptions { assets.open(RoomDescriptions.ASSET).bufferedReader().use { it.readText() } }
