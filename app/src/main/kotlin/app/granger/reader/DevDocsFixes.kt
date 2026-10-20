package app.granger.reader

/**
 * DevDocs packs (`devdocs_*`) lay their page out beside a fixed-width nav, `<devdocs-navbar>`, which has no rule for
 * narrow screens: in portrait it takes half the page. Below [NARROW_PX] of CSS width the nav is hidden and the page
 * gets a "☰ Contents" button that opens it as a drawer over the page. A link loads a new page, which closes it.
 * The navbar renders without a shadow DOM, so the pack's own `._sidebar` and `._container` can be styled from outside.
 */
object DevDocsFixes {
    const val NARROW_PX = 720
    private const val MARKER = "granger-devdocs-fix"
    private const val NAVBAR = "<devdocs-navbar"

    private val STYLE = """
        <style id="$MARKER">
        .granger-nav { display: none; }
        @media (max-width: ${NARROW_PX}px) {
          devdocs-navbar ._sidebar { display: none !important; }
          ._container { margin-left: 0 !important; }
          .granger-nav {
            display: block; position: fixed; top: 8px; left: 8px; z-index: 1001;
            padding: 6px 10px; border: 0; border-radius: 6px; font: inherit; font-size: 14px;
            background: rgba(0, 0, 0, 0.55); color: #fff;
          }
          html.granger-nav-open devdocs-navbar ._sidebar {
            display: block !important; position: fixed !important; top: 0; bottom: 0; left: 0;
            width: 85vw; max-width: 360px; z-index: 1000; overflow-y: auto;
            padding-top: 48px; background: var(--sidebarBackground, #f3f3f3);
            box-shadow: 2px 0 12px rgba(0, 0, 0, 0.3);
          }
        }
        </style>
    """.trimIndent()

    // The drawer closes on a tap outside it; taps inside it don't reach the reader's middle-tap toggle.
    private val SCRIPT = """
        <script>
        document.addEventListener('DOMContentLoaded', function () {
          var root = document.documentElement;
          var button = document.createElement('button');
          button.className = 'granger-nav';
          button.type = 'button';
          button.textContent = '☰ Contents';
          button.addEventListener('click', function (e) { e.stopPropagation(); root.classList.toggle('granger-nav-open'); });
          document.body.appendChild(button);
          document.addEventListener('click', function (e) {
            if (!root.classList.contains('granger-nav-open')) return;
            if (e.target.closest && e.target.closest('devdocs-navbar ._sidebar')) { e.stopPropagation(); return; }
            if (e.target !== button) { root.classList.remove('granger-nav-open'); e.stopPropagation(); }
          }, true);
        });
        </script>
    """.trimIndent()

    private val HEAD_END = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val BODY_START = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** Adds the drawer to a DevDocs page; any other page, or one already patched, comes back as [html]. */
    fun patch(html: String): String {
        if (!html.contains(NAVBAR) || html.contains(MARKER)) return html
        val fix = STYLE + SCRIPT
        HEAD_END.find(html)?.let { return html.substring(0, it.range.first) + fix + html.substring(it.range.first) }
        BODY_START.find(html)?.let { return html.substring(0, it.range.last + 1) + fix + html.substring(it.range.last + 1) }
        return fix + html
    }
}
