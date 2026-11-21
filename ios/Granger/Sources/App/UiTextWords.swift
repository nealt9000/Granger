import Foundation
import GrangerCoreKit

/// The shared models' text (UiText) in words: a key's English template from ScreenText with Android's `%1$s` / `%1$d`
/// placeholders filled (nested UiText resolved first), a plural by English's one/other, or raw text. The String Catalog
/// for Spanish comes with the languages plan (I7).
extension UiText {
    var words: String {
        switch onEnum(of: self) {
        case let .raw(r): return r.text
        case let .res(r): return UiTextWords.fill(ScreenText.strings[r.key] ?? r.key, r.args)
        case let .plural(p):
            let forms = ScreenText.plurals[p.key] ?? [:]
            let template = (p.count == 1 ? forms["one"] : nil) ?? forms["other"] ?? p.key
            return UiTextWords.fill(template, p.args)
        }
    }
}

enum UiTextWords {
    /// [template] with each `%N$s`, `%N$d`, `%s` or `%d` replaced by the matching argument.
    static func fill(_ template: String, _ args: [Any]) -> String {
        let values = args.map(word)
        var out = ""
        var i = template.startIndex
        var next = 0
        while i < template.endIndex {
            let c = template[i]
            if c == "%", let spec = placeholder(template, at: i) {
                let index = spec.index ?? next
                next = index + 1
                out += index < values.count ? values[index] : ""
                i = spec.end
            } else {
                out.append(c)
                i = template.index(after: i)
            }
        }
        return out.replacingOccurrences(of: "%%", with: "%")
    }

    private static func placeholder(_ s: String, at i: String.Index) -> (index: Int?, end: String.Index)? {
        var j = s.index(after: i)
        var digits = ""
        while j < s.endIndex, s[j].isNumber { digits.append(s[j]); j = s.index(after: j) }
        var index: Int?
        if j < s.endIndex, s[j] == "$", let n = Int(digits) { index = n - 1; j = s.index(after: j) } else if !digits.isEmpty { return nil }
        // An optional precision (%.1f) is taken as it comes: the shared models format numbers themselves.
        while j < s.endIndex, s[j] == "." || s[j].isNumber { j = s.index(after: j) }
        guard j < s.endIndex, "sdf".contains(s[j]) else { return nil }
        return (index, s.index(after: j))
    }

    private static func word(_ a: Any) -> String {
        if let t = a as? UiText { return t.words }
        if let n = a as? NSNumber { return "\(n)" }
        return "\(a)"
    }
}
