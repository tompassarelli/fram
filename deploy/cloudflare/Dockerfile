# Fram coordinator release image. Graal compilation belongs to image builds,
# never to the JVM development loop.
# Build from the REPO ROOT (the build context is the fram checkout):
#   docker build -f deploy/cloudflare/Dockerfile -t fram-coordinator .

FROM clojure:temurin-21-tools-deps-bookworm-slim AS fram-aot

WORKDIR /fram

COPY deps.edn ./
COPY native/deps.edn native/deps.edn
RUN clojure -Sdeps "$(cat native/deps.edn)" -P

COPY out/ out/
COPY coord.clj coord_daemon.clj coord_writer_authority.clj ./
COPY native/build.sh native/build.sh
COPY native/reachability-metadata.json native/reachability-metadata.json
COPY native/src/ native/src/
RUN native/build.sh aot

FROM ghcr.io/graalvm/native-image-community:25.0.2-muslib AS fram-native-image

USER root
WORKDIR /fram
COPY --from=fram-aot /root/.m2 /root/.m2
COPY --from=fram-aot /fram /fram
RUN FRAM_GRAAL_STATIC=1 native/build.sh image

FROM alpine:3.22

RUN addgroup -S fram \
    && adduser -S -G fram fram \
    && install -d -o fram -g fram /data
COPY --from=fram-native-image /fram/native/fram-daemon-graal /usr/local/bin/fram-daemon-graal

# The wire protocol is UNAUTHENTICATED — 0.0.0.0 is safe ONLY because
# docker-compose keeps this port on a private network with the shim as the
# sole authenticated ingress. Never publish 7977 to the internet.
ENV FRAM_BIND=0.0.0.0 \
    FRAM_SNAPSHOT_BOOT=1 \
    FRAM_SPACE_ID=cloudflare

VOLUME /data
EXPOSE 7977

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
  CMD ["nc", "-z", "127.0.0.1", "7977"]

USER fram
ENTRYPOINT ["/usr/local/bin/fram-daemon-graal"]
CMD ["serve", "7977", "/data/history.framlog"]
