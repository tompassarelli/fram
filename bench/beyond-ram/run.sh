#!/usr/bin/env bash
# bench/beyond-ram/run.sh — FRAM_MMAP_IMAGE RSS bench driver (thread 019f82d9).
# Generates one synthetic code-graph corpus, then boots THREE measured daemons
# SEQUENTIALLY (fresh JVM each, so heaps + RSS don't cross-contaminate) and prints
# a comparison table:
#   off     baseline (whole-log fold + warm cache)
#   on      FRAM_MMAP_IMAGE mmap-cold, NO whole-corpus op (the RSS scenario)
#   on-mat  mmap-cold + one whole-corpus op (documents lazy-materialize cost)
# The daemon runs on the JVM (clojure -M) — the representative runtime, matching prod.
#   BENCH_FACTS=1000000 bench/beyond-ram/run.sh
set -euo pipefail
ROOT="/home/tom/code/fram"
N="${BENCH_FACTS:-1000000}"
WORK="${BENCH_WORK:-/tmp/fram-beyond-ram}"
mkdir -p "$WORK"
LOG="$WORK/corpus.log"

echo "# generating $N-fact corpus -> $LOG"
bb "$ROOT/bench/beyond-ram/gen.clj" "$LOG" "$N"
echo "# corpus bytes: $(wc -c < "$LOG")  lines: $(wc -l < "$LOG")"
echo "# cores: $(nproc)  loadavg: $(cut -d' ' -f1-3 /proc/loadavg)"

: > "$WORK/rows.edn"
measure () {   # mode logfile  -> extracts the marked EDN row
  ( cd "$ROOT" && clojure -M bench/beyond-ram/measure.clj "$1" "$2" 2>/dev/null \
      | sed -n 's/^BENCHROW //p' )
}
for mode in off on on-mat; do
  cp "$LOG" "$WORK/$mode.log"                 # isolate each mode's checkpoint/sidecar
  rm -rf "${WORK:?}/$mode.log.snapshots" "$WORK/$mode.log.snap"
  echo "# ---- mode: $mode ----"
  # on/on-mat: a throwaway process writes the checkpoint FIRST, so the measured
  # boot is a fresh mmap-cold boot uncontaminated by the fold+writer peak.
  if [ "$mode" != "off" ]; then measure prep "$WORK/$mode.log" >/dev/null; fi
  row="$( measure "$mode" "$WORK/$mode.log" )"
  echo "$row" | tee -a "$WORK/rows.edn"
done

echo "# ================= RESULTS ================="
( cd "$ROOT" && bb -e '
(require (quote [clojure.edn :as edn]) (quote [clojure.string :as str]))
(def rows (->> (slurp (str (or (System/getenv "BENCH_WORK") "/tmp/fram-beyond-ram") "/rows.edn"))
               str/split-lines (remove str/blank?) (map edn/read-string)))
(println (format "%-8s %8s %9s %8s %8s %8s %10s %10s %8s"
                 "mode" "boot-ms" "facts" "vmRSS" "vmHWM" "heap" "bylp-lit" "bylp-ref" "by-l"))
(doseq [r rows]
  (println (format "%-8s %8s %9s %8s %8s %8s %10s %10s %8s"
                   (name (:mode r)) (:boot-ms r) (:facts r) (:vmrss-mib r) (:vmhwm-mib r)
                   (:heap-mib r) (:by-lp-lit-us r) (:by-lp-ref-us r) (:by-l-us r))))
(println "(RSS in MiB; latencies in us/lookup, JIT-warmed steady state)")
(let [off (first (filter #(= :off (:mode %)) rows))
      on  (first (filter #(= :on  (:mode %)) rows))]
  (when (and off on)
    (println (format "\nRSS win (on vs off): heap %.1fx  vmRSS %.1fx  vmHWM %.1fx"
                     (/ (double (:heap-mib off)) (max 1 (:heap-mib on)))
                     (/ (double (:vmrss-mib off)) (max 1 (:vmrss-mib on)))
                     (/ (double (:vmhwm-mib off)) (max 1 (:vmhwm-mib on)))))
    (println (format "by-lp latency on-cold vs off-warm: literal %.2fx  ref %.2fx"
                     (/ (double (:by-lp-lit-us on)) (max 1 (:by-lp-lit-us off)))
                     (/ (double (:by-lp-ref-us on)) (max 1 (:by-lp-ref-us off)))))))
'
)
