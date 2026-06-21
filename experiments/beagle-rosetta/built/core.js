import * as $$bc from 'beagle/core.js';

function fib(n) {
  return ((n < 2) ? n : (fib((n - 1)) + fib((n - 2))));
}

function fact(n) {
  return ((n < 2) ? 1 : (n * fact((n - 1))));
}

function gcd2(a, b) {
  return (($$bc.equiv(b, 0)) ? a : gcd2(b, (a % b)));
}

function sum_to(n) {
  return ((n < 1) ? 0 : (n + sum_to((n - 1))));
}

function collatz_steps(n, acc) {
  return ((n <= 1)) ? acc : (($$bc.equiv(0, (n % 2)))) ? collatz_steps(Math.trunc(n / 2), (acc + 1)) : collatz_steps(((3 * n) + 1), (acc + 1));
}

function fizzbuzz_1(n) {
  return (($$bc.equiv(0, (n % 15)))) ? "FizzBuzz" : (($$bc.equiv(0, (n % 3)))) ? "Fizz" : (($$bc.equiv(0, (n % 5)))) ? "Buzz" : ("".concat(n));
}
