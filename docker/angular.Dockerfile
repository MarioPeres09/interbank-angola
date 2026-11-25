FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/angular-app/package*.json ./
RUN npm ci
COPY frontend/angular-app/angular.json frontend/angular-app/tsconfig*.json ./
COPY frontend/angular-app/src ./src
RUN mkdir -p public && npm run build

FROM nginx:alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/angular-app/browser/ /usr/share/nginx/html/
