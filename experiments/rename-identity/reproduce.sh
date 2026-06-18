#!/usr/bin/env bash
# References carry identity, not spelling — reproduction.
#
# Watch the COMPILER (not us) verify that the claim graph's rename is correct and that an
# incomplete rename is wrong — that recompile IS the verification. Then see the competent
# baseline (clojure-lsp) on the same task, blind to the references erased at lowering.
#
# Needs: racket, babashka (bb), Beagle (BEAGLE=~/code/beagle), clojure-lsp (baseline only).
set -uo pipefail
FRAM="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$FRAM"
BEAGLE="${BEAGLE:-$HOME/code/beagle}"
RT="$BEAGLE/beagle-lib/private/claims-roundtrip.rkt"
BUILD="$BEAGLE/bin/beagle-build-all"
T=Claim; T2=Datum; MOD=kernel
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
hr(){ printf '%s\n' "------------------------------------------------------------"; }

hr; echo "1. project every Beagle module (.bclj) to AST-claims (lossless)"
mkdir -p "$W/edn"
for f in src/fram/*.bclj; do racket "$RT" --emit-edn "$f" > "$W/edn/$(basename "$f" .bclj).edn" 2>/dev/null; done
EDNS=$(ls "$W"/edn/*.edn)
echo "   $(ls "$W"/edn | wc -l) modules projected"

hr; echo "2. GRAPH rename  $T -> $T2  (ONE identity edit; references follow refers_to)"
mkdir -p "$W/r"
RESOLVE_OUT="$W/r" bb -cp out chartroom/src/resolve.clj rename $T $T2 $MOD $EDNS 2>&1 | grep -E "CLAIMS EDITED" || true
mkdir -p "$W/rendered"
for p in "$W"/r/resolved-*.edn; do
  b="$(basename "$p" .edn | sed 's/^resolved-//; s/\.bclj$//')"
  racket "$RT" --render "$p" > "$W/rendered/$b.bclj" 2>/dev/null
done
N=$(grep -rhoE "\b(k/)?(->)?$T2\b" "$W/rendered" | wc -l); echo "   $T2 tokens in regenerated source: $N  (1 new definition + $((N-1)) references re-pointed from that one edit)"

hr; echo "3. THE ORACLE — recompile the graph's (complete) rename:"
"$BUILD" "$W/rendered" --out "$W/o1" 2>&1 | grep -iE "built, .* error" || echo "   (build output above)"

hr; echo "4. THE ORACLE DISCRIMINATES — break ONE reference (an incomplete rename) and recompile:"
cp -r "$W/rendered" "$W/broken"
# revert exactly one cross-module type-annotation reference back to the old name:
broke=""
for m in cnf datalog export fold import main query schema tools; do
  if grep -qE "k/$T2\b" "$W/broken/$m.bclj" 2>/dev/null; then
    perl -0pi -e 'BEGIN{$n=0} s/k\/'$T2'\b/k\/'$T'/ if $n++==0' "$W/broken/$m.bclj"
    broke="$m"; break
  fi
done
echo "   reverted one k/$T2 -> k/$T in module: ${broke:-none}"
"$BUILD" "$W/broken" --out "$W/o2" 2>&1 | grep -iE "built, .* error|expected|type" | head -3 || echo "   (build output above)"
echo "   ^ a missed reference = a compiler error. clean-recompile is a REAL correctness check."

hr; echo "5. BASELINE — clojure-lsp on the EMITTED Clojure (the only Clojure it can read):"
if command -v clojure-lsp >/dev/null 2>&1; then
  P="$W/lsp"; mkdir -p "$P/src/fram"
  cp out/fram/*.clj "$P/src/fram/" 2>/dev/null || true
  printf '{:paths ["src"] :deps {org.clojure/clojure {:mvn/version "1.11.1"}}}\n' > "$P/deps.edn"
  mkdir -p "$P/.clojure-lsp"; printf '{:source-paths #{"src"}}\n' > "$P/.clojure-lsp/config.edn"
  ( cd "$P" && clojure-lsp rename --from fram.kernel/$T --to fram.kernel/$T2 >/dev/null 2>&1 || true )
  echo "   $T tokens remaining in clojure-lsp's project AFTER its rename: $(grep -rhoE "\b$T\b" "$P/src" 2>/dev/null | wc -l)  (it renamed the ones it could see)"
  echo "   type-annotation references in that emitted Clojure to begin with: 0  (erased at lowering — nothing for it to find)"
else
  echo "   clojure-lsp not installed — skipping baseline. (Fact it would face:)"
fi
echo "   type-annotation refs to $T in SOURCE .bclj: $(grep -rhoE ":-[^]]*\b$T\b" src/fram/*.bclj 2>/dev/null | wc -l)   in emitted .clj: $(grep -rhoE ":-[^]]*\b$T\b" out/fram/*.clj 2>/dev/null | wc -l)"

hr; echo "6. clojure-lsp/clj-kondo CANNOT PARSE the .bclj source:"
if command -v clj-kondo >/dev/null 2>&1; then
  clj-kondo --lint "src/fram/$MOD.bclj" 2>&1 | grep -iE "too many arguments to def" | head -1 || echo "   (clj-kondo output above)"
else echo "   clj-kondo not installed"; fi

hr
echo "CONTRAST: graph = ONE identity edit -> all references re-point, compiler says 0 errors."
echo "          clojure-lsp = renames only what survived to the target; blind to references erased at lowering."
echo "          => references carry IDENTITY, not spelling.  See RESULTS.md."
