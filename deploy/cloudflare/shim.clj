#!/usr/bin/env bb
;; shim.clj — the HTTP<->TCP bridge between Cloudflare Workers and the Fram
;; coordinator daemon.
;;
;; Why it exists: the coordinator's wire protocol is one line of EDN over raw
;; TCP, unauthenticated BY DESIGN (docs/coordinator-bind-and-wire.md — auth is
;; the gateway's job). Cloudflare Workers can open raw TCP (cloudflare:sockets
;; connect()) and speak TLS, but they cannot present a CLIENT certificate on a
;; raw socket, so the engine's mTLS mode is unreachable from a Worker — and the
;; raw plaintext port must never be published. This shim IS the authenticating
;; gateway: it sits on the same private Docker network as the daemon, checks a
;; bearer token, and forwards exactly one EDN line per request over loopback-
;; grade plaintext TCP.
;;
;;   POST /q       {:op :query|:query-page|:as-of|:version|:status|:validate ...}
;;   POST /assert  {:op :assert|:retract|:assert-at-version|:bump ...}
;;
;; Body: ONE EDN request map (the daemon's native request shape). Replies are
;; EDN by default. A request carrying :fmt :json asks the daemon for JSON and
;; receives content-type application/json. The shim re-prints the parsed map
;; (pr-str) before forwarding — that guarantees a single well-formed line and
;; drops any smuggled trailing input.
;;
;; Deliberately NOT here: TLS (terminate at your reverse proxy / tunnel),
;; multi-tenancy, rate limiting, :subscribe (streaming doesn't fit
;; one-request/one-response HTTP).
(require '[org.httpkit.server :as srv]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '[java.net Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter])

(def fram-host (or (System/getenv "FRAM_HOST") "127.0.0.1"))
(def fram-port (parse-long (or (System/getenv "FRAM_PORT") "7977")))
(def shim-port (parse-long (or (System/getenv "SHIM_PORT") "8787")))

;; fail CLOSED: no token, no server. SHIM_TOKEN_FILE supports Docker secrets.
(def token
  (or (System/getenv "SHIM_TOKEN")
      (some-> (System/getenv "SHIM_TOKEN_FILE") slurp str/trim not-empty)
      (do (binding [*out* *err*]
            (println "FATAL: set SHIM_TOKEN or SHIM_TOKEN_FILE (bearer token the Worker must present)"))
          (System/exit 2))))

(def max-body-bytes (* 1024 1024))     ; matches the daemon's 1 MiB line cap

(def allowed-ops
  {"/q"      #{:query :query-page :as-of :version :status :validate}
   "/assert" #{:assert :retract :assert-at-version :bump}})

(defn- authorized? [req]
  (let [h (get (:headers req) "authorization" "")
        presented (if (str/starts-with? h "Bearer ") (subs h 7) "")]
    ;; constant-time compare — no timing oracle on the token
    (java.security.MessageDigest/isEqual
     (.getBytes ^String presented "UTF-8") (.getBytes ^String token "UTF-8"))))

;; the daemon protocol: ONE connection = ONE request line = ONE response line.
;; (Extra bytes after a query request line make the daemon cancel it —
;; :unexpected-client-input — so never pool/pipeline these sockets.)
(defn- coord-round-trip [^String line]
  (with-open [s (doto (Socket.)
                  (.connect (InetSocketAddress. ^String fram-host (int fram-port)) 3000)
                  (.setSoTimeout 15000))]
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s) "UTF-8"))
          r (BufferedReader. (InputStreamReader. (.getInputStream s) "UTF-8"))]
      (.write w line) (.newLine w) (.flush w)
      (.readLine r))))

(defn- json-format? [fmt]
  (or (= :json fmt) (= "json" fmt)))

(defn- accepts-json? [req]
  (str/includes?
   (str/lower-case (get (:headers req) "accept" ""))
   "application/json"))

(defn- wire-resp [format status body]
  {:status status
   :headers {"content-type" (if (= :json format)
                              "application/json"
                              "application/edn")}
   :body (str (cond
                (string? body) body
                (= :json format) (json/generate-string body)
                :else (pr-str body))
              "\n")})

(defn handler [req]
  ;; Accept selects the shape of shim-originated errors that happen before a
  ;; valid request map exists. Once parsed, :fmt is authoritative because it is
  ;; also what selects the coordinator's response serializer.
  (let [path (:uri req)
        pre-parse-format (if (accepts-json? req) :json :edn)]
    (cond
      (not (authorized? req))
      (wire-resp pre-parse-format 401 {:error "unauthorized"})

      (not= :post (:request-method req))
      (wire-resp pre-parse-format 405 {:error "POST only"})

      (not (contains? allowed-ops path))
      (wire-resp pre-parse-format 404 {:error "unknown path (POST /q or POST /assert)"})

      :else
      (let [body (some-> (:body req) slurp)]
        (cond
          (or (nil? body) (str/blank? body))
          (wire-resp pre-parse-format 400 {:error "empty body (want one EDN request map)"})

          (> (alength (.getBytes ^String body "UTF-8")) max-body-bytes)
          (wire-resp pre-parse-format 400 {:error "body too large (1 MiB cap)"})

          :else
          (let [parsed (try (edn/read-string body) (catch Exception _ ::bad))]
            (cond
              (or (= ::bad parsed) (not (map? parsed)))
              (wire-resp pre-parse-format 400
                         {:error "body must be one EDN request map, e.g. {:op :version}"})

              :else
              (let [format (if (json-format? (:fmt parsed)) :json :edn)]
                (if-not (contains? (allowed-ops path) (:op parsed))
                  (wire-resp format 403
                             {:error (str "op " (pr-str (:op parsed))
                                          " not allowed on " path)})
                  (try
                    (if-let [reply (coord-round-trip (pr-str parsed))]
                      (wire-resp format 200 reply)
                      (wire-resp format 502
                                 {:error "coordinator closed the connection without a reply"}))
                    (catch Exception e
                      (wire-resp format 502
                                 {:error (str "coordinator unreachable: "
                                              (.getMessage e))}))))))))))))

(srv/run-server handler {:ip "0.0.0.0" :port shim-port})
(println (str "fram-shim listening on 0.0.0.0:" shim-port
              " -> coordinator " fram-host ":" fram-port))
@(promise)
