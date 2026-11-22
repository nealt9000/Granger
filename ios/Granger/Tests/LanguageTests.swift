import XCTest
@testable import Granger

/// Plan I7 Task 3: the String Catalog's words in English and Spanish as the app formats them, and the shared keys'
/// Spanish table. (That every word on screen has Spanish is make_text_ids.py --check, which this target's build runs.)
final class LanguageTests: XCTestCase {
    private let es = Locale(identifier: "es_419")
    private var spanish: Bundle { get throws { try XCTUnwrap(Bundle.main.path(forResource: "es", ofType: "lproj").flatMap(Bundle.init(path:)), "es.lproj in the app") } }

    func testWordsFromAndroidCarryItsSpanish() throws {
        let b = try spanish
        XCTAssertEqual(String(localized: "Settings", bundle: b, locale: es), "Ajustes")
        let name = "Ana"
        XCTAssertEqual(String(localized: "Read by \(name)", bundle: b, locale: es), "Narrado por Ana")
    }

    func testSeveralArgumentsKeepTheirPlaces() throws {
        let (a, b) = ("2 GB", "10 GB")
        XCTAssertEqual(String(localized: "\(a) of \(b) free on this iPhone"), "2 GB of 10 GB free on this iPhone")
        XCTAssertEqual(String(localized: "\(a) of \(b) free on this iPhone", bundle: try spanish, locale: es), "2 GB de 10 GB libres en este iPhone")
    }

    func testPluralsTakeAndroidsForms() throws {
        XCTAssertEqual(String(localized: "\(1) songs"), "1 song")
        XCTAssertEqual(String(localized: "\(3) songs"), "3 songs")
        XCTAssertEqual(String(localized: "\(1) songs", bundle: try spanish, locale: es), "1 canción")
        XCTAssertEqual(String(localized: "\(3) songs", bundle: try spanish, locale: es), "3 canciones")
    }

    func testAPluralBesideAnotherArgumentVariesTheWholeSentence() throws {
        let size = "4 MB"
        let one: Int32 = 1, five: Int32 = 5
        XCTAssertEqual(String(localized: "\(size) · \(one) items"), "4 MB · 1 item")
        XCTAssertEqual(String(localized: "\(size) · \(five) items"), "4 MB · 5 items")
        XCTAssertEqual(String(localized: "\(size) · \(one) items", bundle: try spanish, locale: es), "4 MB · 1 elemento")
        XCTAssertEqual(String(localized: "\(size) · \(five) items", bundle: try spanish, locale: es), "4 MB · 5 elementos")
    }

    func testIphoneOnlyWordsHaveSpanish() throws {
        XCTAssertEqual(String(localized: "Import from Files", bundle: try spanish, locale: es), "Importar de Archivos")
        XCTAssertEqual(String(localized: "Imported \(1) files.", bundle: try spanish, locale: es), "Se importó 1 archivo.")
    }

    func testTheSharedKeysHaveSpanish() {
        XCTAssertEqual(ScreenText.en.keys.sorted(), ScreenText.es.keys.sorted())
        XCTAssertEqual(ScreenText.enPlurals.keys.sorted(), ScreenText.esPlurals.keys.sorted())
        XCTAssertEqual(ScreenText.es["settings_language_make_main"], "Hacer principal")
        XCTAssertFalse(ScreenText.spanish, "the tests run in English")
        XCTAssertEqual(ScreenText.word("settings_language_make_main"), ScreenText.en["settings_language_make_main"])
    }
}
