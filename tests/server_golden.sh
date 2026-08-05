#!/usr/bin/env bash
# Server golden gate — eleven original-Clojure behavior comparisons.
#
# Capture precedes any Beagle port:
#   tests/server_golden.sh capture tests/goldens/coord_daemon
# Verify later implementations byte-for-byte:
#   tests/server_golden.sh verify tests/goldens/coord_daemon
#
# Every case runs in a fresh process against a fixed fixture path. The fixed path
# removes path entropy at the source. There are ZERO masks: no sed, filtering, or
# output rewriting. The compared artifacts are each case's raw stdout, stderr,
# and process exit status.
#
# The default wire transport is a real ephemeral loopback port. Managed sandboxes
# that forbid TCP listen(2) can set FRAM_SERVER_GOLDEN_TRANSPORT=memory; that mode
# passes the same bytes through server/serve-conn using a test-only Socket
# proxy. Both modes exercise the request parser, newline framing, response
# formatter, error boundary, and close path; port mode additionally covers
# ServerSocket bind/accept.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:?usage: server_golden.sh capture|verify <dir>}"
GOLD="${2:?usage: server_golden.sh capture|verify <dir>}"
DAEMON="${FRAM_SERVER_DAEMON:-$HERE/server.clj}"
WORK="${TMPDIR:-/tmp}/server-golden-run"
TRANSPORT="${FRAM_SERVER_GOLDEN_TRANSPORT:-port}"

case "$MODE" in
  capture|verify) ;;
  *) echo "server_golden: mode must be capture or verify" >&2; exit 2 ;;
esac
case "$TRANSPORT" in
  port|memory) ;;
  *) echo "server_golden: transport must be port or memory" >&2; exit 2 ;;
esac

if command -v flock >/dev/null 2>&1; then
  exec 9>"$WORK.lock"
  flock -w 1800 9 || {
    echo "server_golden: another run holds $WORK.lock" >&2
    exit 2
  }
fi

rm -rf "${WORK:?}"
mkdir -p "$WORK"

cat >"$WORK/probe.clj" <<'EOF'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fram.store :as c]
         '[fram.schema :as s])

(def root (System/getenv "FRAM_GOLDEN_ROOT"))
(def daemon (System/getenv "FRAM_GOLDEN_DAEMON"))
(def case-name (first *command-line-args*))
(def transport (System/getenv "FRAM_GOLDEN_TRANSPORT"))
(binding [*command-line-args* []]
  (load-file daemon))

(def case-dir (io/file "/tmp/server-golden-run/cases" case-name))
(.mkdirs case-dir)
(def flat (.getCanonicalPath (io/file case-dir "facts.log")))

(defn line [tx op l p r]
  (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "golden"}))

(defn seed! []
  (spit flat
        (str (line 1 "assert" "@title" "cardinality" "single") "\n"
             (line 2 "assert" "@A" "title" "Alpha") "\n"
             (line 3 "assert" "@A" "tag" "x") "\n")))

(defn golden-boot! []
  ;; The golden observes the fold boot, never an old checkpoint left by another
  ;; case. Disabling optional acceleration changes no store semantics. The boot
  ;; diagnostic includes elapsed milliseconds, which are telemetry rather than
  ;; behavior; assert last-boot's stable fields in boot-existing instead.
  (reset! snapshot-boot-enabled? false)
  (reset! mmap-image-enabled? false)
  (binding [*out* (java.io.StringWriter.)]
    (boot-flat! flat)))

(defn wire-memory
  "Run the production one-request connection handler over exact UTF-8 bytes.
   Returned text includes the production newline terminator."
  [request]
  (let [in (java.io.ByteArrayInputStream.
            (.getBytes ^String request java.nio.charset.StandardCharsets/UTF_8))
        out (java.io.ByteArrayOutputStream.)
        sock (proxy [java.net.Socket] []
               (getInputStream [] in)
               (getOutputStream [] out)
               (setSoTimeout [_] nil)
               (close [] nil))]
    (serve-conn sock)
    (.toString out "UTF-8")))

(defn wire-port [request]
  (with-open [server (java.net.ServerSocket. 0)]
    (let [worker (future
                   (with-open [accepted (.accept server)]
                     (serve-conn accepted)))
          captured (java.io.ByteArrayOutputStream.)]
      (with-open [client (java.net.Socket.)]
        (.connect client
                  (java.net.InetSocketAddress.
                   "127.0.0.1" (.getLocalPort server))
                  2000)
        (.setSoTimeout client 5000)
        (let [output (.getOutputStream client)]
          (.write output
                  (.getBytes ^String request
                             java.nio.charset.StandardCharsets/UTF_8))
          (.flush output))
        (io/copy (.getInputStream client) captured))
      @worker
      (.toString captured "UTF-8"))))

(defn wire [request]
  (case transport
    "port" (wire-port request)
    "memory" (wire-memory request)
    (throw (ex-info "unknown golden transport" {:transport transport}))))

(defn emit-wire [request]
  (print (wire request))
  (flush))

(defn domain-triples [db0]
  (->> (reified->facts db0)
       (map (fn [f] [(:l f) (:p f) (:r f)]))
       (sort-by pr-str)
       vec))

