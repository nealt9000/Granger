package app.granger.ebook

import android.app.Activity
import android.os.Bundle
import android.util.Log
import app.granger.core.books.PdfSupport
import app.granger.core.books.PdfToEpub
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only: converts the PDF at the `path` extra into the cache and logs each stage's time (tag GrangerPdfBench).
 * `adb shell am start -n app.granger/app.granger.ebook.PdfBenchActivity --es path /sdcard/…/book.pdf`
 */
class PdfBenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return finish()
        PdfSupport.init(this)
        thread(name = "pdf-bench") {
            val out = File(cacheDir, "bench.epub").apply { delete() }
            val start = System.currentTimeMillis()
            val report = PdfToEpub.convert(File(path), out) { stage, ms -> Log.i(TAG, "$stage: $ms ms") }
            Log.i(TAG, "done in ${System.currentTimeMillis() - start} ms: ${report.why}, ${report.pages} pages, ${report.chapters} chapters")
            out.delete()
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "GrangerPdfBench"
    }
}
