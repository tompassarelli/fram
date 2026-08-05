# Write/OCC oracle conformance

The corpus is a line-oriented, TAB-separated replay format. Empty trailing
fields are omitted. Subjects match `@[a-z0-9_-]+`, ordinary predicates match
`p_[a-z0-9_]+`, and values match `[a-z0-9_-]+`. The only additional predicate
is the literal `cardinality`. This alphabet keeps predicates outside Fram's
kernel-classification aliases, terminal predicates, reserved predicates,
delivery triggers, and fallback-single predicates; values are always literals,
never `@` references. TAB, `|`, `=`, and a trailing `@<integer>` are therefore
unambiguous delimiters.

One operation appears on each line:

```text
version
assert<TAB>TE<TAB>P<TAB>R[<TAB>BASE]
retract<TAB>TE<TAB>P<TAB>R[<TAB>BASE]
assert-at-version<TAB>TE<TAB>P<TAB>R<TAB>BASE
assert-batch<TAB>TE<TAB>[BASE]<TAB>P=R[@FBASE]|P=R...
assert-batch-at-version<TAB>TE<TAB>BASE<TAB>P=R[@FBASE]|...
```

For `assert-batch`, an omitted top-level base may also be represented by placing
the fact list directly after `TE`; an empty fact list is represented by omitting
both optional fields. A cardinality declaration is
`assert<TAB>@P<TAB>cardinality<TAB>single|multi`.

Each replayed operation emits exactly one normalized line. Successful single
mutations use `I<TAB>ok<TAB>VERSION`; successful batches append
`written=P1,P2<TAB>idempotent=P3`. Rejections use
`I<TAB>reject<TAB>VERSION<TAB>reason=CANON`, followed when present by `code`,
`at`, and `pred`. Keyword rejections use the keyword name; vector rejections
join their strings with `; `. Version reads use `I<TAB>version<TAB>V`.
After the operation lines, the replay emits `final-version<TAB>V` and the live
non-schema triples as `fact<TAB>L<TAB>P<TAB>R`. The harness sorts only the
fingerprint facts before comparison.

The twelve committed corpora are:

- `S0.tsv`: empty-log/version tracer.
- `S1.tsv`: multi-value coexistence and identical-triple idempotency.
- `S2.tsv`: single-cardinality last-write-wins without a base.
- `S3.tsv`: single-cardinality compare-and-set conflicts.
- `S4.tsv`: multi and single retraction, stale-base rejection, and absent data.
- `S5.tsv`: global-version single writes, invalid bases, and idempotency.
- `S6.tsv`: atomic batches, fact-local conflicts, idempotency, and invalid shape.
- `S7.tsv`: global-version batches and fact-local-base rejection.
- `S8.tsv`: cardinality declaration and lossy multi-to-single refusal.
- `F1.tsv`, `F2.tsv`, `F3.tsv`: deterministic 200-operation generated corpora.

## Replay leg

`src/fram/fri_replay.bclj` decides all twelve corpora and folds the accepted
transactions through `fram.store`. Conformance is the replay model and the
folded TermStore agreeing on every corpus, plus a non-empty summary line. The
exact bar is:

```sh
bash tests/fri2_replay_oracle_test.sh
```

Expected output:

```text
fri2-replay: 12/12 oracle corpora replay and fold in agreement
```

## Excluded incidental server facts

Fingerprints exclude facts whose subject matches `@snapshot:*` or `@log:*`:
server snapshot/rotation bookkeeping whose values embed per-run absolute paths,
and therefore vary across runs. They are not write/OCC semantics. Production
snapshot transactions remain authoritative persisted history and replay
normally.
