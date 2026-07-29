(ns resolve-walk
  (:require [clojure.string :as str]
            [fram.types :as t]
            [fram.store :as c]
            [resolve-core :as rc]
            [resolve-read :as rr]
            [resolve-binds :as rb]
            [resolve-modules :as rm]))

^{:line 106 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defrecord Walk [ctx view tx REFERS BOUND FIXED QUAL CTOR ACC nres nunres nxmod ntype ncomment xres tres ares])

(defn walk-ctx [r] (:ctx r))

(defn walk-view [r] (:view r))

(defn walk-tx [r] (:tx r))

(defn walk-REFERS [r] (:REFERS r))

(defn walk-BOUND [r] (:BOUND r))

(defn walk-FIXED [r] (:FIXED r))

(defn walk-QUAL [r] (:QUAL r))

(defn walk-CTOR [r] (:CTOR r))

(defn walk-ACC [r] (:ACC r))

(defn walk-nres [r] (:nres r))

(defn walk-nunres [r] (:nunres r))

(defn walk-nxmod [r] (:nxmod r))

(defn walk-ntype [r] (:ntype r))

(defn walk-ncomment [r] (:ncomment r))

(defn walk-xres [r] (:xres r))

(defn walk-tres [r] (:tres r))

(defn walk-ares [r] (:ares r))

^{:line 108 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defrecord Corpus [srcs modframe typeframe accessors ents])

(defn corpus-srcs [r] (:srcs r))

(defn corpus-modframe [r] (:modframe r))

(defn corpus-typeframe [r] (:typeframe r))

(defn corpus-accessors [r] (:accessors r))

(defn corpus-ents [r] (:ents r))

^{:line 110 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- nn [e]
  ^{:line 110 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 110 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? e) -1 e))

^{:line 112 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (def RET-COLON ^{:line 112 :file "/home/tom/code/fram/src/resolve_walk.bclj"} #{":-" ":" ":raises"})

^{:line 114 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (def UNQUOTE-TOKENS ^{:line 114 :file "/home/tom/code/fram/src/resolve_walk.bclj"} #{"~" "," "~@" ",@"})

^{:line 116 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- sv [^Walk w e]
  ^{:line 116 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rr/sym-val ^{:line 116 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 116 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) e))

^{:line 118 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- kd [^Walk w e]
  ^{:line 118 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rr/kind-of ^{:line 118 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 118 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) e))

^{:line 120 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- hd [^Walk w e]
  ^{:line 120 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rr/head-sym ^{:line 120 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 120 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) e))

^{:line 122 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- kids [^Walk w e]
  ^{:line 122 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rr/ordered-children ^{:line 122 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) e))

^{:line 124 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Boolean brk? [^Walk w e]
  ^{:line 124 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/brackets? ^{:line 124 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 124 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) e))

^{:line 126 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Boolean list? [^Walk w e]
  ^{:line 126 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 126 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w e)))

^{:line 128 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- xr [^Walk w nm]
  ^{:line 128 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [f ^{:line 128 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:xres w)]
  ^{:line 128 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f nm)))

^{:line 130 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- tr [^Walk w nm]
  ^{:line 130 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [f ^{:line 130 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tres w)]
  ^{:line 130 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f nm)))

^{:line 132 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ar [^Walk w nm]
  ^{:line 132 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [f ^{:line 132 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ares w)]
  ^{:line 132 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f nm)))

^{:line 134 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- scope-lookup [scope nm]
  ^{:line 135 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [i 0]
  ^{:line 136 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 136 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (>= i ^{:line 136 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (count scope)) nil ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [hit ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth scope i) nm)]
  ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? hit) ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 138 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc i)) hit)))))

^{:line 140 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- push [frame scope]
  ^{:line 140 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (into ^{:line 140 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [frame] scope))

^{:line 142 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn bind! [^Walk w L target]
  ^{:line 143 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 144 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 144 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 144 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn L) ^{:line 144 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:REFERS w) ^{:line 144 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn target) ^{:line 144 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 145 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 145 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:nres w) ^{:line 145 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 145 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))))

