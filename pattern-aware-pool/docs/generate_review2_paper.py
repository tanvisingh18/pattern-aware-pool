#!/usr/bin/env python3
"""Generate Review-2 research paper DOCX/MD from experiment_summary.csv."""

from __future__ import annotations

import csv
from pathlib import Path

from docx import Document
from docx.shared import Pt, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "Java Submission"
OUT_DIR.mkdir(exist_ok=True)
OUT = OUT_DIR / "Review2_Complete_Research_Paper.docx"
MD_OUT = OUT_DIR / "Review2_Complete_Research_Paper.md"
SUMMARY = ROOT / "pattern-aware-pool" / "docs" / "results" / "experiment_summary.csv"


def set_run_font(run, size=11, bold=False, italic=False):
    run.font.name = "Times New Roman"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "Times New Roman")
    run.font.size = Pt(size)
    run.bold = bold
    run.italic = italic


def add_heading(doc, text, level=1):
    h = doc.add_heading(text, level=level)
    for run in h.runs:
        set_run_font(run, size=14 if level == 1 else 12, bold=True)


def add_para(doc, text, *, bold=False, italic=False, size=11, center=False, space_after=8):
    p = doc.add_paragraph()
    if center:
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_run_font(run, size=size, bold=bold, italic=italic)
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.15


def add_table(doc, headers, rows):
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.style = "Table Grid"
    for i, h in enumerate(headers):
        cell = table.rows[0].cells[i]
        cell.text = h
        for p in cell.paragraphs:
            for r in p.runs:
                set_run_font(r, size=9, bold=True)
    for r_i, row in enumerate(rows):
        for c_i, val in enumerate(row):
            cell = table.rows[r_i + 1].cells[c_i]
            cell.text = str(val)
            for p in cell.paragraphs:
                for r in p.runs:
                    set_run_font(r, size=9)
    doc.add_paragraph()


def load_summary():
    if not SUMMARY.exists():
        raise SystemExit(f"missing {SUMMARY}")
    with SUMMARY.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def find_row(rows, mode, scenario):
    for r in rows:
        if r["mode"] == mode and r["scenario"] == scenario:
            return r
    raise KeyError(f"{mode}/{scenario}")


def fmt(mean_s, sd_s, digits=1):
    return f"{float(mean_s):.{digits}f}±{float(sd_s):.{digits}f}"


def pct(mean_s, sd_s):
    return f"{float(mean_s) * 100:.1f}%±{float(sd_s) * 100:.1f}"


def uf(row):
    return fmt(row["mean_user_facing_failures"], row["sd_user_facing_failures"])


def probes(row):
    return (
        f"{fmt(row['mean_recovery_probes_ok'], row['sd_recovery_probes_ok'])}/"
        f"{fmt(row['mean_recovery_probes_fail'], row['sd_recovery_probes_fail'])}"
    )


