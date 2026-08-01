(ns native-rpc-client
  (:require [coord-daemon-wire :as wire]
            [fram.types :as t])
  (:import [java.net InetSocketAddress Socket]
           [java.nio ByteBuffer ByteOrder]))

(defn- read-exact! [input bytes offset length]
  (loop [position offset remaining length]
    (if (zero? remaining)
      true
      (let [read-count (.read input bytes position remaining)]
        (if (neg? read-count)
          false
          (recur (+ position read-count) (- remaining read-count)))))))

(defn read-frame! [input]
  (let [header (byte-array wire/rpc-v1-header-bytes)]
    (when-not (read-exact! input header 0 wire/rpc-v1-header-bytes)
      (throw (ex-info "native RPC response ended inside its header"
                      {:type :rpc-truncated})))
    (let [buffer (doto (ByteBuffer/wrap header) (.order ByteOrder/LITTLE_ENDIAN))]
      (.position buffer 14)
      (let [body-length (int (Integer/toUnsignedLong (.getInt buffer)))
            body (byte-array body-length)
            frame (byte-array (+ wire/rpc-v1-header-bytes body-length))]
        (when-not (read-exact! input body 0 body-length)
          (throw (ex-info "native RPC response ended inside its body"
                          {:type :rpc-truncated})))
        (System/arraycopy header 0 frame 0 wire/rpc-v1-header-bytes)
        (System/arraycopy body 0 frame wire/rpc-v1-header-bytes body-length)
        (wire/decode-rpc-frame-v1! frame)))))

(defn request! [port request-id request]
  (with-open [socket (Socket.)]
    (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout socket 10000)
    (let [output (.getOutputStream socket)]
      (.write output (wire/encode-rpc-frame-v1!
                      (wire/rpc-request-frame request-id request)))
      (.flush output)
      (t/rpcframev1-response (read-frame! (.getInputStream socket))))))

(defn cancel! [port request-id]
  (with-open [socket (Socket.)]
    (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (let [output (.getOutputStream socket)]
      (.write output (wire/encode-rpc-frame-v1!
                      (wire/rpc-cancel-frame request-id)))
      (.flush output))))
