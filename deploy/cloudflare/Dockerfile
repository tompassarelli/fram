# Fram coordinator daemon image.
# Build from the REPO ROOT (the build context is the fram checkout):
#   docker build -f deploy/cloudflare/Dockerfile -t fram-coordinator .
#
# The daemon runs on the JVM (bin/fram-daemon: real threads). Compiled namespaces ship in
# out/ — nothing to build; `clojure -P` below only warms the tiny dep cache
# (clojure + cheshire) into the image so first boot never touches the network.
FROM clojure:temurin-21-tools-deps-bookworm-slim

WORKDIR /fram

# deps first — cached layer unless deps.edn changes
COPY deps.edn ./
RUN clojure -P

# exactly what coord_daemon.clj needs at runtime:
#   out/                 compiled engine namespaces (classpath, deps.edn :paths)
#   coord.clj            reified coordinator library (load-file'd)
#   coord_writer_authority.clj  writer-token admission (load-file'd)
#   rotations.clj        covering read indexes (load-file'd)
#   out/resolve.clj      generated namespace-preserving resolver shim
#   out/defcheck_gate.clj conditional load (FRAM_DEFCHECK=1)
COPY out/ out/
COPY coord.clj coord_daemon.clj coord_writer_authority.clj ./
COPY bin/fram-daemon bin/fram-daemon

# The wire protocol is UNAUTHENTICATED — 0.0.0.0 is safe ONLY because
# docker-compose keeps this port on a private network with the shim as the
# sole authenticated ingress. Never publish 7977 to the internet.
ENV FRAM_BIND=0.0.0.0 \
    FRAM_SNAPSHOT_BOOT=1 \
    FRAM_SPACE_ID=cloudflare

# durable state: the append-only fact log (+ snapshot checkpoints) live here.
# Container restarts refold/snapshot-boot from this file; the Workers hold nothing.
VOLUME /data
EXPOSE 7977

# Probe the real binary protocol with the same shared client used by data tools.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD clojure -M -e "(require '[fram.rt :as rt] '[coord-daemon-wire :as wire]) (rt/require-native-success! (rt/native-call! 7977 :rpc/version wire/rpc-unit))"

# One-shot migration owns any legacy facts.log conversion before this image is
# deployed. The live source is the recursive-term FRAMLOG only.
CMD ["bin/fram-daemon", "serve", "7977", "/data/history.framlog"]
