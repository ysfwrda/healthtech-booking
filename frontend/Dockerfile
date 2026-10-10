# syntax=docker/dockerfile:1

# ---------- Build stage ----------
# Vite 8 needs Node >= 20.19 / 22.12.
FROM node:25-alpine AS build
WORKDIR /app

# Copy the manifests first so `npm ci` stays cached until dependencies change.
COPY package.json package-lock.json ./
RUN npm ci

# VITE_API_BASE is inlined into the bundle at build time. The browser (not the
# container) calls the API, so this is the host-published gateway address.
ARG VITE_API_BASE=http://localhost:8080
ENV VITE_API_BASE=$VITE_API_BASE

COPY . .
RUN npm run build

# ---------- Runtime stage ----------
FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
