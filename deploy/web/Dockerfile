FROM node:22-alpine AS web-build
WORKDIR /build
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web/ ./
RUN npm run build

FROM node:22-alpine AS miniapp-build
WORKDIR /build
COPY miniapp/package.json miniapp/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY miniapp/ ./
ARG MINIAPP_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${MINIAPP_API_BASE_URL}
RUN npm run build:h5

FROM caddy:2-alpine
COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
COPY --from=web-build /build/dist /srv/frontend
COPY --from=miniapp-build /build/dist/build/h5 /srv/miniapp
