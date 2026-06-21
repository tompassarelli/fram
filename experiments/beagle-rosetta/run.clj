(require '[rosetta.core :as r])
(def cases [["fib(10)" (r/fib 10) 55] ["fact(5)" (r/fact 5) 120] ["gcd2(48,36)" (r/gcd2 48 36) 12]
            ["sum-to(100)" (r/sum-to 100) 5050] ["collatz-steps(27,0)" (r/collatz-steps 27 0) 111]
            ["fizzbuzz(15)" (r/fizzbuzz-1 15) "FizzBuzz"] ["fizzbuzz(4)" (r/fizzbuzz-1 4) "4"]
            ["fizzbuzz(9)" (r/fizzbuzz-1 9) "Fizz"] ["fizzbuzz(10)" (r/fizzbuzz-1 10) "Buzz"]])
(def results (map (fn [[nm got want]] [nm got want (= got want)]) cases))
(doseq [[nm got want ok] results] (println (format "  %-22s = %-10s want %-10s %s" nm (pr-str got) (pr-str want) (if ok "OK" "FAIL"))))
(let [n (count results) p (count (filter last results))]
  (println (format "\nROSETTA: %d/%d correct (compiled by Beagle, run on clj)" p n))
  (System/exit (if (= p n) 0 1)))
