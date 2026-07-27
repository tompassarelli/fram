# M1 final-switch accounting

Observed 2026-07-28 at `HEAD == origin/main == 4a0fc2657b9387f8e4b947ad4cf0e6083f47c212`.

## Measurement

`~/code/fram/scripts/m1-final-switch-accounting.clj` uses
`clojure.core/read` over a `LineNumberingPushbackReader` with
`{:read-cond :allow :features #{:bb :clj}}`. It does not grep
`~/code/fram/resolve.clj`. A def is PASS-THROUGH only when it contains at
least one qualified symbol and every qualified namespace is an alias for a
ported `resolve-*` module. A def with no qualified symbol, or any qualified
symbol outside those ported aliases, is SUBSTANTIVE. Thus a wrapper that
dereferences state (`clojure.core/deref`), calls `c/*`, `str/*`, `System/*`,
or any other non-ported qualified namespace is SUBSTANTIVE.

Rerun:

```sh
bb ~/code/fram/scripts/m1-final-switch-accounting.clj ~/code/fram
```

Observed:

| revision | lines | top-level forms | defs | PASS-THROUGH | SUBSTANTIVE |
|---|---:|---:|---:|---:|---:|
| LINK 9 `7396fc2` | 1252 | 185 | 182 | 94 | 88 |
| current `4a0fc265` | 1046 | 186 | 183 | 105 | 78 |
| trajectory | -206 | +1 | +1 | +11 | -10 |

The script re-read `7396fc2:resolve.clj` and reproduced LINK 9's published
`1252 / 94 / 88` numbers exactly. Current ported aliases are
`rb rc rco rm rmi rq rr rv rvb rw`. No consumer file selected by the
qualified-reference scan failed to read.

Judgment event: the 206-line drain did not imply a comparable substantive-def
drain. Under the strict LINK 9 rule, Cut L wrappers that still dereference
host atoms or touch `clojure.core` remain SUBSTANTIVE; only ten substantive
defs drained.

## Remaining cut sizes

Form lines are the union of physical lines occupied by the reader forms; shared
same-line defs are counted once per group. Comments and blank lines are not
charged.

| group | defs | form lines | required cut |
|---|---:|---:|---|
| corpus/store frame residue | 52 | 81 | Move the remaining store load/state installation, dereferencing wrappers, frame/export tables, and corpus adapters into `src/resolve_corpus.bclj` or explicit host-state operations. |
| extract/emit residue | 16 | 46 | Move structural traversal, projection/extract adapters, scoped emission, and output selection into `src/resolve_mint.bclj` / `src/resolve_verbs.bclj` or one graph-native emit module. |
| CLI/main | 2 | 109 | Move `mode` and the 108-line `-main` dispatch into a graph-native resolver entry module; update package/bin callers that currently require the root file path. |
| other | 8 | 46 | Move lint logic to core/modules, verb-host assembly to verbs, and query adapters to query; retain only the justified host policy seam. |
| total | 78 | 282 | Not deletion-ready. |

Every remaining SUBSTANTIVE def and its syntactic internal consumers plus
external qualified consumers follows. External counts include
`resolve/<name>` and aliases such as `rsv/<name>`.