def build():
    summary = load_summary()
    bad_r = find_row(summary, "reactive", "bad-window")
    bad_c = find_row(summary, "circuit_breaker", "bad-window")
    bad_p = find_row(summary, "predictive", "bad-window")
    morn_c = find_row(summary, "circuit_breaker", "morning-healthy")
    morn_p = find_row(summary, "predictive", "morning-healthy")
    win_r = find_row(summary, "reactive", "window-start")
    win_c = find_row(summary, "circuit_breaker", "window-start")
    win_p = find_row(summary, "predictive", "window-start")
    reuse_off = find_row(summary, "predictive", "reuse-off")
    reuse_on = find_row(summary, "predictive", "reuse-on")
    shift14 = find_row(summary, "predictive", "pattern-shift-h14")
    shift15 = find_row(summary, "predictive", "pattern-shift-h15")

    n = int(float(bad_r["n"]))
    react_fail = uf(bad_r)
    cb_fail = uf(bad_c)
    pred_fail = uf(bad_p)
    pred_fo = fmt(bad_p["mean_preemptive_failovers"], bad_p["sd_preemptive_failovers"])
    pred_warm = fmt(bad_p["mean_warm_hits"], bad_p["sd_warm_hits"])
    pred_probes = probes(bad_p)
    pred_prewarm = fmt(
        bad_p["mean_background_prewarm_connects"], bad_p["sd_background_prewarm_connects"]
    )
    morn_p_backup = fmt(morn_p["mean_backup_selections"], morn_p["sd_backup_selections"])
    morn_c_backup = fmt(morn_c["mean_backup_selections"], morn_c["sd_backup_selections"])

    doc = Document()
    section = doc.sections[0]
    section.top_margin = Inches(1)
    section.bottom_margin = Inches(1)
    section.left_margin = Inches(1)
    section.right_margin = Inches(1)

    add_para(
        doc,
        "History-Aware Predictive Connection Pooling: A Client-Side Learning Approach "
        "to Self-Healing JDBC Connections in Structurally Unreliable Networks",
        bold=True,
        size=14,
        center=True,
    )
    add_para(doc, "Sara Sharma, Tanvi Singh", center=True, size=12)
    add_para(doc, "23BCE0967, 23BCE2155", center=True, size=11)
    add_para(
        doc,
        "School of Computer Science and Engineering, Vellore Institute of Technology, Vellore, India",
        center=True,
        size=11,
    )
    add_para(
        doc,
        "Guide: Mr. Syamasudha Veeragandham, Assistant Professor, School of Computer Science and Engineering, "
        "Department of Software Systems, VIT Vellore",
        center=True,
        size=10,
        italic=True,
    )
    add_para(doc, "Review-2 Complete Research Document (through Conclusion)", center=True, size=11, bold=True)

    add_heading(doc, "Abstract", 1)
    add_para(
        doc,
        "Standard JDBC connection pools and resilience wrappers such as circuit breakers are largely "
        "memoryless across recurring structural failure patterns. This paper presents a client-side "
        "pattern-aware pool that learns per-endpoint history (EWMA hourly rates, run-length clusters, "
        "and optional reset periods), then fails over when weighted risk, hot-hour EWMA, live "
        "cluster length, or a predicted reset threshold fires — and pre-warms backup connections with "
        f"recovery probes. We compare three modes under a learn-then-measure protocol (7-day live learning, "
        f"day-8 measurement, n={n} seeds): reactive primary-first, a classic circuit breaker "
        "(open after 3 primary failures / 60s / half-open probe), and the predictive pool. In the afternoon "
        f"bad window, reactive incurred {react_fail} user-facing connect failures per 100 requests, "
        f"circuit breaker {cb_fail}, and predictive {pred_fail} with {pred_fo} preemptive failovers, "
        f"{pred_warm} warm hits, recovery probes {pred_probes}, and {pred_prewarm} background pre-warm "
        "connects (from experiment_summary.csv). UI is Swing (not JavaFX); lifecycle hooks are "
        "Spring-compatible in spirit, not a Spring dependency.",
    )
    add_para(
        doc,
        "Keywords— connection pooling; JDBC; self-healing systems; predictive fault tolerance; "
        "EWMA; circuit breaker; hot-hour failover; recovery probe; preemptive migration.",
        italic=True,
    )

    add_heading(doc, "I. INTRODUCTION", 1)
    add_para(
        doc,
        "Connection pools amortise JDBC handshake cost, but mainstream pools follow a memoryless failure "
        "loop: validate/use, evict on failure, create replacement. That fits near-independent data-centre "
        "failures poorly when connectivity degrades in structured ways — daily bad hours, burst clusters, "
        "and threshold-triggered resets after a bounded success streak.",
    )
    add_para(
        doc,
        "Circuit breakers reduce repeated primary attempts after a short failure window, but still pay "
        "the first failures of every open period and do not pre-warm backups. This work evaluates whether "
        "client-side history can outperform both reactive failover and a circuit breaker on the same "
        "flaky-endpoint simulator.",
    )

    add_heading(doc, "II. OBJECTIVES", 1)
    for t in [
        "O1 — Failure memory: record attempts per endpoint in a bounded store.",
        "O2 — Pattern learning: hourly rates (EWMA for gating; plain rate for display), clusters, optional reset detection.",
        "O3 — Predictive routing: failover on weighted score OR hot hour (EWMA) OR live cluster OR reset prediction.",
        "O4 — Pre-emptive readiness: pre-warm backups and recover primary via background probes.",
        "O5 — Evaluation: learn from live traffic for 7 days; measure day 8; report mean±sd over seeds.",
    ]:
        add_para(doc, t)

    add_heading(doc, "III. LITERATURE REVIEW (SUMMARY)", 1)
    add_para(
        doc,
        "Reactive patterns (circuit breaker, retry/backoff) contain cascading failure after thresholds "
        "are crossed but cannot act before the first failures of a known pattern [2][14]. Predictive "
        "systems such as PreGAN+ [1], Deoxys [8], and related edge/satellite predictors [5][6] demonstrate "
        "predict-then-act behaviour with infrastructure telemetry a JDBC client typically lacks. Review-1 "
        "surveyed a broader set (21 papers); this Review-2 document cites the subset needed for methodology "
        "through conclusion and does not alter Review-1 artefacts.",
    )

    add_heading(doc, "IV. RESEARCH GAP", 1)
    add_para(
        doc,
        "Gap: an application-level JDBC pool that treats its own failure history as a first-class signal "
        "for pre-emptive per-request routing and pre-warming, without external telemetry — evaluated "
        "against both reactive failover and a circuit-breaker baseline.",
    )

    add_heading(doc, "V. PROPOSED METHODOLOGY / PROPOSED SYSTEM", 1)
    add_heading(doc, "V.A Overall Methodology", 2)
    add_para(
        doc,
        "Layered development: domain models → Layer 1 history/analysis → Layer 2 scoring/routing → "
        "Layer 3 pre-warm/recovery → ConnectionPool orchestration (predictive / reactive / circuit-breaker) → "
        "ExperimentRunner → Advanced Java integrations (named ThreadGroup workers, Spring-compatible "
        "lifecycle class, RMI management, Swing monitoring dashboard).",
    )
    add_heading(doc, "V.B System Architecture", 2)
    add_para(
        doc,
        "Application → ConnectionPool.getConnection()\n"
        "    ├─ RoutingDecider ← PredictionEngine ← EndpointRiskProfile\n"
        "    │       ↑                    ↑\n"
        "    │   PatternAnalyzer + ResetPatternDetector ← FailureHistoryStore\n"
        "    ├─ WarmPool / BackupPreWarmer (pre-warm + recovery probes)\n"
        "    ├─ IdleConnectionPool (real reuse; experiments disable reuse for routing purity)\n"
        "    ├─ EndpointConnector (Flaky simulator or JdbcEndpointConnector)\n"
        "    └─ PoolMetrics + MonitoringDashboard (Swing) + PoolLifecycle / RMI\n"
        "Background: ScheduledExecutorService in ThreadGroup \"pap-monitoring\"",
        size=10,
    )
    add_heading(doc, "V.C Detailed Approach", 2)
    add_para(
        doc,
        "Routing avoids the requested endpoint when ANY of: (1) weighted risk ≥ threshold; "
        "(2) hot hour — enough samples and EWMA failure rate ≥ hotHourThreshold (plain rate is display-only); "
        "(3) live cluster — currentRunLength ≥ clusterAvoidRun; "
        "(4) reset predicted — success streak ≥ detected period P−1 (one-shot). "
        "Attribution order: HOT_HOUR → LIVE_CLUSTER → RESET_PREDICTED → SCORE. "
        "BackupPreWarmer pre-warms when risk/hot-hour is imminent and probes primary while avoided "
        "so traffic can return. IdleConnectionPool provides real pooling when reuse is enabled.",
    )
    add_heading(doc, "V.D Algorithms", 2)
    add_para(
        doc,
        "EWMA hourly update (α=0.35 after warm-up); hot-hour gating uses EWMA after ≥ hotHourMinSamples; "
        "plain rate = failures/samples for display; run-length clustering; "
        "risk = α·tod + β·clusterPenalty + γ·recent; "
        "reset period = median of last three completed success-streaks (≥10) if within ±2; "
        "circuit breaker = open after 3 consecutive primary failures for 60s (MutableClock), then one half-open probe; "
        "recoveryProbeSeconds defaults to 30.",
        size=10,
    )

    add_heading(doc, "VI. IMPLEMENTATION / EXPERIMENTAL SETUP", 1)
    add_heading(doc, "VI.A Requirements", 2)
    add_table(
        doc,
        ["Component", "Specification"],
        [
            ["JDK", "17+ (tested on Temurin 25)"],
            ["Build", "Maven Wrapper (./mvnw) / Maven 3.9.x"],
            ["Test", "JUnit 5"],
            ["UI", "Swing monitoring dashboard (not JavaFX)"],
            ["Lifecycle", "PoolLifecycle — Spring-compatible style, not Spring"],
            ["Remote", "Java RMI PoolManagementRemote"],
        ],
    )
    add_heading(doc, "VI.B Experimental Protocol", 2)
    add_para(
        doc,
        f"Seeds: Random 1..{n}. Learning: 7 days × 24h × 12 req/h through live FlakyEndpointConnector "
        "while advancing MutableClock and calling backgroundTick every 30s simulated for every mode "
        "(identical cadence). recoveryProbeSeconds=30. Day 8 windows: morning-healthy 09:15 (100), "
        "bad-window 14:20 (100), evening-stable 18:00 (80), plus window-start (13:55–14:10), "
        "pattern-shift, and reuse on/off. PoolConfig.reuseEnabled=false for routing experiments. "
        "Latency is simulated (connectors do not Thread.sleep). History is not seeded with day-8 answers. "
        "User-facing connect failures are reported separately from primary connect attempts, recovery probes, "
        "and background pre-warm connects. Results: experiment_runs.csv / experiment_summary.csv.",
    )

    add_heading(doc, "VII. RESULTS AND DISCUSSION", 1)
    add_heading(doc, "VII.A Measured Results", 2)
    add_para(
        doc,
        f"Table II. Mean±sd over n={n} seeds. User-facing connect failures are checkout failures "
        "visible to callers; probe and pre-warm connects are separate CSV columns.",
        italic=True,
    )
    table_rows = []
    for mode, scenario, req in [
        ("reactive", "morning-healthy", "100"),
        ("circuit_breaker", "morning-healthy", "100"),
        ("predictive", "morning-healthy", "100"),
        ("reactive", "bad-window", "100"),
        ("circuit_breaker", "bad-window", "100"),
        ("predictive", "bad-window", "100"),
        ("reactive", "evening-stable", "80"),
        ("circuit_breaker", "evening-stable", "80"),
        ("predictive", "evening-stable", "80"),
    ]:
        r = find_row(summary, mode, scenario)
        table_rows.append(
            [
                mode,
                scenario,
                req,
                pct(r["mean_success_rate"], r["sd_success_rate"]),
                uf(r),
                fmt(r["mean_primary_connect_attempts"], r["sd_primary_connect_attempts"]),
                fmt(r["mean_preemptive_failovers"], r["sd_preemptive_failovers"]),
                fmt(r["mean_warm_hits"], r["sd_warm_hits"]),
                fmt(r["mean_backup_selections"], r["sd_backup_selections"]),
                probes(r),
                fmt(r["mean_background_prewarm_connects"], r["sd_background_prewarm_connects"]),
            ]
        )
    add_table(
        doc,
        [
            "Mode",
            "Scenario",
            "Req",
            "Success",
            "User-Facing Failures",
            "Primary Connects",
            "Preemptive FO",
            "Warm Hits",
            "Backup Sel",
            "Probes ok/fail",
            "Prewarm",
        ],
        table_rows,
    )

    add_heading(doc, "VII.B Additional Scenarios", 2)
    add_para(
        doc,
        f"Window-start (13:55–14:10; failures counted only in 14:00–14:05): reactive {uf(win_r)}, "
        f"circuit breaker {uf(win_c)}, predictive {uf(win_p)} user-facing connect failures.",
    )
    add_para(
        doc,
        f"Pattern-shift (learn hour 14 for 7 days; day 8 bad hour moves to 15): at 14:00–14:30 predictive "
        f"backup selections {fmt(shift14['mean_backup_selections'], shift14['sd_backup_selections'])} "
        f"with {uf(shift14)} user-facing failures; at 15:00–15:30 user-facing failures {uf(shift15)} "
        f"with backup share {fmt(shift15['mean_backup_selections'], shift15['sd_backup_selections'])}.",
    )
    add_para(
        doc,
        f"Reuse experiment (1000 healthy-hour requests, predictive): reuse-off physical connects "
        f"{fmt(reuse_off['mean_physical_connects'], reuse_off['sd_physical_connects'])}, "
        f"latency mean/p95 "
        f"{fmt(reuse_off['mean_latency_sim_ms'], reuse_off['sd_latency_sim_ms'])}/"
        f"{fmt(reuse_off['mean_p95_latency_sim_ms'], reuse_off['sd_p95_latency_sim_ms'])}; "
        f"reuse-on physical connects "
        f"{fmt(reuse_on['mean_physical_connects'], reuse_on['sd_physical_connects'])}, "
        f"latency mean/p95 "
        f"{fmt(reuse_on['mean_latency_sim_ms'], reuse_on['sd_latency_sim_ms'])}/"
        f"{fmt(reuse_on['mean_p95_latency_sim_ms'], reuse_on['sd_p95_latency_sim_ms'])}.",
    )

    add_heading(doc, "VII.C Discussion", 2)
    add_para(
        doc,
        "In the bad window, predictive routing nearly eliminates user-facing connect failures via "
        "hot-hour / risk failover and warm backup hits, outperforming reactive and still beating the "
        "circuit breaker, which must open after paying initial primary failures each open cycle. "
        f"Healthy-hour backup share is {morn_p_backup} for predictive versus {morn_c_backup} for the "
        "circuit breaker (CB remains open longer after morning bursts); predictive still reports fewer "
        "user-facing connect failures in that window. Probe and pre-warm connects are background work and "
        "are not counted as user-facing connect failures.",
    )

    add_heading(doc, "VII.D Threats to Validity", 2)
    add_para(
        doc,
        "Internal: simulator patterns (85% fail at hour 14, bursts, reset-after-47) are synthetic; "
        "EWMA choices affect sticky avoidance; sequential day-8 windows share history. "
        "External: results may not transfer to production WAN traces or multi-region topologies. "
        "Construct: user-facing connect failures count checkout failures visible to callers; "
        "probe/pre-warm connects are separate. Conclusion: limited to one flaky configuration and n seeds; "
        "Review-1 cited more papers than this Review-2 summary.",
    )

    add_heading(doc, "VIII. CONCLUSION", 1)
    add_para(
        doc,
        "A history-aware JDBC pool can learn structured failure patterns from its own attempts and "
        f"pre-emptively avoid a known bad hour. Under the learn-then-measure protocol, predictive mode reduced "
        f"bad-window user-facing connect failures from {react_fail} (reactive) and {cb_fail} (circuit breaker) "
        f"to {pred_fail}, with warm hits serving failovers. Limitations and threats above apply; "
        "future work includes trace-driven evaluation and auto-tuned weights.",
    )

    add_heading(doc, "REFERENCES", 1)
    for r in [
        '[1] S. Tuli et al., "PreGAN+," IEEE TMC, 2024.',
        '[2] M. Mohammad, "Resilient Microservices…," arXiv:2512.16959, 2025.',
        '[3] A. Nouruzi et al., "AI-Based E2E Resilient… 6G," IEEE TNSE, 2025.',
        '[4] S. Tripathi et al., "GQAT-Net," Journal of Grid Computing, 2025.',
        '[5] E. Ferrer et al., "Inter-Satellite Link Prediction…," 2024.',
        '[6] C. Yan and B. Mafakheri, "Satellite Connectivity Prediction…," 2025.',
        '[7] W. Symbor and Ł. Falas, "…IoT… Prediction-Based Resource Allocation," Sensors, 2025.',
        '[8] C. Zhang et al., "Deoxys," ACM SoCC, 2024.',
        '[9] W. Xiao et al., "FT-MoE," arXiv:2504.20446, 2025.',
        '[10] Y. Zhang et al., "Fault-Tolerant Scheduling…," Sensors, 2024.',
        '[11] C. Ji and H. Luo, "Cloud-Based AI Systems…," arXiv:2505.11743, 2025.',
        '[12] C.-W. Huang et al., "Resilient and Reliable Cloud Network Control…," 2025.',
        '[13] N. Hayashibara et al., "The ϕ Accrual Failure Detector," IEEE SRDS, 2004.',
        '[14] Netflix, "Hystrix," GitHub.',
        '[15] "Self-Adaptive Dynamic Connection Pool Management Method," ACM ICSIM, 2022.',
        '[16] A. Aral and I. Brandic, "Learning Spatiotemporal Failure Dependencies…," IEEE TPDS, 2021.',
    ]:
        add_para(doc, r, size=10, space_after=4)

    add_heading(doc, "APPENDIX A — How to Run", 1)
    add_para(
        doc,
        "cd pattern-aware-pool\n"
        "export JAVA_HOME=...(JDK 17+)\n"
        "./mvnw test\n"
        "./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.FullSystemDemo\n"
        "./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner",
        size=10,
    )

    doc.save(OUT)
    print("Wrote", OUT)

    md = f"""# Review-2 Complete Research Paper

**Authors:** Sara Sharma (23BCE0967), Tanvi Singh (23BCE2155)

> Full DOCX: `{OUT.name}` — numbers loaded from `experiment_summary.csv` (n={n}).

## Bad-window user-facing connect failures (mean±sd)

| Mode | User-Facing Failures / 100 req | Preemptive Failovers | Warm Hits | Probes ok/fail | Prewarm |
|---|---:|---:|---:|---:|---:|
| Reactive | {react_fail} | 0.0±0.0 | 0.0±0.0 | — | — |
| Circuit breaker | {cb_fail} | 0.0±0.0 | 0.0±0.0 | — | — |
| Predictive | {pred_fail} | {pred_fo} | {pred_warm} | {pred_probes} | {pred_prewarm} |

## Additional scenarios

| Scenario | Metric | Value |
|---|---|---|
| window-start (predictive) | user-facing failures (14:00–14:05) | {uf(win_p)} |
| pattern-shift-h14 (predictive) | backup selections | {fmt(shift14['mean_backup_selections'], shift14['sd_backup_selections'])} |
| pattern-shift-h15 (predictive) | user-facing failures | {uf(shift15)} |
| reuse-off (predictive) | physical connects | {fmt(reuse_off['mean_physical_connects'], reuse_off['sd_physical_connects'])} |
| reuse-on (predictive) | physical connects | {fmt(reuse_on['mean_physical_connects'], reuse_on['sd_physical_connects'])} |
| morning-healthy backup share | predictive vs CB | {morn_p_backup} vs {morn_c_backup} |

## Protocol
7-day live learning → day-8 measure; seeded Random 1..{n}; no answer-seeding; routing experiments reuseEnabled=false; recoveryProbeSeconds=30; identical backgroundTick cadence; simulated latency (no sleep).

## Stack notes
Swing (not JavaFX); Spring-compatible lifecycle class (not Spring); real IdleConnectionPool reuse; recovery probes; routing = score OR hot-hour (EWMA) OR live cluster OR reset prediction.

## Commands
```bash
cd pattern-aware-pool
export JAVA_HOME=...(JDK 17+)
./mvnw test
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.FullSystemDemo
./mvnw -q exec:java -Ddemo.mainClass=com.college.pap.demo.ExperimentRunner
```
"""
    MD_OUT.write_text(md, encoding="utf-8")
    print("Wrote", MD_OUT)


if __name__ == "__main__":
    build()
