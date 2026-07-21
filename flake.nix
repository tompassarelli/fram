{
  description = "fram — fact-engine CLIs (babashka-backed; JVM coordinator daemon)";

  inputs = {
    # Pinned to the same nixpkgs rev the host system tracks.
    nixpkgs.url = "github:NixOS/nixpkgs/e8210c649915deed7080033cdbabcc19e40bb899";

    # Build-time only: turn the committed deps-lock.json into a pure Maven cache.
    # The packaged daemon runs Java directly and has no runtime clj-nix dependency.
    clj-nix.url = "github:jlesquembre/clj-nix/2b1290ee56e9bbd50e9b5874c985d34ad2f1b458";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, clj-nix }:
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
            cp -r out bin src coord.clj coord_daemon.clj pull.clj \
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
    in
    {
      packages = forAll (system: pkgs: rec {
        fram = mkFram pkgs clj-nix.packages.${system};
        default = fram;
      });

      checks = forAll (system: pkgs:
        let fram = self.packages.${system}.default;
        in {
          packaged-daemon = fram;
          package-contract = pkgs.runCommand "fram-package-contract" {} ''
            test "${fram.runtimeRoot}" = "${fram}/libexec/fram"
            test "${fram.babashkaClasspath}" = "${fram}/libexec/fram/out"
            test -d "${fram.runtimeRoot}"
            test -d "${fram.babashkaClasspath}"
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
        });
    };
}
