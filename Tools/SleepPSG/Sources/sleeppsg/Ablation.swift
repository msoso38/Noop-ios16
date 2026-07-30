import Foundation

// Ablation — the leave-one-SUBJECT-out model comparison that answers "is REM detection reading physiology,
// or is it reading the clock?"
//
// The question is not rhetorical. REM rises through the night in every population, so a model given nothing
// but "how far into the night is it" already scores respectably. If NOOP's REM output were mostly that, the
// per-epoch cardiac and movement terms would be decoration and the honest description of the feature would
// change. The test is to fit three models on the same epochs and compare: clock features alone, physiology
// features alone, and both.
//
// LEAVE-ONE-SUBJECT-OUT, never epoch-level. Consecutive 30 s epochs from one night are about as
// independent as consecutive frames of a film: a random epoch split puts a subject's 02:14 epoch in train
// and their 02:14:30 epoch in test, and every model then scores near-perfectly by memorising the subject.
// Each fold here holds out one whole subject, fits on the other 30, and is scored only on the held-out one.
//
// The decision threshold is chosen ON THE TRAINING FOLDS (the value maximising training F1) and then
// applied unchanged to the held-out subject. A fixed 0.5 would not be neutral: REM is ~20 % of a night, so
// 0.5 systematically under-predicts a minority class, and it would do so unequally across three models with
// different calibration. Tuning it on the test fold would be the leak this whole design exists to avoid.

/// One epoch's evidence, already reduced to the columns the recipe itself sees.
struct AblationRow {
    let subject: String
    let isRem: Bool
    /// Time-of-night fraction and its square — enough for a clock model to express a rise, a fall, or a
    /// hump, so "the clock" is not strawmanned as a straight line.
    let clock: [Double]
    /// Per-night z-scored heart rate, heart-rate variability, movement, and the HR-flatness percentile —
    /// the same normalised quantities `stageEpochs` builds its emissions from.
    let physiology: [Double]
}

enum AblationModel: String, CaseIterable {
    case clock = "clock-only"
    case physiology = "physiology-only"
    case both = "both"

    func features(_ r: AblationRow) -> [Double] {
        switch self {
        case .clock: return r.clock
        case .physiology: return r.physiology
        case .both: return r.clock + r.physiology
        }
    }
}

struct AblationResult {
    let model: AblationModel
    /// F1 over every held-out epoch pooled — one number for the cohort.
    let pooledF1: Double
    /// Mean of the per-subject F1s — one number per subject, so a long night cannot outvote a short one.
    let meanSubjectF1: Double
    let subjectF1: [String: Double]
    let pooledPrecision: Double
    let pooledRecall: Double
}

enum Ablation {

    /// Fit and score all three models leave-one-subject-out.
    static func run(_ rows: [AblationRow]) -> [AblationResult] {
        let subjects = Array(Set(rows.map { $0.subject })).sorted()
        var out: [AblationResult] = []
        for model in AblationModel.allCases {
            var pooledRef: [Bool] = [], pooledPred: [Bool] = []
            var perSubject: [String: Double] = [:]
            for held in subjects {
                let train = rows.filter { $0.subject != held }
                let test = rows.filter { $0.subject == held }
                guard !train.isEmpty, !test.isEmpty else { continue }
                let X = train.map { model.features($0) }
                let y = train.map { $0.isRem }
                let w = logisticFit(X: X, y: y)
                let trainP = X.map { sigmoid(dot(w, $0)) }
                let thr = bestF1Threshold(scores: trainP, truth: y)
                let testX = test.map { model.features($0) }
                let pred = testX.map { sigmoid(dot(w, $0)) >= thr }
                let ref = test.map { $0.isRem }
                perSubject[held] = binaryF1(ref: ref, pred: pred)
                pooledRef.append(contentsOf: ref)
                pooledPred.append(contentsOf: pred)
            }
            var tp = 0, fp = 0, fn = 0
            for i in 0..<pooledRef.count {
                if pooledRef[i] && pooledPred[i] { tp += 1 }
                else if !pooledRef[i] && pooledPred[i] { fp += 1 }
                else if pooledRef[i] && !pooledPred[i] { fn += 1 }
            }
            out.append(AblationResult(
                model: model,
                pooledF1: binaryF1(ref: pooledRef, pred: pooledPred),
                meanSubjectF1: mean(subjects.compactMap { perSubject[$0] }),
                subjectF1: perSubject,
                pooledPrecision: tp + fp == 0 ? .nan : Double(tp) / Double(tp + fp),
                pooledRecall: tp + fn == 0 ? .nan : Double(tp) / Double(tp + fn)))
        }
        return out
    }