```text
!eso/1
substantive_defs[78]{group,name,lines,internal_consumers,external_consumers}
cli-main	mode	35	-main	-
cli-main	-main	933-1040	-	-
corpus-store-frame-residue	ctx	46	anchor-match-sites,anchor-matches,binding-name,binding-privacy,bound-target,brackets?,call-edges,collect-bind-syms,collect-or-vals,corpus-state,emit-env,for-bind-pairs,forms-of,frame-of,head-sym,import-graph,kind-of,let-bind-pairs,lift-bound-to-refers!,live-node?,load-edn,make-xresolve,map-node?,match-pat-binds,merge-import-opts,mint-env,module-accessors,module-defs,module-export-set,module-exports,module-has-macro?,module-imports,module-name,module-types,node->canon,node->str,ns-form,ord-edges,ordered-children,ordered-segs,param-binds,parse-require,pred-val,refers-target,render-sym,select-causal-1,select-main-1,structural-kids,sym-val,type-name-leaf,ultimate,unwrap-def,unwrap-meta,verb-env,view-cids,walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=11,~/code/fram/tests/coord_crdt_coupled_receipt.clj=4,~/code/fram/tests/coord_gate_receipt.clj=4,~/code/fram/tests/coord_ksweep.clj=4,~/code/fram/tests/coord_views_resolve_test.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=2
corpus-store-frame-residue	tx	47	corpus-state,lift-bound-to-refers!,load-edn,mint-env,verb-env,walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=1
corpus-store-frame-residue	SUP	48	mint-env,verb-env,with-corpus-state!	-
corpus-store-frame-residue	*resolve-walk?*	68	corpus-state	~/code/fram/coord_daemon.clj=1,~/code/fram/tests/coord_gate_feasibility.clj=2,~/code/fram/tests/coord_ksweep.clj=2
corpus-store-frame-residue	*corpus-scope*	76	corpus-state	~/code/fram/coord_daemon.clj=1,~/code/fram/tests/coord_gate_feasibility.clj=1,~/code/fram/tests/coord_ksweep.clj=1
corpus-store-frame-residue	*corpus-cache*	83	corpus-state	~/code/fram/coord_daemon.clj=1
corpus-store-frame-residue	file->ents	84	-main,binding-privacy,call-edges,corpus-state,emit-env,forms-of,import-graph,install-warm-corpus!,load-edn,make-xresolve,mint-env,module-accessors,module-defs,module-export-set,module-exports,module-has-macro?,module-imports,module-name,module-types,ns-form,parse-require,verb-env,walk-corpus,with-corpus-state!,wrapper-of	~/code/fram/coord_daemon.clj=3,~/code/fram/tests/store_delete_reorder_test.clj=2
corpus-store-frame-residue	load-edn	86-96	corpus-host	-
corpus-store-frame-residue	Vp	101	mint-env,verb-env,with-corpus-state!	~/code/fram/coord_daemon.clj=2,~/code/fram/tests/store_delete_reorder_test.clj=2
corpus-store-frame-residue	KIND	101	corpus-state,lift-bound-to-refers!,live-node?,mint-env,verb-env,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	REFERS	101	anchor-match-sites,anchor-matches,binding-name,call-edges,corpus-state,emit-env,lift-bound-to-refers!,mint-env,node->canon,node->str,refers-target,render-sym,ultimate,verb-env,walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=4,~/code/fram/tests/coord_crdt_coupled_receipt.clj=1,~/code/fram/tests/coord_gate_receipt.clj=1,~/code/fram/tests/coord_ksweep.clj=1
corpus-store-frame-residue	BOUND	102	anchor-match-sites,anchor-matches,binding-name,bound-target,call-edges,corpus-state,emit-env,lift-bound-to-refers!,mint-env,node->canon,node->str,refers-target,render-sym,ultimate,verb-env,walk-env,with-corpus-state!	-
corpus-store-frame-residue	FIXED	103	anchor-match-sites,anchor-matches,emit-env,mint-env,node->canon,node->str,render-sym,verb-env,walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=4
corpus-store-frame-residue	QUAL	103	walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	CTOR	104	walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	ACC	105	walk-env,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	*view*	119	anchor-match-sites,anchor-matches,binding-name,binding-privacy,bound-target,brackets?,call-edges,collect-bind-syms,collect-or-vals,corpus-state,emit-env,for-bind-pairs,forms-of,frame-of,head-sym,import-graph,kind-of,let-bind-pairs,make-xresolve,map-node?,match-pat-binds,merge-import-opts,mint-env,module-accessors,module-defs,module-export-set,module-exports,module-has-macro?,module-imports,module-name,module-types,node->canon,node->str,ns-form,param-binds,parse-require,pred-val,refers-target,render-sym,select-causal-1,select-main-1,sym-val,type-name-leaf,ultimate,unwrap-def,unwrap-meta,verb-env,walk-env	~/code/fram/tests/coord_views_resolve_test.clj=7
corpus-store-frame-residue	n-resolved	244	-main,walk-env,with-corpus-state!	-
corpus-store-frame-residue	n-unresolved	244	-main,walk-env,with-corpus-state!	-
corpus-store-frame-residue	n-xmod	245	-main,walk-env,with-corpus-state!	-
corpus-store-frame-residue	n-type	245	-main,walk-env,with-corpus-state!	-
corpus-store-frame-residue	n-comment	246	-main,walk-env,with-corpus-state!	-
corpus-store-frame-residue	n-forms-walked	250	run-resolution!,run-resolution-over!,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	walked-modules	250	run-resolution!,run-resolution-over!,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	*xresolve*	251	re-resolve!,walk-env	-
corpus-store-frame-residue	*tresolve*	252	re-resolve!,walk-env	-
corpus-store-frame-residue	*aresolve*	253	re-resolve!,walk-env	-
corpus-store-frame-residue	module-defs	286	re-resolve!	-
corpus-store-frame-residue	forms-of	288	re-resolve!,verb-env	-
corpus-store-frame-residue	ns-form	289	-	-
corpus-store-frame-residue	module-name	290	scope-match?,verb-env	-
corpus-store-frame-residue	parse-require	292	verb-env	-
corpus-store-frame-residue	module-exports	293	-	-
corpus-store-frame-residue	module-types	295	re-resolve!	-
corpus-store-frame-residue	module-accessors	296	re-resolve!	-
corpus-store-frame-residue	srcs	304	-main,author-emit!,binding-privacy,call-edges,corpus-state,emit-srcs,import-graph,install-warm-corpus!,re-resolve!,run-verb-warm!,scope->srcs,verb-env,walk-corpus,with-corpus-state!	~/code/fram/coord_daemon.clj=12,~/code/fram/tests/coord_bound_identity_coldrestart_receipt.clj=1,~/code/fram/tests/store_colon_marker_roundtrip_test.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=3
corpus-store-frame-residue	file-modframe	305	call-edges,def-binding,install-corpus-tables!,verb-env,walk-corpus,with-corpus-state!	~/code/fram/coord_daemon.clj=2,~/code/fram/tests/coord_bound_identity_coldrestart_receipt.clj=1
corpus-store-frame-residue	file-typeframe	306	def-binding,install-corpus-tables!,verb-env,walk-corpus,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	file-accessors	307	install-corpus-tables!,walk-corpus,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	def-binding	308	verb-env	~/code/fram/coord_daemon.clj=2,~/code/fram/tests/coord_concern_overlap_test.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=4
corpus-store-frame-residue	global-exports	313	install-corpus-tables!,make-xresolve,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	global-type-exports	318	install-corpus-tables!,make-xresolve,with-corpus-state!	~/code/fram/coord_daemon.clj=3
corpus-store-frame-residue	global-accessor-exports	322	install-corpus-tables!,make-xresolve,with-corpus-state!	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	make-xresolve	323-325	re-resolve!,run-resolution!,run-resolution-over!	-
corpus-store-frame-residue	walk-corpus	341	resolve-comment,run-resolution!,run-resolution-over!,walk-comments	-
corpus-store-frame-residue	install-corpus-tables!	368-374	corpus-state,install-warm-corpus!	-
corpus-store-frame-residue	install-warm-corpus!	376-379	corpus-state	-
corpus-store-frame-residue	with-corpus-state!	392-402	corpus-host	-
corpus-store-frame-residue	module-export-set	455-456	-	~/code/fram/coord_daemon.clj=2
corpus-store-frame-residue	module-imports	461-462	-	-
corpus-store-frame-residue	import-graph	463-464	-	~/code/fram/coord_daemon.clj=1
corpus-store-frame-residue	module-has-macro?	469-470	-	~/code/fram/coord_daemon.clj=1
extract-emit-residue	*deleted-forms*	552	emit-env	-
extract-emit-residue	*deleted-subtree*	553	emit-env	-
extract-emit-residue	wrapper-of	554	emit-env,form-for-victim,verb-env	~/code/fram/coord_daemon.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=1
extract-emit-residue	structural-kids	555-561	descendants	-
extract-emit-residue	descendants	562-567	emit-env,verb-env	-
extract-emit-residue	form-for-victim	568-573	verb-env	~/code/fram/coord_daemon.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=3
extract-emit-residue	emit-env	579-581	extract-file!	-
extract-emit-residue	extract-file!	582-585	-main,author-emit!,author-emit-scoped!,verb-env	~/code/fram/coord_daemon.clj=2,~/code/fram/tests/store_colon_marker_roundtrip_test.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=1
extract-emit-residue	*resolve-out*	592	out-path,run-verb-warm!	-
extract-emit-residue	out-path	593-594	-main,author-emit!,author-emit-scoped!,extract-file!,verb-env	-
extract-emit-residue	*project-srcs*	645	emit-srcs,run-verb-warm!	-
extract-emit-residue	emit-srcs	646	author-emit-scoped!,verb-env	-
extract-emit-residue	*capture-only?*	655	author-emit-scoped!,verb-env	~/code/fram/coord_daemon.clj=1
extract-emit-residue	author-emit-scoped!	657-663	verb-env	-
extract-emit-residue	scope-match?	676-678	scope->srcs	-
extract-emit-residue	scope->srcs	679	verb-env	-
other	*reject!*	59	verb-env	~/code/fram/coord_daemon.clj=1,~/code/fram/tests/store_colon_marker_roundtrip_test.clj=1,~/code/fram/tests/store_delete_reorder_test.clj=2
other	named-def-head?	184-185	-	-
other	extend-method-form?	202-206	extend-target-lint	-
other	extend-target-lint	207-218	-	~/code/fram/coord_daemon.clj=1
other	verb-env	775-788	run-verb-warm!,verb-delete!,verb-insert-comment!,verb-insert-form!,verb-rename!,verb-reorder!,verb-replace-in-body!,verb-set-body!,verb-upsert-form!,wrap-forms,writable-victim	-
other	run-verb-warm!	867-876	-	~/code/fram/coord_daemon.clj=1
other	call-edges	897	-main	~/code/fram/coord_daemon.clj=1
other	binding-privacy	911	-main	-
```

