# 05 — Real-strap parity verification

**What to build:** Nothing new in the app — this ticket closes the loop with real hardware. Run a
connection test-profile session (same shape as the one that surfaced this gap:
`~/Downloads/noop-connection-iOS-v9.1.1-260727-1600/`) against BOTH a WHOOP 5.0/MG and a WHOOP 4.0
strap in one run, with the renamed capture toggle on, and confirm the resulting capture file
contains full-fidelity raw frames (not rendered payload substrings) for both families.

Use this trace to specifically answer the question that started this whole thread: does WHOOP
4.0's `GET_ALARM_TIME` have the same FAILURE-misread-as-no-alarm bug that was fixed for 5/MG in
commit `2f07a7b4`? With a real 4.0 raw frame for that command now captured, the result byte is
directly verifiable (`FrameRouter.commandResultByte`/`readbackReportsNoAlarm` in
`Strand/BLE/FrameRouter.swift`) instead of inferred from `report.txt`'s rendered payload line. If
it does have the bug, file it as its own follow-up issue rather than fixing it inside this ticket
— this ticket's job is verification/tooling closure, not a new protocol fix.

**Blocked by:** 03 — iOS/macOS: rename + wire WHOOP 4.0, 04 — Android: rename + wire WHOOP 4.0

**Status:** verification complete (iOS); Android still open

- [x] A single test-profile session capturing both a 5/MG and a WHOOP 4.0 connection produces raw frames for both families in the capture file — `noop-connection-iOS-v9.1.1-260727-1600/` (2026-07-27 15:55–16:00 NZST): connects to a WHOOP 5/MG first (`report.txt` lines 31–102, DIS `variant=5.0`), then switches to and stays on a WHOOP 4.0 (`report.txt` line 148 `bondState … family=whoop4` onward). Note: that session's `raw-capture.jsonl` ring only retained the first ~34s of frames (the 5/MG portion) — the 4.0 raw frames for the next checklist item came from a second, focused capture below.
- [x] The WHOOP 4.0 `GET_ALARM_TIME` COMMAND_RESPONSE result byte is directly readable from a captured raw frame (not inferred) — `~/Downloads/raw-20260727-183144.json`, frame seq 123: `aa140003247b433201010150a1676a00000400200502a5d7`. Decoding `[type,seq,cmd,origin_seq,result]` at the WHOOP4 inner offset (4): `cmd=0x43` (67, GET_ALARM_TIME), **`result=0x01` = SUCCESS**. The payload's epoch bytes (`50 a1 67 6a`) are byte-for-byte identical to the epoch just sent in the preceding `SET_ALARM_TIME` frame (seq 122, `aa100057247a4231010150a1676a0000241bcf17`, payload `01 50 a1 67 6a 00 00`). This does **not** match the pre-`2f07a7b4` misread pattern (that was FAILURE=0x00 immediately after a successful SET, on 5/MG).
- [ ] N/A — bug not present on 4.0, so no follow-up issue filed.
- [x] Not present because: WHOOP 4.0's `GET_ALARM_TIME` firmware path genuinely answers SUCCESS with the real armed epoch; unlike 5/MG, it was never guessing FAILURE=no-alarm. The only real bug found in this family's readback was a decode-side one (fixed in `281b20cf`: the payload's SET-mirror prefix is two `0x01` bytes, not one) — a NOOP-side bug, not a firmware quirk. Recorded here so "does 4.0 have the 5/MG alarm-readback bug" isn't re-asked: **no, by design/firmware, confirmed 2026-07-27 on two independent real captures.**
- [ ] Android: not yet repeated — needs its own real-strap capture (see `Strand/BLE/FrameRouter.swift` decode vs `android/.../ble/WhoopBleClient.kt` twin, and the Android capture flow from ticket 04).

**Bonus finding this pass:** running this exact live-capture test on iOS surfaced and fixed a real crash — `Collector.ingest`'s DEBUG parse-once-invariant assert reparsed with `collectFields: false` while the live path had built `parsed` with `collectFields: rawFrameRecorder.isEnabled` (true, since raw capture was on for this test). Threaded the actual flag through (`Strand/Collect/Collector.swift`, `Strand/BLE/BLEManager.swift`) so the assert compares like-for-like. Release builds were unaffected (assert compiles out), but it blocked exactly this kind of on-device raw-capture testing.