^{:line 147 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn bind-xmod! [^Walk w node x]
  ^{:line 148 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 148 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (or ^{:line 148 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? x) ^{:line 148 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? ^{:line 148 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:target x))) nil ^{:line 150 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [mode ^{:line 150 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:mode x)
   acc ^{:line 150 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:accessor x)]
  ^{:line 151 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 152 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node ^{:line 152 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:target x))
  ^{:line 153 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 154 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= :fixed mode) ^{:line 155 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 155 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 156 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 157 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:FIXED w) ^{:line 158 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 158 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) "1") ^{:line 159 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 160 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= :qual mode) ^{:line 161 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 161 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 162 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 163 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:QUAL w) ^{:line 164 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 164 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 164 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:alias x)) ^{:line 165 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  :else nil)
  ^{:line 168 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 168 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? acc) ^{:line 168 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ACC w) ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) acc) ^{:line 169 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))))
  ^{:line 170 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 170 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:nxmod w) ^{:line 170 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 170 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))
  true))))

^{:line 173 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn bound-render! [^Walk w node nm bt]
  ^{:line 174 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 175 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node bt)
  ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [x ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w nm)
   pfx ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rc/ctor-prefix ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (string? nm) nm nil))
   acc ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (ar w nm)
   stripped ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? pfx) nil ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str/replace ^{:line 176 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str nm) pfx ""))]
  ^{:line 177 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 178 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 178 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? x) ^{:line 178 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 178 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:target x))) ^{:line 179 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [mode ^{:line 179 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:mode x)
   xacc ^{:line 179 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:accessor x)]
  ^{:line 180 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 181 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 182 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= :fixed mode) ^{:line 183 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 183 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 184 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 185 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:FIXED w) ^{:line 186 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 186 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) "1") ^{:line 187 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 188 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= :qual mode) ^{:line 189 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 189 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 190 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 191 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:QUAL w) ^{:line 192 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 192 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 192 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:alias x)) ^{:line 193 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  :else nil)
  ^{:line 196 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 196 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? xacc) ^{:line 196 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 197 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 197 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 198 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 199 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ACC w) ^{:line 200 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 200 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) xacc) ^{:line 201 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))))))
  ^{:line 202 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 202 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? pfx) ^{:line 203 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (or ^{:line 203 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 203 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (tr w stripped)) ^{:line 203 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 203 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:target ^{:line 203 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w stripped))))) ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:CTOR w) ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) pfx) ^{:line 204 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 205 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? acc) ^{:line 206 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 206 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 207 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 208 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ACC w) ^{:line 209 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 209 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 209 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth acc 1)) ^{:line 210 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  :else nil))))

^{:line 214 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-type! [^Walk w node]
  ^{:line 215 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 216 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 216 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w node)) ^{:line 217 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [nm ^{:line 217 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w node)
   b ^{:line 217 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (tr w nm)]
  ^{:line 218 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 218 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? b) ^{:line 219 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 220 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node b)
  ^{:line 221 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 221 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ntype w) ^{:line 221 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 221 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))
  true) ^{:line 223 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind-xmod! w node ^{:line 223 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w nm))))
  ^{:line 224 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 224 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w node)) ^{:line 225 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [ch ^{:line 225 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w node)]
  ^{:line 225 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type! w ch))
  ^{:line 226 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w node) ^{:line 227 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [ch ^{:line 227 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 227 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 227 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w node)))]
  ^{:line 227 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type! w ch))
  :else nil))

^{:line 231 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Boolean colon? [^Walk w e]
  ^{:line 232 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [v ^{:line 232 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w e)]
  ^{:line 232 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 232 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? v) ^{:line 232 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/TYPE-COLON ^{:line 232 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str v)))))

^{:line 234 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn resolve-type-after-colon! [^Walk w nodes]
  ^{:line 235 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [xs nodes]
  ^{:line 236 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 236 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (empty? xs) nil ^{:line 238 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 238 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (colon? w ^{:line 238 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth xs 0 nil)) ^{:line 239 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [nxt ^{:line 239 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth xs 1 nil)]
  ^{:line 239 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 239 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? nxt) ^{:line 239 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 239 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type! w nxt)))) ^{:line 240 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 240 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 240 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest xs)))))))

