(ns rosetta.core)

^{:line 7 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (defn fib [n]
  ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (if ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (< n 2) n ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (+ ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (fib ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (- n 1)) ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (fib ^{:line 8 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (- n 2)))))

^{:line 10 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (defn fact [n]
  ^{:line 11 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (if ^{:line 11 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (< n 2) 1 ^{:line 11 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (* n ^{:line 11 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (fact ^{:line 11 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (- n 1)))))

^{:line 13 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (defn gcd2 [a b]
  ^{:line 14 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (if ^{:line 14 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (= b 0) a ^{:line 14 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (gcd2 b ^{:line 14 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (mod a b))))

^{:line 16 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (defn sum-to [n]
  ^{:line 17 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (if ^{:line 17 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (< n 1) 0 ^{:line 17 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (+ n ^{:line 17 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (sum-to ^{:line 17 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (- n 1)))))

^{:line 19 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (defn collatz-steps [n acc]
  ^{:line 20 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (cond
  ^{:line 20 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (<= n 1) acc
  ^{:line 21 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (= 0 ^{:line 21 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (mod n 2)) ^{:line 21 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (collatz-steps ^{:line 21 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (quot n 2) ^{:line 21 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (+ acc 1))
  :else ^{:line 22 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (collatz-steps ^{:line 22 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (+ ^{:line 22 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (* 3 n) 1) ^{:line 22 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (+ acc 1))))

^{:line 24 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (defn ^String fizzbuzz-1 [n]
  ^{:line 25 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (cond
  ^{:line 25 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (= 0 ^{:line 25 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (mod n 15)) "FizzBuzz"
  ^{:line 26 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (= 0 ^{:line 26 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (mod n 3)) "Fizz"
  ^{:line 27 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (= 0 ^{:line 27 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (mod n 5)) "Buzz"
  :else ^{:line 28 :file "/home/tom/code/fram/experiments/beagle-rosetta/rosetta.bclj"} (str n)))
