{
  description = "fram — fact-engine CLIs (babashka-backed; JVM coordinator daemon)";

  inputs = {
    # Pinned to the same nixpkgs rev the host system tracks.
    nixpkgs.url = "github:NixOS/nixpkgs/e8210c649915deed7080033cdbabcc19e40bb899";

    # Build-time only: turn the committed deps-lock.json into a pure Maven cache.
    # The packaged daemon runs Java directly and has no runtime clj-nix dependency.
    clj-nix.url = "github:jlesquembre/clj-nix/2b1290ee56e9bbd50e9b5874c985d34ad2f1b458";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";

    # Graph-edit authoring is sealed against one published Beagle source. Its
    # nixpkgs follows this flake so the packaged .zo files and the Racket that
    # loads them are built from the exact same package set.
    beagle.url = "github:tompassarelli/beagle/989fff80824f0e5a8936ac0d7e0ceba33b810890";
    beagle.inputs.clj-nix.follows = "clj-nix";
    beagle.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, clj-nix, beagle }:
    let
      # babashka is unavailable on x86_64-darwin in this nixpkgs revision, so
      # advertising that system made `flake check --all-systems` dishonest.
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f system nixpkgs.legacyPackages.${system});

      mkFram = pkgs: cljpkgs:
        let
          daemonDeps = cljpkgs.mk-deps-cache {
            lockfile = ./deps-lock.json;
          };

          # The bin/ scripts resolve HERE = $(dirname $0)/.. and load out/ (compiled
          # Clojure), coord*.clj, chartroom/, defcheck_gate.clj, tests/, and src/
          # from there. The CLI + MCP run on babashka against committed out/. The
          # daemon's exact JVM classpath is resolved once during the build from the
          # pure cache above, then Java runs it directly at runtime.
          runtimePackages = [
            pkgs.babashka
            pkgs.coreutils
            pkgs.bash
            pkgs.gnused
            pkgs.gnugrep
            pkgs.direnv
            pkgs.git
          ];
          runtimePath = pkgs.lib.makeBinPath runtimePackages;
        in
        pkgs.stdenv.mkDerivation (finalAttrs: {
          pname = "fram";
          version = "0-unstable-2026-06-28";
          src = ./.;

          nativeBuildInputs = [
            pkgs.makeWrapper
            pkgs.babashka
            pkgs.clojure
            pkgs.coreutils
          ];

          dontConfigure = true;
          dontBuild = true;

          installPhase = ''
            runHook preInstall

            mkdir -p $out/libexec/fram/tests $out/libexec/fram/chartroom $out/bin
            cp -r out bin src coord.clj coord_daemon.clj pull.clj fri.clj \
              defcheck_gate.clj deps.edn \
              $out/libexec/fram/
            cp tests/fram_mcp.clj $out/libexec/fram/tests/
            # Only chartroom's source is executable runtime input. build/ is a
            # generated analysis corpus with checkout-local paths; docs/tests are
            # development assets and do not belong in the closure.
            cp -r chartroom/src $out/libexec/fram/chartroom/
            chmod -R u+w $out/libexec/fram

            # Resolve tools.deps only while building, against the store-backed
            # lock cache. Canonicalizing every entry prevents a relative project
            # path or cache symlink from becoming a runtime lookup.
            mkdir -p "$TMPDIR/fram-clj-cache"
            (
              cd "$out/libexec/fram"
              export HOME="${daemonDeps}"
              export CLJ_CONFIG="$HOME/.clojure"
              export CLJ_CACHE="$TMPDIR/fram-clj-cache"
              export GITLIBS="$HOME/.gitlibs"
              export JAVA_TOOL_OPTIONS="-Duser.home=${daemonDeps}"

              rawClasspath="$(${pkgs.clojure}/bin/clojure -Srepro -Spath)"
              [ -n "$rawClasspath" ] || {
                echo "fram: clojure -Spath returned an empty daemon classpath" >&2
                exit 1
              }

              canonicalClasspath=
              while IFS= read -r entry; do
                [ -n "$entry" ] || continue
                canonical="$(realpath "$entry")"
                case "$canonical" in
                  "$out"/*|/nix/store/*) ;;
                  *)
                    echo "fram: non-store daemon classpath entry: $canonical" >&2
                    exit 1
                    ;;
                esac
                if [ -z "$canonicalClasspath" ]; then
                  canonicalClasspath="$canonical"
                else
                  canonicalClasspath="$canonicalClasspath:$canonical"
                fi
              done < <(printf '%s\n' "$rawClasspath" | tr ':' '\n')

              [ -n "$canonicalClasspath" ] || {
                echo "fram: failed to canonicalize daemon classpath" >&2
                exit 1
              }
              printf '%s\n' "$canonicalClasspath" > daemon.classpath
              chmod 0444 daemon.classpath
              # tools.deps writes a project-local basis despite CLJ_CACHE. It is
              # build metadata containing the whole cache path, not runtime data.
              rm -rf .cpcache
            )

            # Absolute interpreters for #!/usr/bin/env bash | bb shebangs.
            patchShebangs $out/libexec/fram/bin

            for s in $out/libexec/fram/bin/*; do
              [ -f "$s" ] || continue
              name=$(basename "$s")
              # Keep the installed surface honest and small. Authoring and
              # defcheck helpers stay in libexec for MCP/checkout workflows,
              # but require an external Beagle toolchain and are not advertised
              # as self-contained package commands.
              case "$name" in
                fram|fram-daemon|fram-mcp|fram-primer) ;;
                *) continue ;;
              esac
              chmod +x "$s"
              makeWrapper "$s" "$out/bin/$name" \
                --prefix PATH : "${runtimePath}" \
                --set BABASHKA_CLASSPATH "$out/libexec/fram/out" \
                --set FRAM "$out/libexec/fram" \
                --set FRAM_HOME "$out/libexec/fram" \
                --set FRAM_BIN "$out/libexec/fram/bin" \
                --set FRAM_OUT "$out/libexec/fram/out" \
                --set FRAM_RESOLVE "$out/libexec/fram/chartroom/src/resolve.clj" \
                --set FRAM_PACKAGED "1" \
                --set FRAM_JAVA "${pkgs.jdk}/bin/java" \
                --set FRAM_DAEMON_CLASSPATH_FILE "$out/libexec/fram/daemon.classpath"
            done

            runHook postInstall
          '';

          doInstallCheck = true;
          installCheckPhase = ''
            runHook preInstallCheck

            FRAM_SMOKE_BB="${pkgs.babashka}/bin/bb" \
            FRAM_SMOKE_ENV="${pkgs.coreutils}/bin/env" \
            FRAM_SMOKE_GREP="${pkgs.gnugrep}/bin/grep" \
            FRAM_SMOKE_READLINK="${pkgs.coreutils}/bin/readlink" \
            FRAM_SMOKE_TR="${pkgs.coreutils}/bin/tr" \
            FRAM_SMOKE_REQUIRE_PROC="${if pkgs.stdenv.hostPlatform.isLinux then "1" else "0"}" \
              ${pkgs.bash}/bin/bash ${./tests/package_daemon_smoke.sh} "$out"

            runHook postInstallCheck
          '';

          meta = with pkgs.lib; {
            description = "Fram fact-engine CLI, MCP server, primer, and JVM coordinator daemon";
            longDescription = ''
              Self-contained CLI, MCP server, primer, and JVM coordinator daemon.
              Beagle graph-authoring helpers are retained under libexec and require
              an external BEAGLE_HOME toolchain; they are not public package commands.
            '';
            license = with licenses; [ mit asl20 ];
            platforms = systems;
            mainProgram = "fram";
          };

          # Stable package boundary for consumers such as North. These evaluate
          # to the realized Fram store path, never a literal $out/placeholder.
          passthru = {
            runtimeRoot = "${finalAttrs.finalPackage}/libexec/fram";
            babashkaClasspath = "${finalAttrs.finalPackage}/libexec/fram/out";
          };
        });

      # Authority packaging only. The coordinator authentication, descriptor,
      # receipts, and projection lifecycle live in later slices. This output
      # closes the executable/toolchain boundary and refuses to serve until
      # North supplies the future lease and independently computed closure seal.
      mkGraphEditRuntime = system: pkgs: fram: beaglePkg:
        let
          framRoot = fram.runtimeRoot;
          beagleRevision = beagle.rev;
          runtimePackages = [
            fram
            beaglePkg
            pkgs.babashka
            pkgs.racket
            pkgs.jdk
            pkgs.bash
            pkgs.coreutils
            pkgs.gnugrep
            pkgs.gnused
          ];
          runtimePath = pkgs.lib.makeBinPath runtimePackages;
          coreManifestData = {
            manifestVersion = "fram.graph-edit-runtime-core/v1";
            authorityProfile = "graph-edit-authority-v1";
            verificationOwner = "north";
            selfAttestation = false;
            # The Nix build system the sealed closure was realized for. FRAM binds
            # this into descriptor.runtime.system; it is NEVER inferred from ambient
            # JVM/host state at run time.
            system = system;
            closureDigestField = "intentionally-absent; North computes it from trusted Nix DB NAR hashes";
            sourcePins = {
              beagle = beagleRevision;
            };
            storeRoots = [
              { role = "babashka"; path = "${pkgs.babashka}"; }
              { role = "beagle"; path = "${beaglePkg}"; }
              { role = "fram"; path = "${fram}"; }
              { role = "jdk"; path = "${pkgs.jdk}"; }
              { role = "racket"; path = "${pkgs.racket}"; }
            ];
            executables = {
              babashka = "${pkgs.babashka}/bin/bb";
              coordinatorJava = "${pkgs.jdk}/bin/java";
              coordinatorSource = "${framRoot}/coord_daemon.clj";
              entrypointRelative = "bin/fram-graph-edit-runtime";
              mcpSource = "${framRoot}/tests/fram_mcp.clj";
              racket = "${pkgs.racket}/bin/racket";
            };
            helpers = {
              beagleBuildAll = "${beaglePkg}/bin/beagle-build-all";
              factsCheckEmit = "${beaglePkg}/beagle-lib/private/facts-check-emit.rkt";
              factsRoundtrip = "${beaglePkg}/beagle-lib/private/facts-roundtrip.rkt";
              framResolve = "${framRoot}/chartroom/src/resolve.clj";
            };
            environment = {
              acceptedNorthBindings = [
                "NORTH_FRAM_AUTHORITY_INSTANCE_ID"
                "NORTH_FRAM_AUTHORITY_LEASE_EPOCH"
                "NORTH_FRAM_AUTHORITY_LEASE_ID"
                "NORTH_FRAM_CHECKOUT_ROOT"
                "NORTH_FRAM_CODE_LOG"
                "NORTH_FRAM_CODE_PORT"
                "NORTH_FRAM_RUNTIME_CLOSURE_DIGEST"
                "NORTH_FRAM_SOURCE_ROOT"
              ];
              childPolicy = "env-i-explicit-allowlist";
              ignoredAmbient = [
                "BEAGLE_HOME"
                "FRAM_*"
                "HOME"
                "PATH"
                "direnv"
                "project .mcp.json"
              ];
              runtimePath = runtimePath;
            };
          };
          coreManifest = pkgs.writeText
            "fram-graph-edit-runtime-core-v1.json"
            (builtins.toJSON coreManifestData + "\n");
        in
        pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
          pname = "fram-graph-edit-runtime";
          version = "1";
          src = ./.;

          nativeBuildInputs = [
            pkgs.makeBinaryWrapper
            pkgs.bash
            pkgs.coreutils
            pkgs.diffutils
            pkgs.babashka
            pkgs.gnugrep
            pkgs.python3
          ];

          dontConfigure = true;
          dontBuild = true;

          installPhase = ''
            runHook preInstall

            mkdir -p "$out/bin" "$out/libexec/fram" "$out/share/fram/empty-threads"
            cp ${./bin/fram-graph-edit-runtime} "$out/libexec/fram/fram-graph-edit-runtime"
            cp ${coreManifest} "$out/share/fram/graph-edit-runtime-core-v1.json"
            chmod 0444 "$out/libexec/fram/fram-graph-edit-runtime"

            # Source this hook at the point of use so its binary implementation
            # wins even if another propagated setup hook also defined
            # makeWrapper. A shell wrapper would itself evaluate BASH_ENV before
            # it could clear hostile caller state.
            source ${pkgs.makeBinaryWrapper}/nix-support/setup-hook
            # The pinned hook accumulates optional C fragments in deliberately
            # unset locals, so its generator is not nounset-clean.
            set +u
            makeBinaryWrapper "${pkgs.bash}/bin/bash" \
              "$out/bin/fram-graph-edit-runtime" \
              --add-flag -p \
              --add-flag "$out/libexec/fram/fram-graph-edit-runtime" \
              --unset BASHOPTS \
              --unset BASH_ENV \
              --unset CDPATH \
              --unset ENV \
              --unset FRAM_GRAPH_EDIT_SEALED_ENVIRONMENT_STAGE \
              --unset SHELLOPTS \
              --set HOME "/homeless-shelter" \
              --set LANG C \
              --set LC_ALL C \
              --set PATH "${runtimePath}" \
              --set BEAGLE_HOME "${beaglePkg}" \
              --set FRAM_GRAPH_EDIT_SEALED_BASH "${pkgs.bash}/bin/bash" \
              --set FRAM_GRAPH_EDIT_SEALED_BB "${pkgs.babashka}/bin/bb" \
              --set FRAM_GRAPH_EDIT_SEALED_BEAGLE "${beaglePkg}" \
              --set FRAM_GRAPH_EDIT_SEALED_BUILD_ALL "${beaglePkg}/bin/beagle-build-all" \
              --set FRAM_GRAPH_EDIT_SEALED_CAT "${pkgs.coreutils}/bin/cat" \
              --set FRAM_GRAPH_EDIT_SEALED_CHECK_EMIT "${beaglePkg}/beagle-lib/private/facts-check-emit.rkt" \
              --set FRAM_GRAPH_EDIT_SEALED_EMPTY_THREADS "$out/share/fram/empty-threads" \
              --set FRAM_GRAPH_EDIT_SEALED_ENV "${pkgs.coreutils}/bin/env" \
              --set FRAM_GRAPH_EDIT_SEALED_FRAM "${framRoot}" \
              --set FRAM_GRAPH_EDIT_SEALED_JAVA "${pkgs.jdk}/bin/java" \
              --set FRAM_GRAPH_EDIT_SEALED_MANIFEST "$out/share/fram/graph-edit-runtime-core-v1.json" \
              --set FRAM_GRAPH_EDIT_SEALED_PATH "${runtimePath}" \
              --set FRAM_GRAPH_EDIT_SEALED_RACKET "${pkgs.racket}/bin/racket" \
              --set FRAM_GRAPH_EDIT_SEALED_REALPATH "${pkgs.coreutils}/bin/realpath" \
              --set FRAM_GRAPH_EDIT_SEALED_RESOLVE "${framRoot}/chartroom/src/resolve.clj" \
              --set FRAM_GRAPH_EDIT_SEALED_ROUNDTRIP "${beaglePkg}/beagle-lib/private/facts-roundtrip.rkt"
            set -u

            runHook postInstall
          '';

          doInstallCheck = true;
          installCheckPhase = ''
            runHook preInstallCheck

            FRAM_RUNTIME_TEST_BB="${pkgs.babashka}/bin/bb" \
            FRAM_RUNTIME_TEST_CMP="${pkgs.diffutils}/bin/cmp" \
            FRAM_RUNTIME_TEST_ENV="${pkgs.coreutils}/bin/env" \
            FRAM_RUNTIME_TEST_GREP="${pkgs.gnugrep}/bin/grep" \
            FRAM_RUNTIME_TEST_PYTHON="${pkgs.python3}/bin/python3" \
            FRAM_RUNTIME_TEST_SLEEP="${pkgs.coreutils}/bin/sleep" \
            FRAM_RUNTIME_TEST_SYSTEM="${system}" \
              ${pkgs.bash}/bin/bash ${./tests/package_graph_edit_runtime_smoke.sh} "$out"

            runHook postInstallCheck
          '';

          meta = with pkgs.lib; {
            description = "Default-dark sealed runtime for North-owned Fram graph editing";
            longDescription = ''
              Store-only Fram, Beagle, Racket, Babashka, and JVM graph-edit
              runtime. North remains the independent closure-verification and
              authority owner; this package never self-attests its NAR closure.
            '';
            license = with licenses; [ mit asl20 ];
            platforms = systems;
            mainProgram = "fram-graph-edit-runtime";
          };

          passthru = {
            coreManifest = "${finalAttrs.finalPackage}/share/fram/graph-edit-runtime-core-v1.json";
            framPackage = fram;
            beaglePackage = beaglePkg;
          };
        });
    in
    {
      packages = forAll (system: pkgs: rec {
        fram = mkFram pkgs clj-nix.packages.${system};
        fram-graph-edit-runtime = mkGraphEditRuntime system pkgs fram beagle.packages.${system}.default;
        default = fram;
      });

      checks = forAll (system: pkgs:
        let
          fram = self.packages.${system}.default;
          graphEditRuntime = self.packages.${system}.fram-graph-edit-runtime;
        in {
          packaged-daemon = fram;
          graph-edit-runtime = graphEditRuntime;
          package-contract = pkgs.runCommand "fram-package-contract" {} ''
            test "${fram.runtimeRoot}" = "${fram}/libexec/fram"
            test "${fram.babashkaClasspath}" = "${fram}/libexec/fram/out"
            test -d "${fram.runtimeRoot}"
            test -d "${fram.babashkaClasspath}"
            test "${graphEditRuntime.coreManifest}" = \
              "${graphEditRuntime}/share/fram/graph-edit-runtime-core-v1.json"
            test -x "${graphEditRuntime}/bin/fram-graph-edit-runtime"
            test -r "${graphEditRuntime.coreManifest}"
            touch "$out"
          '';
        });

      apps = forAll (system: pkgs:
        let
          fram = self.packages.${system}.default;
          mkApp = name: {
            type = "app";
            program = "${fram}/bin/${name}";
            meta = {
              description = "Run the packaged Fram ${name} surface";
              platforms = systems;
            };
          };
        in
        {
          default = mkApp "fram";
          fram = mkApp "fram";
          fram-daemon = mkApp "fram-daemon";
          fram-mcp = mkApp "fram-mcp";
          fram-primer = mkApp "fram-primer";
          fram-graph-edit-runtime = {
            type = "app";
            program = "${self.packages.${system}.fram-graph-edit-runtime}/bin/fram-graph-edit-runtime";
            meta = {
              description = "Run the default-dark sealed Fram graph-edit runtime";
              platforms = systems;
            };
          };
        });
    };
}
