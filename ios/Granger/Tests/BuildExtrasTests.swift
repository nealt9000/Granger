import XCTest
@testable import Granger

/// Plan I8 call 7: which extras each of the three builds has. The tests run in Debug, so the other two are checked
/// through the project's settings per configuration and through the sources: every launch hook sits behind `#if DEBUG`
/// and every testers' extra behind `#if FRIENDS`, so a build without the condition doesn't compile them.
final class BuildExtrasTests: XCTestCase {
    private let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()

    func testThisDebugBuildHasTheHooksAndTheExtras() {
        XCTAssertEqual(BuildExtras.kind, .debug)
        XCTAssertTrue(BuildExtras.launchHooks)
        XCTAssertTrue(BuildExtras.testers)
    }

    /// Debug: hooks and extras, no optimisation. Friends: extras only, optimised as Release. Release: neither.
    func testEachConfigurationHasItsConditionsAndOptimisation() throws {
        let configs = try buildConfigurations()
        let project = configs.filter { $0.settings["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] != nil }
        XCTAssertEqual(Set(project.map(\.name)), ["Debug", "Friends", "Release"], "one project-level entry each")
        func conditions(_ name: String) -> Set<String> {
            Set((project.first { $0.name == name }?.settings["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] ?? "").split(separator: " ").map(String.init))
        }
        XCTAssertEqual(conditions("Debug"), ["DEBUG", "FRIENDS"])
        XCTAssertEqual(conditions("Friends"), ["FRIENDS"])
        XCTAssertEqual(conditions("Release"), [])
        func optimisation(_ name: String) -> String? { project.first { $0.name == name }?.settings["SWIFT_OPTIMIZATION_LEVEL"] }
        XCTAssertEqual(optimisation("Debug"), "-Onone")
        XCTAssertEqual(optimisation("Friends"), "-O")
        XCTAssertEqual(optimisation("Release"), "-O")
        // No target sets its own conditions, which would override these.
        XCTAssertEqual(configs.filter { $0.settings["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] != nil }.count, 3)
    }

    /// The app and the widget say they use no non-exempt encryption in every build; only the builds with the Device
    /// check ask for the mic.
    func testInfoPlistKeysPerConfiguration() throws {
        let configs = try buildConfigurations()
        let app = configs.filter { $0.settings["PRODUCT_BUNDLE_IDENTIFIER"] == "$(GRANGER_BUNDLE_PREFIX)" }
        let widget = configs.filter { $0.settings["PRODUCT_BUNDLE_IDENTIFIER"] == "$(GRANGER_BUNDLE_PREFIX).widgets" }
        XCTAssertEqual(app.count, 3)
        XCTAssertEqual(widget.count, 3)
        for c in app + widget {
            XCTAssertEqual(c.settings["INFOPLIST_KEY_ITSAppUsesNonExemptEncryption"], "NO", c.name)
        }
        for c in app {
            let asks = c.settings["INFOPLIST_KEY_NSMicrophoneUsageDescription"] != nil
            XCTAssertEqual(asks, c.name != "Release", "\(c.name): the mic's reason only where the Device check is")
        }
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "ITSAppUsesNonExemptEncryption") as? Bool, false)
    }

    /// Every `-debug…` launch hook and `-resetData` is read inside `#if DEBUG`; every use of the Device check outside its
    /// own folder (whose files are wholly `#if FRIENDS`) is inside `#if FRIENDS`.
    func testHooksAndExtrasSitBehindTheirConditions() throws {
        let hook = try NSRegularExpression(pattern: #"forKey: "(debug[A-Za-z]*|resetData)""#)
        let extra = try NSRegularExpression(pattern: #"\b(DeviceCheckRun|DeviceCheckScreen|SpeechCheck|SpokenPhrase|MetricKitCounts)\b"#)
        var hooks = 0
        var problems: [String] = []
        for file in try swiftFiles(in: ["Sources", "Widgets"]) {
            let rel = String(file.path.dropFirst(ios.path.count + 1))
            let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n")
            let inside = Self.conditions(lines)
            for (n, line) in lines.enumerated() {
                let range = NSRange(line.startIndex..., in: line)
                if hook.firstMatch(in: line, range: range) != nil {
                    hooks += 1
                    if !inside[n].contains("DEBUG") { problems.append("\(rel):\(n + 1) launch hook outside #if DEBUG") }
                }
                let device = extra.firstMatch(in: line, range: range) != nil && !line.trimmingCharacters(in: .whitespaces).hasPrefix("//")
                if device, !inside[n].contains("FRIENDS"), !inside[n].contains("DEBUG") {
                    problems.append("\(rel):\(n + 1) Device check outside #if FRIENDS")
                }
            }
            if rel.hasPrefix("Sources/DeviceCheck/") {
                let code = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                XCTAssertEqual(code.first, "#if FRIENDS", "\(rel) is wholly in #if FRIENDS")
                XCTAssertEqual(code.last, "#endif", rel)
            }
        }
        XCTAssertGreaterThanOrEqual(hooks, 25, "the hooks were found")
        XCTAssertEqual(problems, [])
    }

    // MARK: helpers

    struct Configuration {
        let name: String
        let settings: [String: String]
    }

    /// The XCBuildConfiguration entries of the committed project, each with its settings (quotes taken off).
    private func buildConfigurations() throws -> [Configuration] {
        let text = try String(contentsOf: ios.appendingPathComponent("Granger.xcodeproj/project.pbxproj"), encoding: .utf8)
        let block = try NSRegularExpression(pattern: #"isa = XCBuildConfiguration;.*?buildSettings = \{(.*?)\n\t\t\t\};\s*name = (\w+);"#,
                                            options: .dotMatchesLineSeparators)
        let setting = try NSRegularExpression(pattern: #"^\s*"?([A-Za-z_]+)"? = (.*);$"#, options: .anchorsMatchLines)
        return block.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { m in
            let body = String(text[Range(m.range(at: 1), in: text)!])
            var settings: [String: String] = [:]
            for s in setting.matches(in: body, range: NSRange(body.startIndex..., in: body)) {
                var value = String(body[Range(s.range(at: 2), in: body)!])
                if value.hasPrefix("\""), value.hasSuffix("\""), value.count >= 2 { value = String(value.dropFirst().dropLast()) }
                settings[String(body[Range(s.range(at: 1), in: body)!])] = value
            }
            return Configuration(name: String(text[Range(m.range(at: 2), in: text)!]), settings: settings)
        }
    }

    private func swiftFiles(in folders: [String]) throws -> [URL] {
        folders.flatMap { folder -> [URL] in
            let root = ios.appendingPathComponent(folder)
            let all = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? []
            return all.filter { $0.pathExtension == "swift" }
        }
    }

    /// For each line, the conditions it's compiled under: `#if X` adds X, `#else` / `#elseif` swap it for "not X", and
    /// `#endif` ends it. Only plain `#if NAME` is understood, which is all the app uses.
    static func conditions(_ lines: [String]) -> [Set<String>] {
        var stack: [String] = []
        var out: [Set<String>] = []
        for line in lines {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("#if ") {
                stack.append(String(t.dropFirst(4)).trimmingCharacters(in: .whitespaces))
            } else if t.hasPrefix("#elseif") || t == "#else" {
                if !stack.isEmpty { stack[stack.count - 1] = "!" + stack[stack.count - 1] }
            } else if t == "#endif" {
                _ = stack.popLast()
            }
            out.append(Set(stack))
        }
        return out
    }
}