^{:line 242 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn resolve-types-in-bracket! [^Walk w bracket]
  ^{:line 243 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [ks ^{:line 243 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 243 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 243 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w bracket)))]
  ^{:line 244 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 244 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (empty? ks) nil ^{:line 246 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [k ^{:line 246 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 0)]
  ^{:line 247 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 248 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (colon? w k) ^{:line 249 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 250 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 250 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 250 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil)) ^{:line 250 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 250 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type! w ^{:line 250 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil))))
  ^{:line 251 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 251 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 251 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks))))
  ^{:line 252 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 252 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w k)) ^{:line 253 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 253 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-type-after-colon! w ^{:line 253 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w k))
  ^{:line 253 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 253 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 253 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ks))))
  :else ^{:line 255 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 255 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 255 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ks))))))))

^{:line 257 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-all! [^Walk w nodes scope wf]
  ^{:line 258 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [n nodes]
  ^{:line 258 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (wf w n scope)))

^{:line 260 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-pat-heads! [^Walk w pat scope wf]
  ^{:line 261 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 261 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 261 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w pat)) ^{:line 261 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 262 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [cs ^{:line 262 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w pat)]
  ^{:line 263 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 264 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (wf w ^{:line 264 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cs 0 nil) scope)
  ^{:line 265 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [ch ^{:line 265 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 265 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest cs))]
  ^{:line 265 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-pat-heads! w ch scope wf)))))))

^{:line 267 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-fn-arity! [^Walk w forms scope wf]
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bi ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [i 0]
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (>= i ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (count forms)) nil ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth forms i)) i ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc i)))))
   pv ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? bi) nil ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth forms bi))
   binds ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? pv) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [] ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/param-binds ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) pv))
   _ ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? pv) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-types-in-bracket! w pv)))
   or-vals ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? pv) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [] ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [acc k] ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (into acc ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/collect-or-vals ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) k))) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [] ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w pv)))))
   frame ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/frame-of ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) binds)
   body ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [xs ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? bi) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [] ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc bi) forms)))]
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [v ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth xs 0 nil))]
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? v) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? RET-COLON ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str v))) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth xs 1 nil)) ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type! w ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth xs 1 nil))))
  ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 268 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 xs)))) xs)))]
  ^{:line 269 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 269 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w or-vals scope wf)
  ^{:line 269 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w body ^{:line 269 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (push frame scope) wf))))

^{:line 271 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-quasi! [^Walk w node scope ^Boolean quoted? wf qsf]
  ^{:line 272 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 273 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 273 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w node)) ^{:line 274 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 274 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (not quoted?) ^{:line 274 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [nm ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w node)
   outer ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (empty? scope) ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [] ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (butlast scope)))
   modframe ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (empty? scope) nil ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (last scope))
   inner ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (scope-lookup outer nm)
   mod-hit ^{:line 275 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get modframe nm)]
  ^{:line 276 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 277 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? inner) nil
  ^{:line 279 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? mod-hit) ^{:line 280 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node mod-hit)
  ^{:line 281 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 281 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind-xmod! w node ^{:line 281 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w nm))) nil
  :else nil))))
  ^{:line 285 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 285 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w node)) ^{:line 286 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [h ^{:line 286 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (hd w node)]
  ^{:line 287 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 288 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? ^{:line 288 :file "/home/tom/code/fram/src/resolve_walk.bclj"} #{"unquote" "unquote-splicing"} ^{:line 288 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str h)) ^{:line 289 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ^{:line 289 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 289 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 289 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w node))) scope wf)
  ^{:line 290 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "quote" ^{:line 290 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str h)) ^{:line 291 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (qsf w ^{:line 291 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w node) scope true wf)
  :else ^{:line 293 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (qsf w ^{:line 293 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w node) scope quoted? wf)))
  :else nil))

^{:line 297 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-quasi-seq! [^Walk w children scope ^Boolean quoted? wf]
  ^{:line 298 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [cs children]
  ^{:line 299 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 299 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (empty? cs) nil ^{:line 301 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [v ^{:line 301 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ^{:line 301 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cs 0 nil))]
  ^{:line 302 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 302 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 302 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? v) ^{:line 302 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? UNQUOTE-TOKENS ^{:line 302 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str v))) ^{:line 303 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 304 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 304 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 304 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cs 1 nil)) ^{:line 304 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 304 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (wf w ^{:line 304 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cs 1 nil) scope)))
  ^{:line 305 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 305 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 305 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 cs)))) ^{:line 306 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 307 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-quasi! w ^{:line 307 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cs 0 nil) scope quoted? wf walk-quasi-seq!)
  ^{:line 308 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 308 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 308 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest cs)))))))))

