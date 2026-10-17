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
    INQUIRE("Inquire & Abstract"),
}

/**
 * Replaces the system's selection menu in the navigator. The menu only reports the tap through [onAction]; the
 * screen reads the navigator's selection, acts, and clears it. DEFINE shows only with [dictionaryAvailable], and
 * INQUIRE only with [Features.INQUIRE]. Holds nothing tied to an activity.
 */
class SelectionMenu(
    private val onAction: (SelectionAction) -> Unit,
    private val dictionaryAvailable: Boolean,
) : ActionMode.Callback {
    private val actions = SelectionAction.entries.filter {
        when (it) {
            SelectionAction.DEFINE -> dictionaryAvailable
            SelectionAction.INQUIRE -> Features.INQUIRE
            else -> true
        }
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        actions.forEachIndexed { i, action ->
            menu.add(Menu.NONE, MENU_ID_BASE + i, i, action.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = actions.getOrNull(item.itemId - MENU_ID_BASE) ?: return false
        // Not finished here: the screen reads the selection first, then clears it, which closes the menu.
        onAction(action)
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private companion object {
        const val MENU_ID_BASE = 0x6773
    }
}