    // MARK: - Logistic regression (ridge-regularised IRLS)

    static func sigmoid(_ z: Double) -> Double { 1.0 / (1.0 + exp(-max(-40, min(40, z)))) }

    static func dot(_ w: [Double], _ x: [Double]) -> Double {
        var s = w[0]                       // intercept
        for i in 0..<x.count { s += w[i + 1] * x[i] }
        return s
    }

    /// Newton/IRLS with a small ridge penalty. The ridge is there for conditioning, not for tuning: the
    /// feature counts here are 2–6 and the penalty is fixed across all three models, so it cannot advantage
    /// one of them. Returns `[intercept, coefficients…]`.
    static func logisticFit(X: [[Double]], y: [Bool], ridge: Double = 1e-4, iterations: Int = 30) -> [Double] {
        let p = (X.first?.count ?? 0) + 1
        var w = [Double](repeating: 0, count: p)
        guard !X.isEmpty, p > 1 else { return w }
        for _ in 0..<iterations {
            var H = [[Double]](repeating: [Double](repeating: 0, count: p), count: p)
            var g = [Double](repeating: 0, count: p)
            for (i, row) in X.enumerated() {
                var xi = [Double](repeating: 1, count: p)
                for j in 0..<row.count { xi[j + 1] = row[j] }
                let mu = sigmoid(dot(w, row))
                let r = (y[i] ? 1.0 : 0.0) - mu
                let s = max(1e-8, mu * (1 - mu))
                for a in 0..<p {
                    g[a] += r * xi[a]
                    for b in 0..<p { H[a][b] += s * xi[a] * xi[b] }
                }
            }
            for a in 0..<p { H[a][a] += ridge * Double(X.count); g[a] -= ridge * Double(X.count) * w[a] }
            guard let step = solve(H, g) else { break }
            var maxStep = 0.0
            for a in 0..<p { w[a] += step[a]; maxStep = max(maxStep, abs(step[a])) }
            if maxStep < 1e-8 { break }
        }
        return w
    }

    /// Gaussian elimination with partial pivoting. Returns nil on a singular system, which leaves the
    /// caller with the last good coefficients rather than NaNs.
    static func solve(_ a0: [[Double]], _ b0: [Double]) -> [Double]? {
        var a = a0, b = b0
        let n = b.count
        for c in 0..<n {
            var piv = c
            for r in (c + 1)..<n where abs(a[r][c]) > abs(a[piv][c]) { piv = r }
            if abs(a[piv][c]) < 1e-12 { return nil }
            if piv != c { a.swapAt(piv, c); b.swapAt(piv, c) }
            for r in (c + 1)..<n {
                let f = a[r][c] / a[c][c]
                if f == 0 { continue }
                for k in c..<n { a[r][k] -= f * a[c][k] }
                b[r] -= f * b[c]
            }
        }
        var x = [Double](repeating: 0, count: n)
        for r in stride(from: n - 1, through: 0, by: -1) {
            var s = b[r]
            for k in (r + 1)..<n { s -= a[r][k] * x[k] }
            x[r] = s / a[r][r]
        }
        return x
    }

    /// The probability cut that maximises F1 on the supplied (training) scores.
    static func bestF1Threshold(scores: [Double], truth: [Bool]) -> Double {
        var best = 0.5, bestF1 = -1.0
        var t = 0.02
        while t < 1.0 {
            let pred = scores.map { $0 >= t }
            let f1 = binaryF1(ref: truth, pred: pred)
            if f1 > bestF1 { bestF1 = f1; best = t }
            t += 0.02
        }
        return best
    }
}