^{:line 310 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Boolean try-type! [^Walk w node nm]
  ^{:line 311 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [b ^{:line 311 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (tr w nm)]
  ^{:line 312 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 312 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? b) false ^{:line 314 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 315 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node b)
  ^{:line 316 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 316 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ntype w) ^{:line 316 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 316 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))
  true))))

^{:line 319 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Boolean try-ctor! [^Walk w node nm]
  ^{:line 320 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [pfx ^{:line 320 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rc/ctor-prefix ^{:line 320 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 320 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (string? nm) nm nil))]
  ^{:line 321 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 321 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? pfx) false ^{:line 323 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [stripped ^{:line 323 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str/replace ^{:line 323 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str nm) pfx "")
   b ^{:line 323 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (tr w stripped)]
  ^{:line 324 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 324 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? b) ^{:line 325 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 326 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node b)
  ^{:line 327 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 327 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 328 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 329 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:CTOR w) ^{:line 330 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 330 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) pfx) ^{:line 331 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 332 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 332 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ntype w) ^{:line 332 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 332 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))
  true) ^{:line 334 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 334 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 334 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind-xmod! w node ^{:line 334 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w stripped))) ^{:line 335 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 336 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 336 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 337 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 338 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:CTOR w) ^{:line 339 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 339 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) pfx) ^{:line 340 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  true) false))))))

^{:line 344 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Boolean try-accessor! [^Walk w node nm]
  ^{:line 345 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [a ^{:line 345 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (ar w nm)]
  ^{:line 346 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 346 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? a) false ^{:line 348 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 349 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node ^{:line 349 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth a 0))
  ^{:line 350 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 350 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 351 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn node) ^{:line 352 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ACC w) ^{:line 353 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value! ^{:line 353 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 353 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth a 1)) ^{:line 354 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 355 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 355 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ntype w) ^{:line 355 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 355 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))
  true))))

^{:line 358 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- walk-type-def! [^Walk w ks]
  ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [ch ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rc/type-name-index ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 0 nil)) ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ^{:line 359 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil)))) ks))]
  ^{:line 360 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 361 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w ch) ^{:line 362 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-types-in-bracket! w ch)
  ^{:line 363 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 363 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w ch)) ^{:line 364 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [cc ^{:line 364 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w ch)]
  ^{:line 365 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 366 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [b ^{:line 366 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 366 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 366 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [k] ^{:line 366 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w k)) cc))]
  ^{:line 367 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-types-in-bracket! w b))
  ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bi ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (loop [i 0]
  ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (>= i ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (count cc)) nil ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cc i)) i ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (recur ^{:line 368 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc i)))))]
  ^{:line 369 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-type-after-colon! w ^{:line 370 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 370 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nil? bi) ^{:line 370 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [] ^{:line 370 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 370 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop ^{:line 370 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc bi) cc)))))))
  ^{:line 371 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 371 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ch)) ^{:line 372 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [b ^{:line 372 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (tr w ^{:line 372 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ch))]
  ^{:line 373 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 373 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 373 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? b) ^{:line 373 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (not= b ch)) ^{:line 373 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 374 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 374 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w ch b)
  ^{:line 374 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 374 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ntype w) ^{:line 374 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 374 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))))))
  :else nil)))

