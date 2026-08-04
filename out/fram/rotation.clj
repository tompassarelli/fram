(ns fram.rotation
  (:require [fram.types :as t]
            [fram.store :as store]))

^{:line 19 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defrecord Rotation [space-id version events by-occurrence spo pos osp])

(defn rotation-space-id [r] (:space-id r))

(defn rotation-version [r] (:version r))

(defn rotation-events [r] (:events r))

(defn rotation-by-occurrence [r] (:by-occurrence r))

(defn rotation-spo [r] (:spo r))

(defn rotation-pos [r] (:pos r))

(defn rotation-osp [r] (:osp r))

^{:line 28 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (def empty-events ^{:line 28 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [])

^{:line 30 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (def empty-bucket ^{:line 30 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} {})

^{:line 32 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (def empty-occurrences ^{:line 32 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} {})

^{:line 34 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn occurrence-of [event]
  ^{:line 34 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot0 event))

^{:line 36 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn proposition-of [event]
  ^{:line 36 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot2 event))

^{:line 38 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^Boolean assertion-occurrence? [event]
  ^{:line 39 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 39 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple? event) ^{:line 40 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 40 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= t/asserts ^{:line 40 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot1 event)) ^{:line 41 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 41 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/occurrence-coordinate? ^{:line 41 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot0 event)) ^{:line 42 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple? ^{:line 42 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot2 event))))))

^{:line 44 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- checked-assertion [event]
  ^{:line 45 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 45 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (assertion-occurrence? event) event ^{:line 47 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (throw ^{:line 47 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (ex-info "fram: rotations cover assertion occurrences only" ^{:line 47 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} {:type :invalid-rotation-occurrence}))))

^{:line 49 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- bucket-add [bucket key event]
  ^{:line 53 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (assoc bucket key ^{:line 53 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (conj ^{:line 53 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get bucket key empty-events) event)))

^{:line 55 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- without-event [events target]
  ^{:line 58 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (filterv ^{:line 58 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [event] ^{:line 58 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (not ^{:line 58 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= event target))) events))

^{:line 60 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- bucket-del [bucket key event]
  ^{:line 64 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [remaining ^{:line 64 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (without-event ^{:line 64 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get bucket key empty-events) event)]
  ^{:line 65 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 65 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (empty? remaining) ^{:line 65 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (dissoc bucket key) ^{:line 65 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (assoc bucket key remaining))))

^{:line 67 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- ^Rotation rotate-add [^Rotation rotation event]
  ^{:line 70 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [proposition ^{:line 70 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (proposition-of ^{:line 70 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (checked-assertion event))
   slot0 ^{:line 70 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot0 proposition)
   slot1 ^{:line 70 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot1 proposition)
   slot2 ^{:line 70 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot2 proposition)]
  ^{:line 71 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (->Rotation ^{:line 71 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-space-id rotation) ^{:line 72 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-version rotation) ^{:line 73 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (conj ^{:line 73 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-events rotation) event) ^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (assoc ^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-by-occurrence rotation) ^{:line 74 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (occurrence-of event) event) ^{:line 75 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 75 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 75 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 75 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-spo rotation) ^{:line 75 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0] event) ^{:line 75 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0 slot1] event) ^{:line 76 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0 slot1 slot2] event) ^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-pos rotation) ^{:line 78 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot1] event) ^{:line 79 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot1 slot2] event) ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-add ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-osp rotation) ^{:line 81 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot2] event) ^{:line 82 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot2 slot0] event))))

^{:line 85 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- ^Rotation rotate-del [^Rotation rotation event]
  ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [proposition ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (proposition-of ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (checked-assertion event))
   slot0 ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot0 proposition)
   slot1 ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot1 proposition)
   slot2 ^{:line 88 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot2 proposition)]
  ^{:line 89 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (->Rotation ^{:line 89 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-space-id rotation) ^{:line 90 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-version rotation) ^{:line 91 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (without-event ^{:line 91 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-events rotation) event) ^{:line 92 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (dissoc ^{:line 92 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-by-occurrence rotation) ^{:line 92 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (occurrence-of event)) ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-spo rotation) ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0] event) ^{:line 93 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0 slot1] event) ^{:line 94 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0 slot1 slot2] event) ^{:line 96 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 96 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 96 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-pos rotation) ^{:line 96 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot1] event) ^{:line 97 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot1 slot2] event) ^{:line 99 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 99 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (bucket-del ^{:line 99 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-osp rotation) ^{:line 99 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot2] event) ^{:line 100 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot2 slot0] event))))

