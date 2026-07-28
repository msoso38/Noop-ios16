// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig — see OuraConfig.xcconfig) AND LITERS is set (by the generated
// Config/LitersLocal.xcconfig, i.e. only after Rust/build-ios.sh has produced the xcframework).
// A default build contains none of this code.
#if CLOUD_SYNC && LITERS
import Foundation

/// The host HTTP transport liters runs every `Storage.http` request through.
///
/// ## Why this file has to exist at all
///
/// `LitersWriter(dbPath:storage:)` without a host client uses liters' built-in socket transport,
/// which is **`http://` only** — `HttpReplicaClient::with_options` *rejects* an `https://` URL
/// rather than silently downgrading it. `noop-cloud` is HTTPS. So on this app the choice is not
/// "nicer transport vs. built-in"; without this class the liters path cannot reach the server at
/// all.
///
/// ## The shape liters asks for, and why it is blocking
///
/// `execute` is synchronous by contract: liters calls it from its own worker while holding the
/// writer lock, and expects a response object it then *pulls* bytes from. That is deliberate — it
/// is what lets a 300 MB snapshot push stream instead of buffering. The bridge here is therefore a
/// `DispatchSemaphore` around a `URLSession` task, and it is a hard error to call it on the main
/// thread: `CloudSyncUploader` only ever reaches it from a `Task`'s background executor, and the
/// `precondition` makes a future misuse fail loudly rather than deadlock the UI.
///
/// ## What this is NOT
///
/// Not a background `URLSession`. A background session delivers completion through the app
/// delegate, minutes later, to a process that may have been relaunched — there is no way to return
/// that from a synchronous `execute`, and pretending otherwise would be worse than not having it.
/// The consequence is stated plainly: an upload in flight when iOS suspends the app is lost, and
/// the next push resumes from the last committed TXID. **That is exactly today's `/ingest`
/// behaviour** (`CloudSyncClient` also uses a foreground session), so this path is not a regression
/// — but it is the reason `liters`' own docs push you toward a background session, and it is the
/// first thing to revisit if pushes start dying at suspend.
final class LitersURLSessionClient: HttpClient, @unchecked Sendable {
    /// One instance per process, one `URLSession` inside it, so every request to the server
    /// coalesces onto one connection — which is what liters' `HttpClient` doc asks for.
    static let shared = LitersURLSessionClient()

