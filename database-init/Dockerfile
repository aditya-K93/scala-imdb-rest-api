# Small helper image to run the one-shot IMDb loader.
# Use the same Postgres image family/tag as the runtime container (Apple Silicon).
FROM arm64v8/postgres:18.1-alpine

RUN set -eux; \
    apk add --no-cache \
      bash \
      ca-certificates \
      curl \
      gzip \
      coreutils

WORKDIR /