(case case-name
  "wire-edn-version"
  (do (seed!) (golden-boot!) (emit-wire "{:op :version}\n"))

  "wire-json-version"
  (do (seed!) (golden-boot!) (emit-wire "{:op :version :fmt :json}\n"))

  "wire-assert-resolved"
  (do (seed!)
      (golden-boot!)
      (emit-wire
       "{:op :assert :te \"@B\" :p \"title\" :r \"Beta\" :base 3}\n")
      (emit-wire "{:op :resolved :te \"@B\" :p \"title\"}\n"))

  "wire-unknown-op"
  (do (seed!) (golden-boot!) (emit-wire "{:op :definitely-unknown}\n"))

  "wire-bad-request"
  (do (seed!) (golden-boot!) (emit-wire "{:op\n"))

  "wire-unknown-verb"
  (do (seed!)
      (golden-boot!)
      (emit-wire
       "{:op :edit-min :spec {:op \"explode\" :module \"demo\"}}\n"))

  "migrate-flat"
  (do (seed!)
      (let [db0 (migrate-flat->database flat)]
        (prn {:version (current-seq db0)
              :triples (domain-triples db0)})))

  "boot-existing"
  (do (seed!)
      (golden-boot!)
      (prn {:version (current-seq @database)
            :facts (hybrid-fact-count)
            :mode (:mode @last-boot)
            :reason (:reason @last-boot)
            :title (s/lookup (:store @database)
                             (s/resolve-name (:store @database) "@A")
                             "title")}))

  "reload-append"
  (do (seed!)
      (golden-boot!)
      (spit flat
            (str (line 4 "assert" "@B" "title" "Tail") "\n")
            :append true)
      (let [result (maybe-reload!)
            st (:store @database)]
        (prn {:reload result
              :version (current-seq @database)
              :built-through @built-through
              :generation @reload-generation
              :title (s/lookup st (s/resolve-name st "@B") "title")})))

  "reload-torn"
  (do (seed!)
      (golden-boot!)
      ;; EDN-valid, append-torn tail: version advances, incomplete state does not.
      (spit flat
            (str (pr-str {:tx 4 :op "assert" :l "@TORN" :p "title"}) "\n")
            :append true)
      (let [result (maybe-reload!)
            st (:store @database)]
        (prn {:reload result
              :version (current-seq @database)
              :built-through @built-through
              :torn-present (boolean (s/resolve-name st "@TORN"))})))

  "shutdown-checkpoint"
  (do (seed!)
      (golden-boot!)
      ;; This is the exact body invoked by start-snapshot-writer!'s JVM shutdown
      ;; hook, called directly so hook scheduling cannot introduce race entropy.
      (reset! snapshot-boot-enabled? true)
      (reset! last-snapshot-seq -1)
      (snapshot-if-dirty! "shutdown")
      (let [sidecar (read-sidecar flat)]
        (prn {:sidecar
              (select-keys sidecar
                           [:seq :image :byte_offset :fact_count :hash
                            :log_identity])
              :image-exists (.exists (io/file (:image sidecar)))
              :tmp-left-behind
              (or (.exists (io/file (str (:image sidecar) ".tmp")))
                  (.exists (io/file (str flat ".snap.tmp"))))})))

  (throw (ex-info "unknown server golden case" {:case case-name})))
EOF

CASES=(
  wire-edn-version
  wire-json-version
  wire-assert-resolved
  wire-unknown-op
  wire-bad-request
  wire-unknown-verb
  migrate-flat
  boot-existing
  reload-append
  reload-torn
  shutdown-checkpoint
)

run_case() {
  local case_name="$1"
  local rc
  set +e
  (
    cd "$HERE"
      FRAM_GOLDEN_ROOT="$HERE" \
      FRAM_GOLDEN_DAEMON="$DAEMON" \
      FRAM_GOLDEN_TRANSPORT="$TRANSPORT" \
      timeout 240 clojure -M "$WORK/probe.clj" "$case_name"
  ) >"$WORK/$case_name.out" 2>"$WORK/$case_name.err"
  rc=$?
  set -e
  echo "$rc" >"$WORK/$case_name.rc"
}

i=0
for case_name in "${CASES[@]}"; do
  i=$((i + 1))
  echo "server_golden: RUN $i/${#CASES[@]} $case_name [$TRANSPORT]"
  run_case "$case_name"
done

bad=0
for case_name in "${CASES[@]}"; do
  if [ "$(tr -d '\n' <"$WORK/$case_name.rc")" != 0 ]; then
    echo "server_golden: case failed: $case_name (rc $(cat "$WORK/$case_name.rc"))" >&2
    sed -n '1,80p' "$WORK/$case_name.err" >&2
    bad=1
  fi
done
[ "$bad" = 0 ] || exit 1

if [ "$MODE" = capture ]; then
  mkdir -p "$GOLD"
  for case_name in "${CASES[@]}"; do
    for ext in out err rc; do
      cp "$WORK/$case_name.$ext" "$GOLD/$case_name.$ext"
    done
  done
  echo "server_golden: captured ${#CASES[@]} cases -> $GOLD"
  exit 0
fi

fail=0
for case_name in "${CASES[@]}"; do
  for ext in out err rc; do
    if ! cmp -s "$WORK/$case_name.$ext" "$GOLD/$case_name.$ext"; then
      echo "DRIFT $case_name.$ext"
      diff -u "$GOLD/$case_name.$ext" "$WORK/$case_name.$ext" | head -40 || true
      fail=1
    fi
  done
done

if [ "$fail" = 0 ]; then
  echo "server_golden: ALL ${#CASES[@]} cases byte-identical (zero masks)"
fi
exit "$fail"