^{:line 378 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk! [^Walk w node scope]
  ^{:line 379 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [k ^{:line 379 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w node)]
  ^{:line 380 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 381 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "symbol" k) ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [nm ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w node)
   local ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (scope-lookup scope nm)
   bt ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rr/bound-target ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) ^{:line 382 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:BOUND w) node)]
  ^{:line 383 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 384 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? bt) ^{:line 385 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bound-render! w node nm bt)
  ^{:line 386 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? local) ^{:line 387 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind! w node local)
  ^{:line 388 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 388 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (bind-xmod! w node ^{:line 388 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w nm))) nil
  ^{:line 390 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (try-type! w node nm) nil
  ^{:line 392 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (try-ctor! w node nm) nil
  ^{:line 394 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (try-accessor! w node nm) nil
  :else ^{:line 397 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 397 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:nunres w) ^{:line 397 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 397 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))))
  ^{:line 398 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" k) ^{:line 399 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [ks ^{:line 399 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w node)
   h ^{:line 399 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (hd w node)
   hs ^{:line 399 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str h)]
  ^{:line 400 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 401 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "quote" hs) nil
  ^{:line 403 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "quasiquote" hs) ^{:line 404 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-quasi! w node scope false walk! walk-quasi-seq!)
  ^{:line 405 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/TYPE-DEFS hs) ^{:line 406 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type-def! w ks)
  ^{:line 407 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/DEF-FORMS hs) ^{:line 408 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [after-name ^{:line 408 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 408 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks))]
  ^{:line 409 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 409 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= ":-" ^{:line 409 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (str ^{:line 409 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ^{:line 409 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth after-name 0 nil)))) ^{:line 410 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 411 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 411 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 411 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth after-name 1 nil)) ^{:line 411 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 412 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-type! w ^{:line 412 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth after-name 1 nil))))
  ^{:line 413 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ^{:line 413 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 413 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 after-name)) scope walk!)) ^{:line 414 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w after-name scope walk!)))
  ^{:line 415 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/PARAM-FORMS hs) ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [after-name ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} #{"defn" "defn-" "defmacro"} hs) ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks)) ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 416 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ks)))]
  ^{:line 417 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 417 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 417 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some ^{:line 417 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 417 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 417 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w f) true nil)) after-name)) ^{:line 418 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-fn-arity! w after-name scope walk!) ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [a ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w f)) ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ^{:line 419 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w f) 0 nil)))) after-name))]
  ^{:line 420 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-fn-arity! w ^{:line 420 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w a) scope walk!))))
  ^{:line 421 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/LET-FORMS hs) ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bracket ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil)
   ok ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? bracket) ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w bracket))
   _ ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ok ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-types-in-bracket! w bracket)))
   pairs ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ok ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/let-bind-pairs ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) bracket) ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [])
   final ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [sc p] ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bsyms ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth p 0)
   vnode ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth p 1)
   orvals ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth p 2)]
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w orvals sc walk!)
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? vnode) ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w vnode sc)))
  ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (push ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/frame-of ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 422 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) bsyms) sc)))) scope pairs)]
  ^{:line 423 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ^{:line 423 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 423 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks)) final walk!))
  ^{:line 424 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/FOR-FORMS hs) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bracket ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil)
   ok ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? bracket) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w bracket))
   _ ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ok ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-types-in-bracket! w bracket)))
   entries ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ok ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/for-bind-pairs ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) bracket) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [])
   final ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [sc e] ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= :expr ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth e 0)) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth e 1) sc)
  sc) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bsyms ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth e 1)
   vnode ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth e 2)
   orvals ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth e 3)]
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w orvals sc walk!)
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? vnode) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w vnode sc)))
  ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (push ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/frame-of ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 425 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) bsyms) sc))))) scope entries)]
  ^{:line 426 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ^{:line 426 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 426 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks)) final walk!))
  ^{:line 427 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? rc/MATCH-FORMS hs) ^{:line 428 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 429 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w ^{:line 429 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil) scope)
  ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [clause ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [cl] ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w cl)) ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 430 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks))))]
  ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [cc ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w clause)))
   pat ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth cc 0 nil)
   body ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 431 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest cc))]
  ^{:line 432 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 433 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-pat-heads! w pat scope walk!)
  ^{:line 434 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w body ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (push ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/frame-of ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/match-pat-binds ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 436 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) pat)) scope) walk!)))))
  ^{:line 439 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "letfn" hs) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [bracket ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil)
   fnlists ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (and ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? bracket) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (brk? w bracket)) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w f))) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w bracket))))) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [])
   frame ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/frame-of ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (keep ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w f) 0 nil)) fnlists)))
   bodyscope ^{:line 440 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (push frame scope)]
  ^{:line 441 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 442 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [fl fnlists]
  ^{:line 443 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-fn-arity! w ^{:line 443 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 443 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ^{:line 443 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w fl))) bodyscope walk!))
  ^{:line 444 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ^{:line 444 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 444 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 2 ks)) bodyscope walk!)))
  ^{:line 445 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (contains? ^{:line 445 :file "/home/tom/code/fram/src/resolve_walk.bclj"} #{"extend-type" "extend-protocol"} hs) ^{:line 446 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [ch ^{:line 446 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 446 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ks))]
  ^{:line 447 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 448 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 448 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w ch)) ^{:line 449 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w ch scope)
  ^{:line 450 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "list" ^{:line 450 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w ch)) ^{:line 451 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [ic ^{:line 451 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kids w ch)]
  ^{:line 452 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 453 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w ^{:line 453 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ic 0 nil) scope)
  ^{:line 454 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-fn-arity! w ^{:line 454 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 454 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rest ic)) scope walk!)))
  :else nil))
  ^{:line 457 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "as->" hs) ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [init ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 1 nil)
   name ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nth ks 2 nil)
   frame ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rb/frame-of ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w name)) ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [name] ^{:line 458 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))]
  ^{:line 459 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 460 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 460 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? init) ^{:line 460 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 460 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk! w init scope)))
  ^{:line 461 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ^{:line 461 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 461 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (drop 3 ks)) ^{:line 461 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (push frame scope) walk!)))
  :else ^{:line 463 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w ks scope walk!)))
  :else nil)))