    /// Generous, because a snapshot push is the whole database. `timeoutIntervalForRequest` is an
    /// *inactivity* timeout, not a total-transfer one, so this does not cap a slow large upload;
    /// `timeoutIntervalForResource` does, and 1 hour is the ceiling on one push attempt.
    private let session: URLSession

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let cfg = URLSessionConfiguration.default
            cfg.timeoutIntervalForRequest = 120
            cfg.timeoutIntervalForResource = 3600
            cfg.httpMaximumConnectionsPerHost = 2
            cfg.waitsForConnectivity = true
            self.session = URLSession(configuration: cfg)
        }
    }

    func execute(request: HttpRequest, body: HttpRequestBody?) throws -> HttpResponse {
        precondition(!Thread.isMainThread,
                     "LitersURLSessionClient.execute blocks; it must never run on the main thread")

        guard let url = URL(string: request.url) else {
            // `HttpError.Protocol(...)` will not parse — Swift reads `Type.Protocol` as the
            // metatype syntax regardless of backticks — so the case is reached through a
            // leading-dot member reference on an explicitly typed value.
            let e: HttpError = .Protocol(message: "liters produced an unparseable URL: \(request.url)")
            throw e
        }
        var req = URLRequest(url: url)
        req.httpMethod = request.method
        for h in request.headers { req.setValue(h.value, forHTTPHeaderField: h.name) }

        // A PUT body is pulled from liters, never handed over whole: `HttpRequestBody.read(max:)`
        // is the source and a 300 MB snapshot must not be materialised as `Data`. Bound streams
        // give `URLSession` an `InputStream` while a producer thread fills the other end.
        if let body {
            req.httpBodyStream = Self.stream(pulling: body)
        }

        let sem = DispatchSemaphore(value: 0)
        var out: (data: Data, response: HTTPURLResponse)?
        var failure: Error?

        let task = session.dataTask(with: req) { data, response, error in
            if let error {
                failure = error
            } else if let http = response as? HTTPURLResponse {
                out = (data ?? Data(), http)
            } else {
                let e: HttpError = .Protocol(message: "no HTTP response")
                failure = e
            }
            sem.signal()
        }
        task.resume()
        sem.wait()

        if let failure {
            // Transport, not Protocol: liters treats Transport as retryable, and a dropped
            // connection or a suspended radio is exactly that. A push is idempotent by key, so a
            // retry of a half-sent PUT is safe.
            throw HttpError.Transport(message: (failure as NSError).localizedDescription)
        }
        guard let out else { throw HttpError.Transport(message: "no response and no error") }

        var headers: [HttpHeader] = []
        for (k, v) in out.response.allHeaderFields {
            guard let name = k as? String, let value = v as? String else { continue }
            // `content-length` and `content-encoding` are rewritten below, not forwarded. URLSession
            // owns the transfer: it transparently negotiates and undoes `Content-Encoding`, and it
            // does not reliably surface `Content-Length` at all — an HTTP/2 response, or one it
            // decompressed, arrives with the header missing or describing the *compressed* size.
            // Either way the number would not describe `out.data`.
            //
            // This is not cosmetic: liters' client requires a length on a listing response and
            // fails the whole push with "storage: list level 0: missing content-length" without
            // one. Found by running it — the first real push through this class died here.
            let lower = name.lowercased()
            if lower == "content-length" || lower == "content-encoding" { continue }
            headers.append(HttpHeader(name: name, value: value))
        }
        // The authoritative length: what the caller will actually be able to read.
        headers.append(HttpHeader(name: "content-length", value: String(out.data.count)))
        return BufferedHTTPResponse(status: UInt16(out.response.statusCode),
                                    headerList: headers,
                                    body: out.data)
    }

    /// Bridges liters' pull-based body into the push-based `InputStream` `URLSession` wants.
    ///
    /// `Stream.getBoundStreams` gives a connected pair with a fixed-size kernel-ish buffer; the
    /// producer thread blocks on `write` when the consumer is behind, which is the backpressure
    /// that keeps memory flat regardless of push size.
    private static func stream(pulling body: HttpRequestBody) -> InputStream {
        var input: InputStream?
        var output: OutputStream?
        Stream.getBoundStreams(withBufferSize: 65_536, inputStream: &input, outputStream: &output)
        guard let input, let output else {
            // Cannot happen; a zero-byte body is still a legal body and lets the PUT fail on the
            // server's terms rather than crashing the app here.
            return InputStream(data: Data())
        }
        let pump = Thread {
            output.open()
            defer { output.close() }
            while true {
                let chunk: Data
                do {
                    chunk = try body.read(max: 65_536)
                } catch {
                    break // aborts the upload; URLSession sees a truncated body and errors
                }
                if chunk.isEmpty { break } // documented end-of-body
                var sent = 0
                chunk.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                    guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                    while sent < chunk.count {
                        let n = output.write(base + sent, maxLength: chunk.count - sent)
                        if n <= 0 { sent = chunk.count; return } // consumer went away
                        sent += n
                    }
                }
            }
        }
        pump.name = "liters-body-pump"
        pump.stackSize = 512 * 1024
        pump.start()
        return input
    }
}

/// liters' responses on the push path are small (a status line or a short JSON error), so the whole
/// body is collected and then served through the pull interface. The long-lived `/stream` follow —
/// the one case where buffering would be wrong — is a *Replica* concern; this app only pushes.
private final class BufferedHTTPResponse: HttpResponse, @unchecked Sendable {
    private let statusCode: UInt16
    private let headerList: [HttpHeader]
    private var body: Data
    private let lock = NSLock()

    init(status: UInt16, headerList: [HttpHeader], body: Data) {
        self.statusCode = status
        self.headerList = headerList
        self.body = body
    }

    func status() -> UInt16 { statusCode }
    func headers() -> [HttpHeader] { headerList }

    func readBody(max: UInt32) throws -> BodyChunk {
        lock.lock()
        defer { lock.unlock() }
        if body.isEmpty { return .eof }
        let n = Swift.min(Int(max), body.count)
        let chunk = body.prefix(n)
        body = body.dropFirst(n)
        return .data(bytes: Data(chunk))
    }

    /// Nothing to abort: the transfer already completed before this object existed.
    func cancel() {}
}
#endif // CLOUD_SYNC && LITERS
