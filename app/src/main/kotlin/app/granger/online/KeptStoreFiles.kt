package app.granger.online

import app.granger.core.library.KeptArticleDao
import java.io.File
import kotlinx.coroutines.CoroutineScope

/** [KeptStore] in [dir] (Android passes a File, as before the store was shared). */
fun KeptStore(dir: File, dao: KeptArticleDao, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis): KeptStore =
    KeptStore(dir.path, dao, scope, clock)
