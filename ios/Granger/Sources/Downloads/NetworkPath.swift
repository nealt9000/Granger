import Foundation
import Network

/// The network as the download queue needs it (Plan I4 Task 6): whether any is up, and whether it's Wi-Fi (not
/// expensive, not constrained), from NWPathMonitor; `changed` runs whenever that moves. Read from any thread.
final class NetworkPath: @unchecked Sendable {
    static let shared = NetworkPath()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var _connected = true
    private var _unmetered = true
    var changed: () -> Void = {}

    var connected: Bool { lock.lock(); defer { lock.unlock() }; return _connected }
    var unmetered: Bool { lock.lock(); defer { lock.unlock() }; return _unmetered }

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            self._connected = path.status == .satisfied
            self._unmetered = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            self.lock.unlock()
            self.changed()
        }
        monitor.start(queue: DispatchQueue(label: "app.granger.network"))
    }
}
