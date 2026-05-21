FROM node:20-alpine

WORKDIR /app

# Install only production deps first (better layer caching)
COPY discord-alert-bot/package.json discord-alert-bot/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

COPY discord-alert-bot/src ./src

EXPOSE 9094

CMD ["node", "src/index.js"]
