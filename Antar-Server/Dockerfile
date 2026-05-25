# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM golang:1.22-alpine AS builder

WORKDIR /app

# Download deps first — this layer is cached until go.mod/go.sum change
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o antar ./main.go

# ── Stage 2: Run ──────────────────────────────────────────────────────────────
FROM alpine:3.19

WORKDIR /app

# Needed for HTTPS calls to Supabase Auth
RUN apk --no-cache add ca-certificates

COPY --from=builder /app/antar .

EXPOSE 8080

CMD ["./antar"]