## Dynamic-var host seam

There are 39 `^:dynamic` Vars, unchanged from LINK 9. In
`~/code/fram/coord_daemon.clj` plus `~/code/fram/tests/**/*.clj`, 23 Vars have
external `binding` sites: 39 binder entries total. The same scope contains 111
qualified references, counting binder occurrences.

`qN/bN` means N qualified references and N binding-vector entries in that file.

```text
!eso/1
dynamic_vars[39]{name,qualified_refs,binding_sites,sites}
*aresolve*	0	0	-
*capture-only?*	1	1	~/code/fram/coord_daemon.clj:q1/b1
*corpus-cache*	1	1	~/code/fram/coord_daemon.clj:q1/b1
*corpus-scope*	3	3	~/code/fram/coord_daemon.clj:q1/b1,~/code/fram/tests/coord_gate_feasibility.clj:q1/b1,~/code/fram/tests/coord_ksweep.clj:q1/b1
*deleted-forms*	0	0	-
*deleted-subtree*	0	0	-
*project-srcs*	0	0	-
*reject!*	4	4	~/code/fram/coord_daemon.clj:q1/b1,~/code/fram/tests/store_colon_marker_roundtrip_test.clj:q1/b1,~/code/fram/tests/store_delete_reorder_test.clj:q2/b2
*resolve-out*	0	0	-
*resolve-walk?*	5	5	~/code/fram/coord_daemon.clj:q1/b1,~/code/fram/tests/coord_gate_feasibility.clj:q2/b2,~/code/fram/tests/coord_ksweep.clj:q2/b2
*tresolve*	0	0	-
*view*	7	7	~/code/fram/tests/coord_views_resolve_test.clj:q7/b7
*xresolve*	0	0	-
ACC	2	1	~/code/fram/coord_daemon.clj:q2/b1
BOUND	0	0	-
CTOR	2	1	~/code/fram/coord_daemon.clj:q2/b1
FIXED	4	1	~/code/fram/coord_daemon.clj:q4/b1
KIND	2	1	~/code/fram/coord_daemon.clj:q2/b1
QUAL	2	1	~/code/fram/coord_daemon.clj:q2/b1
REFERS	7	1	~/code/fram/coord_daemon.clj:q4/b1,~/code/fram/tests/coord_crdt_coupled_receipt.clj:q1/b0,~/code/fram/tests/coord_gate_receipt.clj:q1/b0,~/code/fram/tests/coord_ksweep.clj:q1/b0
SUP	0	0	-
Vp	4	1	~/code/fram/coord_daemon.clj:q2/b1,~/code/fram/tests/store_delete_reorder_test.clj:q2/b0
ctx	26	2	~/code/fram/coord_daemon.clj:q11/b1,~/code/fram/tests/coord_crdt_coupled_receipt.clj:q4/b0,~/code/fram/tests/coord_gate_receipt.clj:q4/b0,~/code/fram/tests/coord_ksweep.clj:q4/b0,~/code/fram/tests/coord_views_resolve_test.clj:q1/b1,~/code/fram/tests/store_delete_reorder_test.clj:q2/b0
file->ents	5	1	~/code/fram/coord_daemon.clj:q3/b1,~/code/fram/tests/store_delete_reorder_test.clj:q2/b0
file-accessors	2	1	~/code/fram/coord_daemon.clj:q2/b1
file-modframe	3	1	~/code/fram/coord_daemon.clj:q2/b1,~/code/fram/tests/coord_bound_identity_coldrestart_receipt.clj:q1/b0
file-typeframe	2	1	~/code/fram/coord_daemon.clj:q2/b1
global-accessor-exports	2	1	~/code/fram/coord_daemon.clj:q2/b1
global-exports	2	1	~/code/fram/coord_daemon.clj:q2/b1
global-type-exports	3	1	~/code/fram/coord_daemon.clj:q3/b1
n-comment	0	0	-
n-forms-walked	2	0	~/code/fram/coord_daemon.clj:q2/b0
n-resolved	0	0	-
n-type	0	0	-
n-unresolved	0	0	-
n-xmod	0	0	-
srcs	17	1	~/code/fram/coord_daemon.clj:q12/b1,~/code/fram/tests/coord_bound_identity_coldrestart_receipt.clj:q1/b0,~/code/fram/tests/store_colon_marker_roundtrip_test.clj:q1/b0,~/code/fram/tests/store_delete_reorder_test.clj:q3/b0
tx	1	1	~/code/fram/coord_daemon.clj:q1/b1
walked-modules	2	0	~/code/fram/coord_daemon.clj:q2/b0
```