^{:line 103 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- ^Rotation empty-rotation [^String space-id version]
  ^{:line 106 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (->Rotation space-id version empty-events empty-occurrences empty-bucket empty-bucket empty-bucket))

^{:line 114 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^String space-id [^Rotation rotation]
  ^{:line 114 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-space-id rotation))

^{:line 116 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn version [^Rotation rotation]
  ^{:line 116 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-version rotation))

^{:line 118 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn all-occurrences [^Rotation rotation]
  ^{:line 119 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-events rotation))

^{:line 121 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn occurrence-count [^Rotation rotation]
  ^{:line 122 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (count ^{:line 122 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-events rotation)))

^{:line 126 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-slot0 [^Rotation rotation slot0]
  ^{:line 129 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 129 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-spo rotation) ^{:line 129 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0] empty-events))

^{:line 131 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-slot01 [^Rotation rotation slot0 slot1]
  ^{:line 135 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 135 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-spo rotation) ^{:line 135 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot0 slot1] empty-events))

^{:line 137 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-slot1 [^Rotation rotation slot1]
  ^{:line 140 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 140 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-pos rotation) ^{:line 140 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot1] empty-events))

^{:line 142 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-slot12 [^Rotation rotation slot1 slot2]
  ^{:line 146 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 146 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-pos rotation) ^{:line 146 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot1 slot2] empty-events))

^{:line 148 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-slot2 [^Rotation rotation slot2]
  ^{:line 151 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 151 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-osp rotation) ^{:line 151 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot2] empty-events))

^{:line 153 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-slot02 [^Rotation rotation slot0 slot2]
  ^{:line 157 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 157 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-osp rotation) ^{:line 157 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [slot2 slot0] empty-events))

^{:line 159 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn by-proposition [^Rotation rotation proposition]
  ^{:line 162 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 162 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-spo rotation) ^{:line 163 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} [^{:line 163 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot0 proposition) ^{:line 164 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot1 proposition) ^{:line 165 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot2 proposition)] empty-events))

^{:line 170 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn matching [^Rotation rotation slot0 slot1 slot2]
  ^{:line 175 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (cond
  ^{:line 176 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 176 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot0) ^{:line 176 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 176 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot1) ^{:line 176 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot2))) ^{:line 177 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-proposition rotation ^{:line 177 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple slot0 slot1 slot2))
  ^{:line 178 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 178 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot0) ^{:line 178 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot1)) ^{:line 178 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-slot01 rotation slot0 slot1)
  ^{:line 179 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 179 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot1) ^{:line 179 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot2)) ^{:line 179 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-slot12 rotation slot1 slot2)
  ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot0) ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot2)) ^{:line 180 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-slot02 rotation slot0 slot2)
  ^{:line 181 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot0) ^{:line 181 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-slot0 rotation slot0)
  ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot1) ^{:line 182 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-slot1 rotation slot1)
  ^{:line 183 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? slot2) ^{:line 183 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-slot2 rotation slot2)
  :else ^{:line 184 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-events rotation)))

