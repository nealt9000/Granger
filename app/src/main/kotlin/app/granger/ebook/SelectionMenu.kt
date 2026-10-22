package app.granger.ebook

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import app.granger.Features

/** What the text-selection menu offers, in this order. */
enum class SelectionAction(val label: String) {
    COPY("Copy"),
    HIGHLIGHT("Highlight"),
    NOTE("Note"),
    DEFINE("Define"),
    SEARCH("Search"),
    RESEARCH("Research"),
    READ_ALOUD("Read from here"),
}

/** A menu tap, with the menu to close once the screen has read the selection and acted. */
class SelectionRequest(val action: SelectionAction, val mode: ActionMode?)

/**
 * What the selection menu reaches without touching any composition: the navigator's configuration (and so the menu)
 * outlives the screen that built it, through rotation. The current reader's view model sets [sink]; the app keeps
 * [dictionaryAvailable] and [researchAvailable] current.
 */
object SelectionBridge {
    /** Where taps go; the current reader's. A menu from an older reader still reaches the newest. */
    @Volatile var sink: ((SelectionRequest) -> Unit)? = null

    /** Whether Define can do anything now; read each time the menu is shown. */
    @Volatile var dictionaryAvailable: Boolean = false

    /** Whether this phone can run Research (its tier is ASK or better); both readers' menus read it. */
    @Volatile var researchAvailable: Boolean = false

    /** Whether the selection menus offer Research now. */
    val offersResearch: Boolean get() = Features.RESEARCH && researchAvailable

    /** Whether the selection menus offer Read from here (Plan V1); the app sets it from the feature flag. */
    @Volatile var readAloudAvailable: Boolean = false

    fun dispatch(request: SelectionRequest) {
        sink?.invoke(request)
    }
}

/**
 * Replaces the system's selection menu in the navigator. A tap goes to [SelectionBridge]; the screen reads the
 * navigator's selection, acts, clears it and finishes the menu. Define is always added and shown or hidden each
 * time the menu is prepared, and so is Research ([SelectionBridge.offersResearch]). Holds nothing tied to an activity.
 */
class SelectionMenu : ActionMode.Callback {
    private val actions = SelectionAction.entries.toList()

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        actions.forEachIndexed { i, action ->
            menu.add(Menu.NONE, MENU_ID_BASE + i, i, action.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val define = actions.indexOf(SelectionAction.DEFINE)
        menu.findItem(MENU_ID_BASE + define)?.isVisible = SelectionBridge.dictionaryAvailable
        val research = actions.indexOf(SelectionAction.RESEARCH)
        menu.findItem(MENU_ID_BASE + research)?.isVisible = SelectionBridge.offersResearch
        val readAloud = actions.indexOf(SelectionAction.READ_ALOUD)
        menu.findItem(MENU_ID_BASE + readAloud)?.isVisible = SelectionBridge.readAloudAvailable
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = actions.getOrNull(item.itemId - MENU_ID_BASE) ?: return false
        // Not finished here: the screen reads the selection first, then clears it and finishes the menu.
        SelectionBridge.dispatch(SelectionRequest(action, mode))
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private companion object {
        const val MENU_ID_BASE = 0x6773
    }
}
