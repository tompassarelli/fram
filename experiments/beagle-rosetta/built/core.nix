let
  fib = n: if (n < 2) then n else ((fib (n - 1)) + (fib (n - 2)));
  fact = n: if (n < 2) then 1 else (n * (fact (n - 1)));
  gcd2 = a: b: if (b == 0) then a else gcd2 b ((a - (a / b) * b));
  sum-to = n: if (n < 1) then 0 else (n + (sum-to (n - 1)));
  collatz-steps = n: acc: if (n <= 1) then acc else if (0 == ((n - (n / 2) * 2))) then collatz-steps (quot n 2) (acc + 1) else collatz-steps ((3 * n) + 1) (acc + 1);
  fizzbuzz-1 = n: if (0 == ((n - (n / 15) * 15))) then "FizzBuzz" else if (0 == ((n - (n / 3) * 3))) then "Fizz" else if (0 == ((n - (n / 5) * 5))) then "Buzz" else (n);
in
null
