# Fram coordinator daemon image.
# Build from the REPO ROOT (the build context is the fram checkout):
#   docker build -f deploy/cloudflare/Dockerfile -t fram-coordinator .
#
# The daemon runs on the JVM (bin/fram-daemon: real threads, SSLServerSocket
# for optional engine-terminated mTLS). Compiled engine namespaces ship in
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
#   chartroom/src/       resolver (load-file'd: chartroom/src/resolve.clj)
#   defcheck_gate.clj    conditional load (FRAM_DEFCHECK=1)
COPY out/ out/
COPY coord.clj coord_daemon.clj defcheck_gate.clj ./
COPY chartroom/src/ chartroom/src/
COPY bin/fram-daemon bin/fram-daemon

# The wire protocol is UNAUTHENTICATED — 0.0.0.0 is safe ONLY because
# docker-compose keeps this port on a private network with the shim as the
# sole authenticated ingress. Never publish 7977 to the internet.
ENV FRAM_BIND=0.0.0.0 \
    FRAM_SNAPSHOT_BOOT=1

# durable state: the append-only fact log (+ snapshot checkpoints) live here.
# Container restarts refold/snapshot-boot from this file; the Workers hold nothing.
VOLUME /data
EXPOSE 7977

# probe the real protocol: one {:op :version} line must come back as EDN
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/7977 && printf "{:op :version}\n" >&3 && head -c 1 <&3 | grep -q "{"'

# serve-flat = drop-in flat-log mode: /data/facts.log is the single source of
# truth, one {:tx N :op "assert" :l ... :p ... :r ...} line per fact.
CMD ["bin/fram-daemon", "serve-flat", "7977", "/data/facts.log"]