The eventual host shim must preserve Var identity in namespace `resolve` for
exactly the 23 externally bound names:
`*capture-only?*`, `*corpus-cache*`, `*corpus-scope*`, `*reject!*`,
`*resolve-walk?*`, `*view*`, `ACC`, `CTOR`, `FIXED`, `KIND`, `QUAL`, `REFERS`,
`Vp`, `ctx`, `file->ents`, `file-accessors`, `file-modframe`,
`file-typeframe`, `global-accessor-exports`, `global-exports`,
`global-type-exports`, `srcs`, and `tx`.

A value alias is not a replacement for these Vars: external
`binding [resolve/x ...]` must bind the same Var read by the wrappers. Before
root deletion, a generated `ns resolve` entry must define those Vars directly,
then pass their current values into graph-authored functions. The other 16
dynamic roots have no external binder and can move behind that entry; qualified
read-only exports such as `n-forms-walked` may be value aliases if their Var is
never rebound.

## Golden observation

Probe:

```sh
bash ~/code/fram/tests/resolve_golden.sh verify ~/code/fram/tests/goldens/resolve
```

Observed exit 1, not the requested 11/11 green. Of 44 artifacts across the 11
cases, 43 were byte-identical. The only drift was
`resolve-bjs.proj`, which gained the 282-line
`resolved-trap-collision.bjs.edn` projection. `git diff HEAD` was empty for
`~/code/fram/resolve.clj`, `~/code/fram/coord_daemon.clj`,
`~/code/fram/tests/resolve_golden.sh`,
`~/code/fram/tests/goldens/resolve`, and
`~/code/fram/codegraph/test/trap-collision.bjs`; the mismatch is pre-existing
on current main. I did not recapture, mask, or edit the golden.

Worst-fit clue: 105 defs are already PASS-THROUGH and only 282 reader-form lines
remain substantive, but package and tool wiring still names the physical root
`~/code/fram/resolve.clj`, while 23 externally bound Vars require namespace-preserving
identity. Those seams make a file deletion unsafe even if the residual logic
were mechanically moved.

Not done: no resolver, Beagle module, build wiring, fixture, or golden changed;
no graph edit or adoption occurred; no original was deleted. Beagle doctor
functional canaries and emitters were live, but the daemon revive failed and
the repair-loop status remained DEGRADED; this did not affect the Babashka
reader accounting or the raw Clojure golden execution.

blocked-by-plan — current main's committed resolve golden already disagrees
with its resolver/corpus on one unmasked projection, so the requested 11/11
read-only bar cannot be met without a separate behavior/golden decision.

VERDICT: switch is not legal now — remaining cut list: corpus/store frame residue 52 defs / 81 form lines; extract/emit residue 16 / 46; CLI/main 2 / 109; other 8 / 46; then preserve the 23 externally bound Vars in a namespace-`resolve` host shim and update every physical-root-path consumer before deleting `~/code/fram/resolve.clj`.
