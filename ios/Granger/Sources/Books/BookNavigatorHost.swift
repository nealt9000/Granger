import GrangerCoreKit
import ReadiumNavigator
import ReadiumShared
import SwiftUI
import UIKit

/// What the selection menu offers, in Android's order (Copy, Highlight, Note, Define, Search). Ask and Read from here
/// come with Research and the voices.
enum SelectionAction { case copy, highlight, note, define, search, readAloud }

/// Readium's navigator for SwiftUI (Plan I5 call 1: the Readium views are wrapped, not rebuilt): the EPUB navigator,
/// or the PDF navigator on PDFKit (call 2). It's built once per publication; prefs and decorations go to it as they
/// change.
struct BookNavigatorView: UIViewControllerRepresentable {
    let model: BookReaderModel
    let publication: Publication
    let prefs: ReadingPrefs
    let decorations: [Decoration]
    var links: [Decoration] = []
    /// The sentence the voice reads (Plan I5 Task 8), its own group under the highlights.
    var voice: [Decoration] = []
    let searchHit: Locator?
    let onSelection: (SelectionAction, Selection) -> Void

    func makeUIViewController(context: Context) -> BookHostController {
        BookHostController(model: model, publication: publication, prefs: prefs, onSelection: onSelection)
    }

    func updateUIViewController(_ host: BookHostController, context: Context) {
        host.onSelection = onSelection
        host.apply(prefs: prefs)
        host.apply(decorations: decorations, searchHit: searchHit)
        host.apply(links: links)
        host.apply(voice: voice)
    }
}

/// Holds the navigator as a child, is its delegate, and answers the selection menu's custom actions (they come up
/// the responder chain from the page's web view).
final class BookHostController: UIViewController, EPUBNavigatorDelegate, PDFNavigatorDelegate, UIGestureRecognizerDelegate {
    private let model: BookReaderModel
    private let publication: Publication
    private var epub: EPUBNavigatorViewController?
    private var pdf: PDFNavigatorViewController?
    private var shownPrefs: ReadingPrefs?
    private var shownDecorations: [Decoration] = []
    private var shownHit: Locator?
    private let adapter: DirectionalNavigationAdapter
    var onSelection: (SelectionAction, Selection) -> Void

