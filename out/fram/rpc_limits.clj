(ns fram.rpc-limits)

(def term-codec-v1-max-depth 256)

(def rpc-v1-list-envelope-depth 6)

(def rpc-v1-unpaged-response-wrapper-depth 8)

(def rpc-v1-mutation-response-wrapper-depth 9)

(def rpc-v1-max-list-values (- term-codec-v1-max-depth rpc-v1-list-envelope-depth))

(def rpc-v1-max-unpaged-rows (- term-codec-v1-max-depth rpc-v1-unpaged-response-wrapper-depth))

(def rpc-v1-max-batch-actions (- term-codec-v1-max-depth rpc-v1-mutation-response-wrapper-depth))
