FROM node:24.8.0-alpine AS build
WORKDIR /workspace
ARG VITE_OIDC_ISSUER
ARG VITE_OIDC_CLIENT_ID=sentinelops-console
ARG VITE_OIDC_REDIRECT_URI
ENV VITE_OIDC_ISSUER=${VITE_OIDC_ISSUER}
ENV VITE_OIDC_CLIENT_ID=${VITE_OIDC_CLIENT_ID}
ENV VITE_OIDC_REDIRECT_URI=${VITE_OIDC_REDIRECT_URI}
COPY web/ops-console/package.json web/ops-console/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web/ops-console/ ./
RUN npm run build

FROM nginx:1.29.1-alpine
COPY web/ops-console/nginx.conf /etc/nginx/templates/nginx.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp
USER nginx
EXPOSE 8080
CMD ["nginx", "-c", "/tmp/nginx.conf", "-g", "daemon off;"]