    static let highlightGroup = "highlights"
    static let searchGroup = "search"
    /// The reference links' group, apart from the highlights and the search hit (Android's LINKS_GROUP).
    static let linksGroup = "links"
    /// A reference link's look (Plan LL1 Decision 7): a thin dotted underline in the book's link tone (Android's
    /// ReferenceLinkStyle), under the words only, so highlights draw over it unchanged.
    static let referenceStyle = Decoration.Style(id: "granger-reference")
    static let referenceTemplate = HTMLDecorationTemplate(
        layout: .boxes, width: .wrap,
        element: { _ in #"<div class="granger-ref" style="border-bottom-color: rgba(184, 102, 42, 1.00)"></div>"# },
        stylesheet: ".granger-ref { border-bottom: 1.5px dotted; box-sizing: border-box; pointer-events: none; }"
    )
    private var shownLinks: [Decoration] = []
    private var shownVoice: [Decoration] = []
    static let voiceGroup = "voice"
    /// The voice's sentence (Android's reading decoration, the page script's orange).
    static let voiceTint = UIColor(red: 232 / 255, green: 96 / 255, blue: 28 / 255, alpha: 1)
    /// The search hit's tint (Android's SEARCH_HIT_TINT).
    static let searchTint = UIColor(argb: Int32(bitPattern: 0xFFF28B30))

    init(model: BookReaderModel, publication: Publication, prefs: ReadingPrefs, onSelection: @escaping (SelectionAction, Selection) -> Void) {
        self.model = model
        self.publication = publication
        self.onSelection = onSelection
        adapter = DirectionalNavigationAdapter(onNavigation: { [weak model] in model?.pageTouched() })
        super.init(nibName: nil, bundle: nil)
        let navigator: UIViewController & VisualNavigator
        if model.isPdf {
            let config = PDFNavigatorViewController.Configuration(preferences: Self.pdfPreferences(prefs), editingActions: [])
            let p = try! PDFNavigatorViewController(publication: publication, initialLocation: model.initial, config: config, delegate: nil)
            p.delegate = self
            pdf = p
            navigator = p
        } else {
            let actions = [
                EditingAction(title: L("Copy"), action: #selector(grangerCopy(_:))),
                EditingAction(title: L("Highlight"), action: #selector(grangerHighlight(_:))),
                EditingAction(title: L("Note"), action: #selector(grangerNote(_:))),
                EditingAction(title: L("Define"), action: #selector(grangerDefine(_:))),
                EditingAction(title: L("Search"), action: #selector(grangerSearch(_:))),
                EditingAction(title: L("Read from here"), action: #selector(grangerReadAloud(_:))),
            ]
            var templates = HTMLDecorationTemplate.defaultTemplates()
            templates[Self.referenceStyle.id] = Self.referenceTemplate
            let config = EPUBNavigatorViewController.Configuration(
                preferences: Self.epubPreferences(prefs, fixed: model.fixedLayout), editingActions: actions, decorationTemplates: templates
            )
            let e = try! EPUBNavigatorViewController(publication: publication, initialLocation: model.initial, config: config)
            e.delegate = self
            e.observeDecorationInteractions(inGroup: Self.highlightGroup) { [weak self] event in
                guard let id = Int64(event.decoration.id) else { return }
                self?.model.model?.editHighlight(id: KotlinLong(value: id))
            }
            e.observeDecorationInteractions(inGroup: Self.linksGroup) { [weak self] event in
                self?.model.openLink(event.decoration.id)
            }
            epub = e
            navigator = e
        }
        shownPrefs = prefs
        model.navigator = navigator
        // Edge taps turn pages; any other tap shows or hides the bars (Android's middle tap).
        adapter.bind(to: navigator)
        navigator.addObserver(.tap { [weak self] _ in
            self?.model.toggleChrome()
            return true
        })
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        guard let child = (epub as UIViewController?) ?? pdf else { return }
        addChild(child)
        child.view.frame = view.bounds
        child.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(child.view)
        child.didMove(toParent: self)
        child.view.accessibilityIdentifier = "book-page"
        // A swipe across the page is a turn by hand (the edge taps come through the adapter).
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.cancelsTouchesInView = false
        pan.delegate = self
        view.addGestureRecognizer(pan)
    }

    @objc private func panned(_ g: UIPanGestureRecognizer) { model.pageTouched() }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }


    // MARK: Prefs and decorations

    func apply(prefs: ReadingPrefs) {
        guard prefs != shownPrefs else { return }
        shownPrefs = prefs
        model.prefsChanged()
        if let epub { epub.submitPreferences(Self.epubPreferences(prefs, fixed: model.fixedLayout)) }
        if let pdf { pdf.submitPreferences(Self.pdfPreferences(prefs)) }
        view.backgroundColor = Self.pageColor(prefs)
    }

    func apply(decorations: [Decoration], searchHit: Locator?) {
        guard let epub else { return }
        if decorations != shownDecorations {
            shownDecorations = decorations
            epub.apply(decorations: decorations, in: Self.highlightGroup)
        }
        if searchHit != shownHit {
            shownHit = searchHit
            epub.apply(decorations: searchHit.map { [Decoration(id: "search-hit", locator: $0, style: .highlight(tint: Self.searchTint))] } ?? [], in: Self.searchGroup)
        }
    }

    func apply(voice: [Decoration]) {
        guard let epub, voice != shownVoice else { return }
        shownVoice = voice
        epub.apply(decorations: voice, in: Self.voiceGroup)
    }

    func apply(links: [Decoration]) {
        guard let epub, links != shownLinks else { return }
        shownLinks = links
        epub.apply(decorations: links, in: Self.linksGroup)
    }

    /// Readium's EPUB preferences from the shared values (Android's `toEpub`): publisher styles off so the reader's
    /// choices win, always paginated; Night is the app's Night, Match app in Field the app's paper and ink.
    static func epubPreferences(_ p: ReadingPrefs, fixed: Bool) -> EPUBPreferences {
        let theme: ReadiumNavigator.Theme = p.pageNight ? .dark : (p.theme == .sepia ? .sepia : .light)
        let background: ReadiumNavigator.Color? = p.pageNight ? Color(hex: "#12211E") : (p.theme == .app ? Color(hex: "#F3EEE4") : nil)
        let text: ReadiumNavigator.Color? = p.pageNight ? Color(hex: "#EFE4D0") : (p.theme == .app ? Color(hex: "#1F1813") : nil)
        return EPUBPreferences(
            backgroundColor: background,
            fontFamily: p.typeface == .serif ? .serif : .sansSerif,
            fontSize: p.fontScale,
            lineHeight: p.lineHeight,
            pageMargins: p.margins,
            publisherStyles: false,
            scroll: false,
            spread: fixed ? .never : nil,
            textAlign: p.align == .justify ? .justify : .start,
            textColor: text,
            theme: theme
        )
    }

    /// Readium's PDF preferences (Android's `toPdfium`): a page turns sideways, or scrolls down; fits whole or by width.
    static func pdfPreferences(_ p: ReadingPrefs) -> PDFPreferences {
        PDFPreferences(
            fit: p.pdfFitWidth ? .width : .page,
            pageSpacing: ReadingPrefs.companion.PDF_PAGE_SPACING,
            scroll: p.pdfScroll,
            scrollAxis: p.pdfScroll ? .vertical : .horizontal
        )
    }

    /// The page's colour behind the navigator (Android's bookPageColor).
    static func pageColor(_ p: ReadingPrefs) -> UIColor {
        if p.pageNight { return UIColor(argb: Int32(bitPattern: 0xFF12211E)) }
        switch p.theme {
        case .app: return UIColor(argb: Int32(bitPattern: 0xFFF3EEE4))
        case .sepia: return UIColor(red: 0xFA / 255, green: 0xF4 / 255, blue: 0xE8 / 255, alpha: 1)
        default: return .white
        }
    }

    // MARK: The selection menu

    @objc func grangerCopy(_ sender: Any?) { act(.copy) }
    @objc func grangerHighlight(_ sender: Any?) { act(.highlight) }
    @objc func grangerNote(_ sender: Any?) { act(.note) }
    @objc func grangerDefine(_ sender: Any?) { act(.define) }
    @objc func grangerSearch(_ sender: Any?) { act(.search) }
    @objc func grangerReadAloud(_ sender: Any?) { act(.readAloud) }

    private func act(_ action: SelectionAction) {
        guard let epub, let selection = epub.currentSelection else { return }
        onSelection(action, selection)
        epub.clearSelection()
    }

    // MARK: Navigator delegate

    func navigator(_ navigator: any Navigator, locationDidChange locator: Locator) {
        model.onLocator(locator)
    }

    func navigator(_ navigator: any Navigator, presentError error: NavigatorError) {}
}