^{:line 186 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^Boolean live-occurrence? [^Rotation rotation occurrence]
  ^{:line 189 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (contains? ^{:line 189 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-by-occurrence rotation) occurrence))

^{:line 191 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn event-at [^Rotation rotation occurrence]
  ^{:line 194 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (get ^{:line 194 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-by-occurrence rotation) occurrence))

^{:line 196 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn newest-first [events]
  ^{:line 197 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (vec ^{:line 197 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (reverse events)))

^{:line 199 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn newest [events]
  ^{:line 200 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 200 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (empty? events) nil ^{:line 200 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (last events)))

^{:line 202 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn propositions [events]
  ^{:line 203 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (mapv ^{:line 203 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [event] ^{:line 203 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (proposition-of event)) events))

^{:line 205 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn subjects [events]
  ^{:line 206 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (mapv ^{:line 206 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [event] ^{:line 206 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot0 ^{:line 206 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (proposition-of event))) events))

^{:line 209 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn values [events]
  ^{:line 210 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (mapv ^{:line 210 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [event] ^{:line 210 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/triple-slot2 ^{:line 210 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (proposition-of event))) events))

^{:line 213 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn occurrences [events]
  ^{:line 214 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (mapv ^{:line 214 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [event] ^{:line 214 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (occurrence-of event)) events))

^{:line 216 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^Rotation project [ctx]
  ^{:line 217 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (reduce ^{:line 217 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [rotation event] ^{:line 219 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotate-add rotation event)) ^{:line 220 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (empty-rotation ^{:line 220 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/space-id ctx) ^{:line 220 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/current-sequence ctx)) ^{:line 221 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/live-occurrences ctx)))

^{:line 226 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn- ^Rotation apply-frame [^Rotation rotation ^String space-id frame]
  ^{:line 230 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [coordinate ^{:line 230 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/transaction-coordinate space-id ^{:line 230 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/transactionframe-sequence frame))
   operations ^{:line 230 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/transactionframe-operations frame)]
  ^{:line 231 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (loop [current rotation
   ordinal 0]
  ^{:line 232 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 232 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (>= ordinal ^{:line 232 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (count operations)) current ^{:line 234 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [operation ^{:line 234 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (nth operations ordinal)
   proposition ^{:line 234 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/commitoperation-proposition operation)]
  ^{:line 235 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 235 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= t/assert-action ^{:line 235 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/commitoperation-action operation)) ^{:line 236 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (recur ^{:line 236 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotate-add current ^{:line 236 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/assertion-occurrence ^{:line 236 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/occurrence-coordinate coordinate ordinal) proposition)) ^{:line 237 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (inc ordinal)) ^{:line 238 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [target ^{:line 238 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (newest ^{:line 238 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (by-proposition current proposition))]
  ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (recur ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (some? target) ^{:line 239 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotate-del current target) current) ^{:line 240 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (inc ordinal)))))))))

^{:line 245 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^Rotation staged [^Rotation rotation ^String space-id sequence operations]
  ^{:line 250 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (apply-frame rotation space-id ^{:line 250 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (t/->TransactionFrame sequence operations)))

^{:line 252 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^Rotation refresh [^Rotation rotation ctx]
  ^{:line 255 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (let [space ^{:line 255 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/space-id ctx)
   target ^{:line 255 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/current-sequence ctx)
   pinned ^{:line 255 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-version rotation)]
  ^{:line 256 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 256 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (not ^{:line 256 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= space ^{:line 256 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-space-id rotation))) ^{:line 257 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (throw ^{:line 257 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (ex-info "fram: rotation belongs to a different space" ^{:line 257 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} {:type :rotation-space-mismatch})) ^{:line 258 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 258 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (> pinned target) ^{:line 259 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (throw ^{:line 259 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (ex-info "fram: rotation is ahead of the store it projects" ^{:line 259 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} {:type :rotation-ahead-of-store})) ^{:line 260 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (if ^{:line 260 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= pinned target) rotation ^{:line 262 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (assoc ^{:line 262 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (reduce ^{:line 262 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (fn [current frame] ^{:line 264 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (apply-frame current space frame)) rotation ^{:line 264 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/transaction-frames-between ^{:line 264 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (deref ctx) pinned target)) :version target))))))

^{:line 270 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (defn ^Boolean pinned? [^Rotation rotation ctx]
  ^{:line 273 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (and ^{:line 273 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= ^{:line 273 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/space-id ctx) ^{:line 273 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-space-id rotation)) ^{:line 274 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (= ^{:line 274 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (store/current-sequence ctx) ^{:line 274 :file "/home/tom/code/fram/wt-engine-fixes/src/fram/rotation.bclj"} (rotation-version rotation))))
