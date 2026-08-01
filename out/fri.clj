(ns fri
  (:require [fri-port :as fp]))

^{:line 8 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (def ^String MAGIC fp/MAGIC)

^{:line 9 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (def FMT fp/FMT)

^{:line 10 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn source-binding [^String space-id ^String fingerprint valid-bytes]
  ^{:line 11 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/source-binding space-id fingerprint valid-bytes))

^{:line 12 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn write-fri! [dump ^String path source]
  ^{:line 13 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/write-fri! dump path source))

^{:line 14 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn open-fri! [^String path source]
  ^{:line 14 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/open-fri! path source))

^{:line 15 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn close-fri! [image]
  ^{:line 15 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/close-fri! image))

^{:line 16 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn restore-store! [image target]
  ^{:line 17 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/restore-store! image target))

^{:line 18 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn ^String space-id [image]
  ^{:line 18 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/space-id image))

^{:line 19 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn ^String source-fingerprint [image]
  ^{:line 19 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/source-fingerprint image))

^{:line 20 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn source-position [image]
  ^{:line 20 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/source-position image))

^{:line 21 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn transaction-count [image]
  ^{:line 21 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/transaction-count image))

^{:line 22 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn operation-count [image]
  ^{:line 22 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/operation-count image))

^{:line 23 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn semantic-history [image]
  ^{:line 23 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/semantic-history image))

^{:line 24 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn operation-occurrences [image]
  ^{:line 24 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/operation-occurrences image))

^{:line 25 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn live-occurrences [image]
  ^{:line 25 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/live-occurrences image))

^{:line 26 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn live-propositions [image]
  ^{:line 26 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/live-propositions image))

^{:line 27 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn by-slot0 [image term]
  ^{:line 27 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/by-slot0 image term))

^{:line 28 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn by-slot1 [image term]
  ^{:line 28 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/by-slot1 image term))

^{:line 29 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn by-slot2 [image term]
  ^{:line 29 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/by-slot2 image term))

^{:line 30 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn by-slot01 [image slot0 slot1]
  ^{:line 31 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/by-slot01 image slot0 slot1))

^{:line 32 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn by-slot12 [image slot1 slot2]
  ^{:line 33 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/by-slot12 image slot1 slot2))

^{:line 34 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn by-slot02 [image slot0 slot2]
  ^{:line 35 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/by-slot02 image slot0 slot2))

^{:line 36 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn live-occurrences-as-of [image sequence]
  ^{:line 37 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/live-occurrences-as-of image sequence))

^{:line 38 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (defn live-propositions-as-of [image sequence]
  ^{:line 39 :file "/home/tom/code/fram/wt-triple-fri/src/fri.bclj"} (fp/live-propositions-as-of image sequence))
