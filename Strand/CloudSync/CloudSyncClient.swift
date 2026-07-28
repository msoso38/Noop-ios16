// Compiled ONLY when the CLOUD_SYNC compilation condition is set (by the untracked
// OuraSecrets.xcconfig — see OuraConfig.xcconfig). A default build contains none of this code,
// keeping "fully offline" a byte-level property of the shipped binary, not a runtime promise.
#if CLOUD_SYNC
import Foundation

/// User-facing failure reasons for the cloud-sync lane, mapped to clear, non-crashing messages.
/// Mirrors OuraError's shape (LocalizedError + errorDescription).
enum CloudSyncError: LocalizedError, Equatable {
    case badResponse(Int, String)
    case network(String)
    case decode

    var errorDescription: String? {
        switch self {
        case .badResponse(let code, let detail):
            let extra = detail.isEmpty ? "" : " — \(detail)"
            return "The cloud sync server returned an error (\(code))\(extra)."
        case .network(let d):
            return "Network problem talking to the cloud sync server: \(d)"
        case .decode:
            return "Couldn't read the cloud sync server's response."
        }
    }
}

/// The noop-cloud edit-journal client. Injected `session` makes it URLProtocol-testable.
/// Networking lives here in the app target by design (mirrors Strand/Oura/OuraAPIClient.swift).
final class CloudSyncClient {
    /// Internal rather than private so the liters push path can address the *same* server with the
    /// *same* credential this client already holds. Deliberately not a second settings read: a
    /// server URL that has been through `CloudSyncModel.saveSettings`' validation and a token that
    /// came out of the Keychain are exactly what `/ingest` is using, and the two paths must never
    /// be able to disagree about where "the server" is.
    let baseURL: URL
    let token: String
    private let session: URLSession

    init(baseURL: URL, token: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.token = token
        self.session = session
    }

