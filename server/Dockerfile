# The Spunto server image (02 §10.1): amd64 and arm64, runs as UID 10001 and
# writes only to /data and /tmp, so it works with a read-only root filesystem.
#
#   docker buildx build --platform linux/amd64,linux/arm64 -f server/Dockerfile .

ARG PYTHON=python:3.14-slim-trixie@sha256:a2b82f3c48559aa0a8446d9af49826b6e2b2016f4cd2afabfe6013ec53729170
ARG NODE=node:26.9.0-slim@sha256:3a771f83944bb763050c23c0225c260638c4b7899e7a72485ef75e5e570499e5

FROM ${NODE} AS web
WORKDIR /web
RUN npm install --global pnpm@12.11.2
COPY web/package.json web/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM ${PYTHON} AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.0@sha256:cdc6093146eb3ff6a40107b38f008b789e050e77ad87865e381d9917da55a168 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app
WORKDIR /src
# Dependencies first, so a code change reuses this layer.
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=bind,source=server/pyproject.toml,target=pyproject.toml \
    --mount=type=bind,source=server/uv.lock,target=uv.lock \
    uv sync --locked --no-dev --no-install-project
COPY server/pyproject.toml server/uv.lock ./
COPY server/src ./src
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-editable

FROM ${PYTHON}
ARG VERSION=dev
ARG REVISION=dev
LABEL org.opencontainers.image.title="Spunto" \
      org.opencontainers.image.description="Self-hosted, AI-first notes" \
      org.opencontainers.image.source="https://github.com/vitofico/spunto" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
ENV PATH=/app/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SPUNTO_VERSION=${VERSION} \
    SPUNTO_WEB_DIR=/app/web
# uv builds an unseeded application environment; runtime package installers are
# unused, and pip's vendored dependencies carry separate vulnerability advisories.
RUN python -m pip uninstall --yes pip \
    && python -c 'import shutil, sysconfig; from pathlib import Path; shutil.rmtree(Path(sysconfig.get_path("stdlib")) / "ensurepip")'
# A new named volume copies this directory's owner, so the server can write to it.
RUN install -d -o 10001 -g 10001 -m 0750 /data
COPY --from=build /app /app
COPY --from=web /web/dist /app/web
USER 10001:10001
EXPOSE 8080
CMD ["spunto", "serve"]
