# W8 VS-GIT receipts

Regenerate from a clean checkout with `MOAT_N=500 bash bench/moat/rename-at-scale.sh`, `MOAT_N=500 bash bench/moat/blast-radius.sh`, and `MOAT_N=500 bash bench/moat/cold-start.sh`. Each creates a `/tmp/fram-moat.*` fixture, ingests it into its own `code.log`, and starts a daemon on a discovered port; neither reads `.fram/code.log`, `:7977`, nor `:32915`.

Observed on this box, 2026-07-28: git `2.54.0`; `bb` `1.12.218`; `MOAT_N=100`. Bootstrap includes fixture generation, ingest, daemon startup, and its first fold; it is intentionally not hidden inside an edit/query number.

| scenario | graph | git/text | honesty note |
| --- | --- | --- | --- |
| rename-at-scale | bootstrap 1571.137 ms; edit 2304.940 ms; 2 ops | rewrite + commit 11.837 ms | **Git wins raw write latency** on this run. The graph delta remains two operations for 100 sites. |
| blast-radius | bootstrap 2571.378 ms; query 1108.977 ms; 101 callers | grep + textual re-derive 4.642 ms; 101 callers | Correctness matched; **text wins this one-shot cold-process timing**. |
| K-writer propagation | cannot-measure | cannot-measure | Existing precedent needs canonical `.fram/code.log`, prohibited by W8. |
| merge-vs-compose | cannot-measure | cannot-measure | No stable local compose driver was found; a fake selection is not evidence. |
| cold-start | bootstrap + fold 1705.324 ms; append + index 2439.777 ms | clone + checkout 15.321 ms; commit 14.336 ms | **Git wins both reported raw-cost columns** on this synthetic local corpus. |

Methodology: rename has one definition plus `MOAT_N` syntactic reference sites. Blast is `target <- d0 <- … <- dN`. Git arms use isolated local repositories and no network. Graph arms call existing `fram-ingest-code`, `coord_daemon.clj serve-flat`, and `fram-edit-code rename`; the graph rename reports the minimal two-operation delta rather than a whole-module rewrite.
