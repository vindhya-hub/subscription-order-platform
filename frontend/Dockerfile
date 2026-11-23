# syntax=docker/dockerfile:1

# ---- Build the static bundle ----
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .

# Public, build-time settings (compiled into the bundle, so never secrets). See .env.example.
ARG VITE_GRAPHQL_URL=/graphql
ARG VITE_POLL_INTERVAL_MS=2000
RUN VITE_GRAPHQL_URL="$VITE_GRAPHQL_URL" VITE_POLL_INTERVAL_MS="$VITE_POLL_INTERVAL_MS" npm run build

# ---- Serve with nginx, proxying the API so the browser stays same-origin ----
FROM nginx:1.30-alpine
# The official image renders /etc/nginx/templates/*.template with envsubst at start-up.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# Where /graphql is forwarded; override per environment (docker-compose sets it explicitly).
ENV BACKEND_URL=http://backend:8080
EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
