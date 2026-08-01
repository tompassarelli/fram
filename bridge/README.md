# Fram world ⇄ Git bridge

**Status: historical Worlds compatibility adapter. Not a current recursive-kernel
runtime reference.** The code and examples are retained as migration and design
evidence; their world/version/CID vocabulary does not add primitives to the
current Term/Triple model.

`bridge/world_git.clj` is an additive adapter over the durable world verbs in
`coord.clj`. It writes and reads Git objects directly; it never creates a
working-tree checkout.

Load `coord.clj`, then the bridge:

```clojure
(load-file "coord.clj")
(load-file "bridge/world_git.clj")

(bridge.world-git/import-repo! co "agent" "/path/to/repo"
                               {:world-prefix "project"})

(bridge.world-git/render-version! co version-id "/path/to/output-repo"
                                  {:ref "refs/heads/main"
                                   :object-format "sha1"})
```

Successful calls return `{:ok ...}`. Expected boundary failures return a map
with `:reject`.

## Projection contract

- A Git blob becomes a Fram world blob. File paths become slots. Modes
  `100644` and `100755` are preserved exactly.
- Each reachable Git commit becomes one Version over its first parent. The
  overlay contains only added, deleted, content-changed, or mode-changed slots.
  Every local `refs/heads/*` tip gets a deterministic world head.
- Rendering writes one deterministic commit per durable Version in the base
  chain. Author, committer, timestamps, and message format are fixed, so the
  same VersionId and Git object format produce the same tree and commit IDs
  across runs and processes.
- Git commit metadata and merge second-parent edges are not world data. Import
  preserves every commit snapshot; the single world `:base` follows Git's
  first parent.

## Representability limits

The world kernel is authoritative. Import preflights the complete reachable
corpus before writing any world facts and rejects:

- blobs larger than `fram.world/max-blob-bytes` (524,288 bytes), as
  `:world-blob-too-large`; blobs are never split;
- modes other than `100644` and `100755` (including symlinks and submodules);
- paths outside the world's clean, NFC, strict-UTF-8 slot domain;
- repositories without local branch heads.

Projection also rejects `:git-path-conflict` if a world manifest contains both
a file slot and a descendant slot such as `a` and `a/b`, which a Git tree
cannot represent.
