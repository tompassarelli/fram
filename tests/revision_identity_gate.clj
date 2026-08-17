;; The release gate keeps the two independent revision-identity suites in one
;; Babashka process so the second suite reuses the loaded FRAM namespaces.
;; Run from the repository root: bb -cp out tests/revision_identity_gate.clj
(load-file "tests/framref_codec_test.clj")
(load-file "tests/framlog_fork_test.clj")
