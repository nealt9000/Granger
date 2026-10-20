package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** How many rail items can be pinned at once. */
const val MAX_RAIL_PINS = 6

enum class PinResult { PINNED, ALREADY_PINNED, FULL }

/**
 * Rail items pinned to the top of the rail's list, by kind and key (see [RailPin.key]), so a pin outlives its item
 * being pushed off the list by newer ones. The rail drops a pin whose item is gone with [dropMissing].
 */
interface RailPins {
    /** Newest pin first. */
    val pins: Flow<List<RailPin>>

    /** Pins [key] on top; refused with [PinResult.FULL] while [MAX_RAIL_PINS] are pinned. */
    suspend fun pin(key: String): PinResult

    suspend fun unpin(key: String)

    /** Deletes the pins for [keys] (items that no longer exist); other pins stay. */
    suspend fun dropMissing(keys: Collection<String>)
}

class RoomRailPins(
    private val db: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : RailPins {
    private val dao = db.sessionDao()

    override val pins: Flow<List<RailPin>> = dao.observePins()

    override suspend fun pin(key: String): PinResult = db.withTransaction {
        when {
            dao.isPinned(key) -> PinResult.ALREADY_PINNED
            dao.pinCount() >= MAX_RAIL_PINS -> PinResult.FULL
            else -> {
                dao.insertPin(RailPin(key, now()))
                PinResult.PINNED
            }
        }
    }

    override suspend fun unpin(key: String) = dao.deletePins(listOf(key))

    override suspend fun dropMissing(keys: Collection<String>) {
        if (keys.isNotEmpty()) dao.deletePins(keys)
    }
}
