package app.granger.core.player

import androidx.media3.common.MediaItem

/**
 * The browse tree the session serves to Android Auto (and any other media browser), implemented by the app. Its items
 * carry ids, titles and artwork only, never a playable URI: playing one goes through [play], which starts it through
 * the [PlayerHub], so the engine stays the only thing that loads the player.
 */
interface MediaBrowseTree {
    fun root(): MediaItem

    /** Null for an unknown id. [page] and [pageSize] as Auto asks. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>?

    suspend fun item(id: String): MediaItem?

    suspend fun search(query: String): List<MediaItem>

    /** Starts what [id] names through the hub (an empty id with [searchQuery] plays the best hit). False if nothing plays. */
    suspend fun play(id: String, searchQuery: String? = null): Boolean

    /**
     * Appends what [id] names (a song, or an album's, artist's or playlist's playable songs) to the music queue
     * through the hub; an audiobook or a recent play, which can't be queued, is played instead. False if nothing.
     */
    suspend fun add(id: String): Boolean
}

/** A tree made on first use, so the app can hand the hub a tree that itself needs the hub. */
internal class LazyBrowseTree(provider: () -> MediaBrowseTree) : MediaBrowseTree {
    private val tree by lazy(provider)
    override fun root() = tree.root()
    override suspend fun children(parentId: String, page: Int, pageSize: Int) = tree.children(parentId, page, pageSize)
    override suspend fun item(id: String) = tree.item(id)
    override suspend fun search(query: String) = tree.search(query)
    override suspend fun play(id: String, searchQuery: String?) = tree.play(id, searchQuery)
    override suspend fun add(id: String) = tree.add(id)
}
