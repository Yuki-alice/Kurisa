// kurisa-webview-host — macOS / WKWebView, proto=1, NDJSON over stdio.
import AppKit
import WebKit
import Foundation
let PROTO = 1
func jesc(_ s: String) -> String {
    var o = "\""
    for b in s.utf8 {
        if b == 34 { o += "\\\"" } else if b == 92 { o += "\\\\" }
        else if b == 10 { o += "\\n" } else if b == 13 { o += "\\r" }
        else if b == 9 { o += "\\t" }
        else if b < 0x20 { o += String(format: "\\u%04x", b) } else { o += String(UnicodeScalar(b)) }
    }
    o += "\""
    return o
}
func emit(_ s: String) { print(s); fflush(stdout) }
func parseCmd(_ line: String) -> [String: Any]? {
    guard let d = line.data(using: .utf8) else { return nil }
    return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
}
func strVal(_ m: [String: Any], _ k: String) -> String? { m[k] as? String }
func intVal(_ m: [String: Any], _ k: String, _ fb: Int) -> Int {
    if let n = m[k] as? Int { return n }
    if let n = m[k] as? NSNumber { return n.intValue }
    return fb
}
final class Host: NSObject, WKNavigationDelegate {
    var window: NSWindow!
    var view: WKWebView!
    var ua: String = ""
    override init() {
        super.init()
        let ds = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let base = ProcessInfo.processInfo.environment["KURISA_WEBVIEW_DATA"] ?? ds.appendingPathComponent("kurisa/webview").path
        let profile = ProcessInfo.processInfo.environment["KURISA_WEBVIEW_PROFILE"] ?? "default"
        let dir = (base as NSString).appendingPathComponent(profile)
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        view = WKWebView(frame: NSRect(x: 0, y: 0, width: 1000, height: 800), configuration: config)
        view.navigationDelegate = self
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 800), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.contentView = view
        window.title = "Kurisa"
        if let s = view.customUserAgent { ua = s }
    }
    func webView(_ w: WKWebView, didFinish n: WKNavigation!) {
        let u = jesc(w.url?.absoluteString ?? "")
        let t = jesc(w.title ?? "")
        emit("{\"ev\":\"loaded\",\"url\":" + u + ",\"title\":" + t + "}")
    }
    func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) {
        let u = jesc(w.url?.absoluteString ?? "")
        let m = jesc(e.localizedDescription)
        emit("{\"ev\":\"load-failed\",\"url\":" + u + ",\"error\":" + m + "}")
    }
}
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let host = Host()
host.view.evaluateJavaScript("navigator.userAgent") { r, _ in
    if let s = r as? String, !s.isEmpty { host.ua = s }
}
DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
    if host.ua.isEmpty { host.ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15" }
    emit("{\"ev\":\"ready\",\"proto\":" + String(PROTO) + ",\"ua\":" + jesc(host.ua) + ",\"engine\":" + jesc("WKWebView") + "}")
}
func onMain(_ f: @escaping () -> Void) { DispatchQueue.main.async(execute: f) }
Thread.detachNewThread {
    while let line = readLine(strippingNewline: true) {
        guard let m = parseCmd(line) else { continue }
        let rid: String
        if let n = m["id"] as? Int { rid = String(n) } else if let n = m["id"] as? NSNumber { rid = n.stringValue } else { continue }
        guard let cmd = m["cmd"] as? String else { continue }
        if cmd == "open" {
            let url = strVal(m, "url") ?? ""
            let w = intVal(m, "width", 1000); let h = intVal(m, "height", 800)
            onMain {
                host.window.setContentSize(NSSize(width: w, height: h))
                host.window.makeKeyAndOrderFront(nil)
                app.activate(ignoringOtherApps: true)
                if let u = URL(string: url) { host.view.load(URLRequest(url: u)) }
                emit("{\"id\":" + rid + ",\"ok\":" + jesc("opened") + "}")
            }
        } else if cmd == "ua" {
            onMain { emit("{\"id\":" + rid + ",\"ok\":" + jesc(host.ua) + "}") }
        } else if cmd == "eval" {
            let js = strVal(m, "js") ?? ""
            onMain { host.view.evaluateJavaScript(js) { r, e in
                if let e = e { emit("{\"id\":" + rid + ",\"error\":" + jesc(e.localizedDescription) + "}") }
                else if let s = r as? String { emit("{\"id\":" + rid + ",\"ok\":" + jesc(s) + "}") }
                else if let n = r as? NSNumber { emit("{\"id\":" + rid + ",\"ok\":" + jesc(n.stringValue) + "}") }
                else if r == nil { emit("{\"id\":" + rid + ",\"ok\":" + jesc("") + "}") }
                else { emit("{\"id\":" + rid + ",\"ok\":" + jesc(String(describing: r!)) + "}") }
            }}
        } else if cmd == "cookies" {
            let urlStr = strVal(m, "url") ?? host.view.url?.absoluteString ?? ""
            let targetHost = URL(string: urlStr)?.host?.lowercased() ?? ""
            onMain {
                host.view.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                    var items: [String] = []
                    for c in cookies {
                        let d = c.domain.lowercased()
                        let dd = d.hasPrefix(".") ? String(d.dropFirst()) : d
                        if targetHost != dd && !targetHost.hasSuffix("." + dd) { continue }
                        let exp: String
                        if let ed = c.expiresDate { exp = String(Int(ed.timeIntervalSince1970 * 1000)) } else { exp = "null" }
                        let sTrue = "\"true\""; let sFalse = "\"false\""
                        // Build without nested interpolation pitfalls: use concatenation
                        let item = "{\"name\":" + jesc(c.name) + ",\"value\":" + jesc(c.value) + ",\"domain\":" + jesc(c.domain) + ",\"path\":" + jesc(c.path) + ",\"httpOnly\":" + (c.isHTTPOnly ? "true" : "false") + ",\"secure\":" + (c.isSecure ? "true" : "false") + ",\"session\":" + (c.isSessionOnly ? "true" : "false") + ",\"expiresAt\":" + exp + "}"
                        _ = sTrue; _ = sFalse
                        items.append(item)
                    }
                    emit("{\"id\":" + rid + ",\"ok\":[" + items.joined(separator: ",") + "]}")
                }
            }
        } else if cmd == "snapshot" {
            let path = strVal(m, "path") ?? ""
            onMain {
                host.view.takeSnapshot(with: nil) { img, e in
                    if let img = img, let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) {
                        do { try png.write(to: URL(fileURLWithPath: path))
                            emit("{\"id\":" + rid + ",\"ok\":" + jesc(path) + "}")
                        } catch { emit("{\"id\":" + rid + ",\"error\":" + jesc(error.localizedDescription) + "}") }
                    } else {
                        emit("{\"id\":" + rid + ",\"error\":" + jesc(e?.localizedDescription ?? "snapshot failed") + "}")
                    }
                }
            }
        } else if cmd == "close" {
            onMain { host.window.orderOut(nil); emit("{\"id\":" + rid + ",\"ok\":" + jesc("closed") + "}") }
        } else if cmd == "quit" {
            onMain { emit("{\"id\":" + rid + ",\"ok\":" + jesc("bye") + "}"); NSApp.terminate(nil) }
        } else {
            onMain { emit("{\"id\":" + rid + ",\"error\":" + jesc("unknown cmd") + "}") }
        }
    }
    onMain { NSApp.terminate(nil) }
}
app.run()