^{:line 467 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn cbind! [^Walk w L target]
  ^{:line 468 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 469 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact! ^{:line 469 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 469 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn L) ^{:line 469 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:REFERS w) ^{:line 469 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (nn target) ^{:line 469 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w))
  ^{:line 470 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! ^{:line 470 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ncomment w) ^{:line 470 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [n] ^{:line 470 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (inc n)))))

^{:line 472 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- def-binding [^Corpus cp src nm]
  ^{:line 473 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [v ^{:line 473 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 473 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 473 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:modframe cp) src) nm)]
  ^{:line 474 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 474 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? v) v ^{:line 474 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 474 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 474 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:typeframe cp) src) nm))))

^{:line 476 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn resolve-comment! [^Walk w ^Corpus cp e src]
  ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [seg ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [s] ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "symbol" ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w s))) ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rr/ordered-segs ^{:line 477 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) e)))]
  ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [nm ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (sv w seg)
   local ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (def-binding cp src nm)
   b ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? local) local ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:target ^{:line 478 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xr w nm)))]
  ^{:line 479 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 479 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? b) ^{:line 479 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 479 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cbind! w seg b))))))

^{:line 481 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn walk-comments! [^Walk w ^Corpus cp src]
  ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [e ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [x] ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (= "comment" ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (kd w x))) ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ents cp) src ^{:line 482 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))))]
  ^{:line 483 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (resolve-comment! w cp e src)))

^{:line 485 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn- ^Walk for-src [^Walk w ^Corpus cp src xres-for]
  ^{:line 486 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (->Walk ^{:line 486 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 487 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) ^{:line 488 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:tx w) ^{:line 489 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:REFERS w) ^{:line 490 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:BOUND w) ^{:line 491 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:FIXED w) ^{:line 492 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:QUAL w) ^{:line 493 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:CTOR w) ^{:line 494 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ACC w) ^{:line 495 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:nres w) ^{:line 496 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:nunres w) ^{:line 497 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:nxmod w) ^{:line 498 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ntype w) ^{:line 499 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ncomment w) ^{:line 500 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (xres-for src) ^{:line 501 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [nm] ^{:line 501 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 501 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 501 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:typeframe cp) src) nm)) ^{:line 502 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [nm] ^{:line 502 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 502 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 502 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:accessors cp) src) nm))))