    /// GET /edits?since=<cursor>, Bearer-authed. Returns the page of rows plus the server's latest seq.
    func fetchEdits(since: Int) async throws -> (edits: [CloudEdit], latestSeq: Int) {
        var req = URLRequest(url: url(path: "edits", query: ["since": String(since)]))
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, status) = try await send(req)
        guard (200..<300).contains(status) else {
            throw CloudSyncError.badResponse(status, bodyPrefix(data))
        }
        guard let decoded = try? JSONDecoder().decode(CloudEditsResponse.self, from: data) else {
            throw CloudSyncError.decode
        }
        return (decoded.edits, decoded.latestSeq)
    }

    /// POST /edits/ack {"seqs":[...]}, Bearer-authed. Returns the count the server actually acked.
    func ack(seqs: [Int]) async throws -> Int {
        var req = URLRequest(url: url(path: "edits/ack"))
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(AckRequest(seqs: seqs))
        let (data, status) = try await send(req)
        guard (200..<300).contains(status) else {
            throw CloudSyncError.badResponse(status, bodyPrefix(data))
        }
        guard let decoded = try? JSONDecoder().decode(AckResponse.self, from: data) else {
            throw CloudSyncError.decode
        }
        return decoded.acked
    }

    // MARK: - Ingest (Phase 3.5: upload this device's own .noopbak)

    /// POST /ingest, Bearer-authed, with the file at `fileURL` streamed straight from disk as a raw
    /// `application/octet-stream` body via `URLSession.upload(for:fromFile:)` — the whole-DB `.noopbak`
    /// can be 100-300MB, so it must never be loaded into memory as `Data` the way `send(_:)`'s
    /// `session.data(for:)` would. Parses `{ok,bytes,latestDay}`; only `bytes`/`latestDay` are modelled
    /// (Decodable ignores the unmodelled `ok` key — it's redundant with the 2xx status check anyway).
    func ingest(fileURL: URL) async throws -> (bytes: Int, latestDay: String?) {
        var req = URLRequest(url: url(path: "ingest"))
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        // #tz-upload: the upload body is the whole SQLite DB (epoch-UTC timestamps only), so the server
        // has no way to know which zone this phone is in right now. Ship the CURRENT IANA identifier as a
        // header the server stores on the ingestLog row (and surfaces via data_freshness) — this answers
        // "what zone is the phone in as of this upload" even before the per-day `phoneTimezone` table
        // lands in a given mirror.
        req.setValue(TimeZone.current.identifier, forHTTPHeaderField: "X-Phone-Timezone")
        let (data, status) = try await sendUpload(req, fromFile: fileURL)
        guard (200..<300).contains(status) else {
            throw CloudSyncError.badResponse(status, bodyPrefix(data))
        }
        guard let decoded = try? JSONDecoder().decode(IngestResponse.self, from: data) else {
            throw CloudSyncError.decode
        }
        return (decoded.bytes, decoded.latestDay)
    }

    // MARK: - Device registration (Cloud Sync v2: APNs silent-push wake)

    /// POST /register-device, Bearer-authed, `{"token":"<hex>","platform":"ios"}` — hands the server
    /// this device's APNs device token so it can wake this device with a silent (content-available)
    /// push the moment a NEW edit is confirmed elsewhere, instead of only ever reaching it on the next
    /// background-refresh/launch poll. `token` is the hex-encoded APNs device token (the caller,
    /// `CloudSyncAppDelegate`, does the hex-encoding — this method just ships whatever string it's
    /// given).
    ///
    /// The server endpoint ships in parallel with this client (code-complete ahead of it), so a 404
    /// here is EXPECTED for a while — see `CloudSyncAppDelegate`'s call site for why that's treated as
    /// benign rather than a real failure. Same uniform error contract as every other call on this
    /// client (`CloudSyncError.badResponse` for any non-2xx status): no status-code special-casing
    /// happens in here, that judgment call belongs to the caller.
    func registerDevice(token: String) async throws {
        var req = URLRequest(url: url(path: "register-device"))
        req.httpMethod = "POST"
        req.setValue("Bearer \(self.token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(RegisterDeviceRequest(token: token, platform: "ios"))
        let (data, status) = try await send(req)
        guard (200..<300).contains(status) else {
            throw CloudSyncError.badResponse(status, bodyPrefix(data))
        }
    }

    // MARK: - Deep buffers (#423: WHOOP 5/MG high-rate offload archive)

    /// POST /deepbuf, Bearer-authed — one line-aligned, raw-DEFLATE-compressed byte range of
    /// `PuffinDeepBufferLog`'s append-only JSONL. See `DeepBufferUploadPlan` for why the unit of upload
    /// is `(generation, byteStart, byteEnd)` rather than a whole file.
    ///
    /// `compressed` is passed as `Data`, NOT streamed from a file the way `ingest` above must be: a
    /// chunk is ~4 MB and is ALREADY in memory (it was just read and deflated), so spilling it to a
    /// temp file purely to hand `upload(for:fromFile:)` a path would add two disk round-trips to buy
    /// nothing. `ingest`'s whole-DB body is 100-300 MB, which is a different problem.
    ///
    /// The compression is announced in a CUSTOM header rather than `Content-Encoding: deflate`
    /// deliberately: `Content-Encoding` invites any proxy in the path (and Express itself) to helpfully
    /// decode the body, which would silently defeat the whole point of compressing on the phone and
    /// leave the server parsing bytes it thinks are plaintext. A header nothing but this endpoint reads
    /// cannot be helpfully misinterpreted by anything in between.
    func uploadDeepBuffer(generation: String, byteStart: Int, byteEnd: Int,
                          compressed: Data) async throws -> DeepBufferUploadReceipt {
        var req = URLRequest(url: url(path: "deepbuf"))
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue("deflate-raw", forHTTPHeaderField: "X-Deepbuf-Compression")
        req.setValue(generation, forHTTPHeaderField: "X-Deepbuf-Generation")
        req.setValue(String(byteStart), forHTTPHeaderField: "X-Deepbuf-Byte-Start")
        req.setValue(String(byteEnd), forHTTPHeaderField: "X-Deepbuf-Byte-End")
        // Same #tz-upload convention as `ingest` above: every timestamp inside the payload is epoch-UTC,
        // so the phone's current zone is only knowable if it says so.
        req.setValue(TimeZone.current.identifier, forHTTPHeaderField: "X-Phone-Timezone")
        let (data, status) = try await sendUpload(req, from: compressed)
        guard (200..<300).contains(status) else {
            throw CloudSyncError.badResponse(status, bodyPrefix(data))
        }
        guard let decoded = try? JSONDecoder().decode(DeepBufferResponse.self, from: data) else {
            throw CloudSyncError.decode
        }
        return DeepBufferUploadReceipt(storedBytes: decoded.storedBytes, lines: decoded.lines,
                                        duplicate: decoded.duplicate ?? false)
    }

    private func sendUpload(_ req: URLRequest, fromFile fileURL: URL) async throws -> (Data, Int) {
        do {
            let (data, resp) = try await session.upload(for: req, fromFile: fileURL)
            return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            throw CloudSyncError.network(error.localizedDescription)
        }
    }

    private func sendUpload(_ req: URLRequest, from body: Data) async throws -> (Data, Int) {
        do {
            let (data, resp) = try await session.upload(for: req, from: body)
            return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            throw CloudSyncError.network(error.localizedDescription)
        }
    }

    private func url(path: String, query: [String: String] = [:]) -> URL {
        var comps = URLComponents(string: "\(baseURL.absoluteString)/\(path)")!
        if !query.isEmpty {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return comps.url!
    }

    private func send(_ req: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, resp) = try await session.data(for: req)
            return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            throw CloudSyncError.network(error.localizedDescription)
        }
    }

    /// Truncates the response body to a bounded prefix for error messages (never surface an
    /// unbounded server body through an error description).
    private func bodyPrefix(_ data: Data) -> String {
        let s = String(data: data, encoding: .utf8) ?? ""
        return s.count > 200 ? String(s.prefix(200)) : s
    }
}

private struct AckRequest: Encodable {
    let seqs: [Int]
}

private struct AckResponse: Decodable {
    let acked: Int
}

private struct RegisterDeviceRequest: Encodable {
    let token: String
    let platform: String
}

private struct IngestResponse: Decodable {
    let bytes: Int
    let latestDay: String?
}

/// `/deepbuf`'s reply. `duplicate` is optional because the server omits it on the normal path (it is
/// only present, and true, when the chunk was already in the manifest) — see `DeepBufferUploadReceipt`.
private struct DeepBufferResponse: Decodable {
    let storedBytes: Int
    let lines: Int
    let duplicate: Bool?
}
#endif // CLOUD_SYNC
