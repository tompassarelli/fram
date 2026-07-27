#!/usr/bin/env bash
# Runtime golden gate — capture the observable, deterministic rt.clj helpers
# before moving a pure slice to Beagle.  Each case loads FRAM_RT in isolation so
# the original and a future emitted runtime are compared with no masks.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:?usage: rt_golden.sh capture|verify <dir>}"
GOLD="${2:?usage: rt_golden.sh capture|verify <dir>}"
RT="${FRAM_RT:-$HERE/src/fram/rt.clj}"
WORK="${TMPDIR:-/tmp}/rt-golden-run"

rm -rf "${WORK:?}"
mkdir -p "$WORK/fram"
cp "$RT" "$WORK/fram/rt.clj"

cat >"$WORK/probe.clj" <<'EOF'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[fram.rt :as rt])

(def fixture "/tmp/rt-golden-fixture.log")
(defn emit [x] (prn x))
(case (first *command-line-args*)
  "codec" (emit {:encoded (rt/base64url-encode-utf8 "héllo / +")
                 :decoded (rt/base64url-decode-utf8 "aMOpbGxvIC8gKw")
                 :bad (try (rt/base64url-decode-utf8 "!!!") :accepted
                           (catch Throwable t (class t)))})
  "strings" (emit {:split (rt/split-on "a::b::" "::")
                   :kv-a (rt/split-kv " owner   personal plan")
                   :kv-b (rt/split-kv " lone")
                   :slug-a (rt/slugify "  Hello, Fram!  ")
                   :slug-b (rt/slugify "___")
                   :lt [(rt/str-lt? "a" "b") (rt/str-lt? "b" "a")]})
  "ids" (emit {:bump (rt/bump-id "2026-12-31-235959")
               :slug (rt/file-slug "/tmp/2026-06-15-150040_pick_up.md")})
  "envelope" (let [r {:edit_batch/envelope 1 :edit_batch/tx 7
                        :edit_batch/count 1 :edit_batch/sha256 "abc"}
                     bad (assoc r :edit_batch/count 2)]
               (emit {:marker (rt/edit-batch-envelope-marker? r)
                      :seal (rt/edit-batch-envelope-seal r)
                      :valid (rt/valid-edit-batch-envelope? r)
                      :bad (rt/valid-edit-batch-envelope? bad)
                      :sha (rt/sha256-text "fram")}))
  "paths" (emit {:lock (rt/rewrite-lock-path fixture)
                  :intent (rt/rewrite-intent-path fixture)
                  :coord-tmp (rt/rewrite-coord-tmp-path fixture)
                  :telem-tmp (rt/rewrite-telem-tmp-path fixture)
                  :floor (rt/rollback-floor-id)})
  "generation" (do (spit fixture (str (pr-str {:tx 1 :op "assert" :l "@log:gen" :p "generation" :r "g"}) "\n"))
                   (emit {:managed (rt/generation-managed? fixture)
                          :ops (mapv #(select-keys % [:tx :op :l :p :r]) (rt/read-log fixture))}))
  "torn" (do (spit fixture "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\"}\n{:tx")
             (emit (mapv #(select-keys % [:tx :op :l :p :r]) (rt/read-log fixture))))
  "iso" (emit {:int [(rt/parse-int "42") (rt/parse-int "x")]
               :seconds [(rt/iso-to-seconds "1970-01-01T00:00:00Z")
                         (rt/iso-to-seconds "not-a-time")]
               :shapes [(rt/is-iso-datetime-19 "2026-07-27T10:11:12")
                        (rt/is-iso-datetime-16 "2026-07-27T10:11")
                        (rt/is-iso-datetime-19 "bad")]})
  "text" (emit {:digits (rt/filter-digits "vGUARD-019f")
                :repeat (rt/repeat-str "ab" 3)
                :repeat-negative (rt/repeat-str "ab" -1)
                :json (rt/to-json {:b 2 :a [1 true]})})
  "edn" (emit {:quoted (rt/edn-quote "a\\nb\"")
               :unquoted (rt/edn-unquote "\"a b\"")
               :parsed [(rt/parse-edn "{:a 1}") (rt/parse-edn "{")]})
  "files" (do (spit fixture "one")
              (emit {:exists-before (rt/file-exists fixture)
                     :canonical (rt/canonical-log-path fixture)}))
  (throw (ex-info "unknown rt golden case" {:case (first *command-line-args*)})))
EOF

CASES="codec strings ids envelope paths generation torn iso text edn files"
run_case() {
  local case_name="$1"
  local rc
  set +e
  bb -cp "$WORK:$HERE/out" "$WORK/probe.clj" "$case_name" \
    >"$WORK/$case_name.out" 2>"$WORK/$case_name.err"
  rc=$?
  set -e
  echo "$rc" >"$WORK/$case_name.rc"
}

for case_name in $CASES; do run_case "$case_name"; done

if [ "$MODE" = capture ]; then
  mkdir -p "$GOLD"
  for case_name in $CASES; do
    for ext in out err rc; do cp "$WORK/$case_name.$ext" "$GOLD/$case_name.$ext"; done
  done
  echo "rt_golden: captured $(echo "$CASES" | wc -w) cases -> $GOLD"
  exit 0
fi

fail=0
for case_name in $CASES; do
  for ext in out err rc; do
    if ! cmp -s "$WORK/$case_name.$ext" "$GOLD/$case_name.$ext"; then
      echo "DRIFT $case_name.$ext"
      diff -u "$GOLD/$case_name.$ext" "$WORK/$case_name.$ext" | head -40 || true
      fail=1
    fi
  done
done
[ "$fail" = 0 ] && echo "rt_golden: ALL $(echo "$CASES" | wc -w) cases byte-identical"
exit "$fail"