^{:line 504 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn run-resolution-over! [^Walk w ^Corpus cp walk-srcs xres-for n-forms walked]
  ^{:line 505 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (doseq [src walk-srcs]
  ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [w2 ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (for-src w cp src xres-for)
   ents ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ents cp) src ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))
   forms ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/forms-of ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:ctx w) ^{:line 506 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:view w) ents)]
  ^{:line 507 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (do
  ^{:line 508 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! walked conj src)
  ^{:line 509 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (swap! n-forms + ^{:line 509 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (count forms))
  ^{:line 510 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-all! w2 forms ^{:line 510 :file "/home/tom/code/fram/src/resolve_walk.bclj"} [^{:line 510 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ^{:line 510 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:modframe cp) src)] walk!)
  ^{:line 511 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (walk-comments! w2 cp src)))))

^{:line 513 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn run-resolution! [^Walk w ^Corpus cp xres-for n-forms walked]
  ^{:line 514 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (run-resolution-over! w cp ^{:line 514 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:srcs cp) xres-for n-forms walked))

^{:line 516 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn corpus-tables [ctx view srcs ents-of]
  ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [per ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [m s] ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (assoc m s ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ents-of s ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))))) ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {} srcs))
   named ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [s] ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-name ctx view ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ents-of s ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))))) srcs))
   by-mod ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [m s] ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [ents ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get ents-of s ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))]
  ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (assoc m ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-name ctx view ents) ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f ents)))) ^{:line 517 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {} named))]
  ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {:modframe ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (per ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-defs ctx view ents))) :typeframe ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (per ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-types ctx view ents))) :accessors ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (per ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-accessors ctx view ents))) :exports ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (by-mod ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [e ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-exports ctx view ents)]
  ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (empty? e) ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-defs ctx view ents) e)))) :type-exports ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (by-mod ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-types ctx view ents))) :accessor-exports ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (by-mod ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 518 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-accessors ctx view ents)))}))

^{:line 531 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn warm-groups [ctx cache name->module]
  ^{:line 532 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [NAME ^{:line 532 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/value-id ctx "name")]
  ^{:line 533 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (cond
  ^{:line 534 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? cache) cache
  ^{:line 536 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? NAME) ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [groups cid] ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [fact ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/fact-of ctx cid)
   node-name ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/literal ctx ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:r fact))
   module ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (name->module node-name)]
  ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? module) ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (update groups module ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fnil conj ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []) ^{:line 537 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (:l fact)) groups))) ^{:line 538 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {} ^{:line 539 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (c/by-p ctx NAME))
  :else ^{:line 541 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {})))

^{:line 543 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (defn scoped-corpus-tables [ctx view groups scope]
  ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [srcs ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (keys groups))
   frame-srcs ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? scope) ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter scope srcs)) srcs)
   per-frame ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [table src] ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (assoc table src ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get groups src ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))))) ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {} frame-srcs))
   named ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (filter ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [src] ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-name ctx view ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get groups src ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))))) srcs))
   by-module ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [f] ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (some? scope) ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {} ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (reduce ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [table src] ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [ents ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (vec ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (get groups src ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} []))]
  ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (assoc table ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-name ctx view ents) ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (f ents)))) ^{:line 544 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {} named)))]
  ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} {:srcs srcs :modframe ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (per-frame ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-defs ctx view ents))) :typeframe ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (per-frame ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-types ctx view ents))) :accessors ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (per-frame ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-accessors ctx view ents))) :exports ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (by-module ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (let [exports ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-exports ctx view ents)]
  ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (if ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (seq exports) exports ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-defs ctx view ents))))) :type-exports ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (by-module ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-types ctx view ents))) :accessor-exports ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (by-module ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (fn [ents] ^{:line 545 :file "/home/tom/code/fram/src/resolve_walk.bclj"} (rm/module-accessors ctx view ents)))}))
