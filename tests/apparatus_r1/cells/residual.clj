;; Generation-bound byte-count + sha residual oracle. Confirms the recorded
;; boundary (:src_telem_bytes / :src_telem_sha) EXACTLY measures the consumed
;; telemetry prefix under exclusive-read semantics (byte-count == st_size of the
;; consumed region, sha over exactly those bytes), and that a LEGITIMATE identical
;; post-generation append beyond that boundary is preserved as an authored event.
;; Usage: bb -cp lib cells/residual.clj <scratch-root>
(require '[r1.model :as m] '[r1.harness :as h] '[clojure.java.io :as io])

(def root (or (first *command-line-args*) "/tmp/r1-residual"))
(.mkdirs (io/file root))

(def prefix "{:tx 1 :p \"e\" :r \"a\"}\n{:tx 2 :p \"e\" :r \"b\"}\n")
(def prefix-bytes (m/str->bytes prefix))
(def gen (m/bytes->str (m/gen-record-bytes {:tx 3 :gen-n 1 :telem-prefix prefix})))
(def coord (str gen "\n" prefix))
;; a legitimate later append, byte-identical to retained line 1, lands beyond boundary
(def append "{:tx 1 :p \"e\" :r \"a\"}\n")
(def telem-mid (str prefix append)) ; mid-residual crash state
(def cf (io/file root "coordination.log"))
(def tf (io/file root "telemetry.log"))
(spit cf coord) (spit tf telem-mid)

(h/section "Generation-bound residual oracle")
(def grec (m/generation-record (remove :torn (m/split-lines (m/read-bytes (.getPath cf))))))
;; recorded boundary must equal the exact consumed byte-count.
(h/check! "recorded :src_telem_bytes == consumed prefix byte-count"
          (alength prefix-bytes) (long (:src_telem_bytes grec)))
;; recorded sha must equal sha256-16hex of exactly the consumed prefix bytes.
(h/check! "recorded :src_telem_sha == sha256-16hex of consumed prefix"
          (m/sha256-16hex prefix-bytes) (:src_telem_sha grec))
;; residual region of the live telemetry must byte-equal the recorded prefix.
(def live-telem (m/read-bytes (.getPath tf)))
(h/check! "live residual [0,boundary) sha matches recorded"
          (:src_telem_sha grec)
          (m/sha256-16hex (m/prefix-bytes live-telem (:src_telem_bytes grec))))

(h/section "Legitimate identical post-generation append is preserved (not shadowed)")
(def bnd (m/b2-shadow-boundary (remove :torn (m/split-lines (m/read-bytes (.getPath cf)))) live-telem))
(h/check! "boundary valid over mid-residual state" true (:valid bnd))
;; the appended line lies BEYOND the boundary => authored.
(def append-line (last (remove :torn (m/split-lines live-telem))))
(h/check! "post-generation append lies beyond boundary (authored, not shadow)"
          false (m/b2-telem-shadow? bnd append-line))
;; and B-prime would (wrongly) shadow it by byte-equality.
(h/check! "B-prime byte-equality would suppress the legitimate append"
          true (m/bprime-telem-shadow?
                (m/coord-line-byteset (remove :torn (m/split-lines (m/read-bytes (.getPath cf)))))
                append-line))

(h/finish!)
