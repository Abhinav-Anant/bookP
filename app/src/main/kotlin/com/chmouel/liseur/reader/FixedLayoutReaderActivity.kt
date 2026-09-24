package com.chmouel.liseur.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.sync.PositionUpdate
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.toAbsoluteUrl

/**
 * The reader for PDFs and CBZ comics.
 *
 * Liseur's [ReaderActivity] is built around the reflowable EPUB navigator
 * (typography, footnotes, sentence-exact positions), none of which a
 * fixed page has. This one hosts Readium's PDF or image navigator and
 * keeps only what a page-based book needs: resume where you were, and
 * publish every page turn through the same [com.chmouel.liseur.sync.ReadingPositionPublisher]
 * the EPUB reader uses, so positions sync across devices.
 *
 * ponytail: no highlights, search or reading-session statistics for fixed
 * layouts yet; add them here when PDFs need them.
 */
@OptIn(ExperimentalReadiumApi::class)
class FixedLayoutReaderActivity : FragmentActivity() {

    private lateinit var bookId: String
    private var publication: Publication? = null
    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // Like ReaderActivity: nothing is restored before the book is open,
        // because the navigator fragment cannot be rebuilt without it.
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL)?.toUri()?.toAbsoluteUrl()
        bookId = intent.getStringExtra(EXTRA_ID) ?: url?.toString().orEmpty()
        if (url == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val host = FrameLayout(this).apply { id = View.generateViewId() }
        val spinner = ProgressBar(this)
        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(24, 12, 24, 24)
        }
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        root.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(pageLabel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        immersive()

        val container = container
        lifecycleScope.launch {
            val asset = container.assetRetriever.retrieve(url).getOrNull()
            val opened = asset?.let {
                container.publicationOpener.open(it, allowUserInteraction = false).getOrNull()
            }
            if (opened == null) {
                Toast.makeText(this@FixedLayoutReaderActivity, R.string.reader_open_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            publication = opened
            container.libraryRepository.markOpened(bookId)
            val initial = container.database.readingProgressDao().get(bookId)?.locatorJson
                ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

            supportFragmentManager.fragmentFactory = navigatorFactory(opened, initial)
            val fragmentClass = if (opened.conformsTo(Publication.Profile.PDF)) {
                PDF_FRAGMENT
            } else {
                ImageNavigatorFragment::class.java
            }
            supportFragmentManager.beginTransaction()
                .replace(host.id, fragmentClass, null, NAVIGATOR_TAG)
                .commitNow()
            spinner.visibility = View.GONE
            observePosition(opened)
        }
    }

    private fun navigatorFactory(publication: Publication, initial: Locator?): FragmentFactory =
        if (publication.conformsTo(Publication.Profile.PDF)) {
            PdfNavigatorFactory(publication, PdfiumEngineProvider())
                .createFragmentFactory(initialLocator = initial)
        } else {
            ImageNavigatorFragment.createFactory(publication, initial)
        }

    private fun observePosition(publication: Publication) {
        val navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? Navigator ?: return
        val pages = publication.readingOrder.size.takeIf { it > 0 }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    val position = locator.locations.position
                    val total = locator.locations.totalProgression
                    pageLabel.text = when {
                        position != null && pages != null && pages > 1 -> "$position / $pages"
                        total != null -> "${(total * 100).toInt()}%"
                        else -> ""
                    }
                    container.readingPositions.publish(
                        PositionUpdate(
                            bookUrl = bookId,
                            locatorJson = locator.toJSON().toString(),
                            progression = total,
                            readingSecondsPerPosition = null,
                            readingPaceSamples = null,
                            readingPaceElapsedMs = null,
                            readingPaceEvidence = null,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (publication != null) container.readingPositions.closeBook(bookId)
    }

    override fun onDestroy() {
        publication?.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_ID = "id"
        private const val NAVIGATOR_TAG = "navigator"

        @Suppress("UNCHECKED_CAST")
        private val PDF_FRAGMENT =
            org.readium.r2.navigator.pdf.PdfNavigatorFragment::class.java as Class<androidx.fragment.app.Fragment>

        fun intent(context: Context, url: String, id: String): Intent =
            Intent(context, FixedLayoutReaderActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ID, id)
    }
}
