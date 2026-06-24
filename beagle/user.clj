(ns beagle.user)

^{:line 14 :file "/tmp/colon-marker-rt-534471902905308/demo.bclj"} (def seed-marker 0)

(def ^String base "Howdy")

(defn ^String greet [^String who]
  (str base ", " who "!"))
