package middleware

import (
	"antar/config"
	"antar/pkg/response"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// ── JWKS cache ────────────────────────────────────────────────────────────────

type keyCache struct {
	mu        sync.RWMutex
	keys      map[string]*ecdsa.PublicKey
	fetchedAt time.Time
}

var globalKeyCache = &keyCache{keys: make(map[string]*ecdsa.PublicKey)}

const cacheTTL = time.Hour

// ── Middleware ────────────────────────────────────────────────────────────────

// Auth validates Supabase-issued JWTs on protected routes.
//
// Automatically handles both signing methods:
//
//	ES256 — Supabase "JWT Signing Keys" (new). Public key fetched from JWKS.
//	HS256 — Supabase "JWT Secret" (legacy). Reads SUPABASE_JWT_SECRET from .env.
//
// No manual configuration needed — the algorithm is detected from the JWT header.
func Auth(cfg *config.Config) gin.HandlerFunc {
	jwksURL := cfg.SupabaseURL + "/auth/v1/.well-known/jwks.json"

	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			response.Unauthorized(c, "Authorization header required (Bearer <token>)")
			c.Abort()
			return
		}
		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")

		token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
			switch t.Method.Alg() {

			case "ES256":
				// New Supabase JWT Signing Keys
				kid, _ := t.Header["kid"].(string)
				return resolvePublicKey(jwksURL, kid)

			case "HS256":
				// Legacy JWT Secret
				if cfg.SupabaseJWTSecret == "" {
					return nil, fmt.Errorf("SUPABASE_JWT_SECRET not set")
				}
				return []byte(cfg.SupabaseJWTSecret), nil

			default:
				return nil, fmt.Errorf("unsupported signing algorithm: %s", t.Method.Alg())
			}
		})

		if err != nil {
			slog.Warn("JWT validation failed", "error", err.Error())
			response.Unauthorized(c, "Invalid or expired token")
			c.Abort()
			return
		}

		if !token.Valid {
			response.Unauthorized(c, "Invalid token")
			c.Abort()
			return
		}

		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			response.Unauthorized(c, "Invalid token claims")
			c.Abort()
			return
		}

		userID, _ := claims["sub"].(string)
		if userID == "" {
			response.Unauthorized(c, "User ID missing from token")
			c.Abort()
			return
		}

		c.Set("userID", userID)
		c.Next()
	}
}

// ── Key resolution ────────────────────────────────────────────────────────────

func resolvePublicKey(jwksURL, kid string) (*ecdsa.PublicKey, error) {
	// Try read from cache first
	globalKeyCache.mu.RLock()
	notExpired := time.Since(globalKeyCache.fetchedAt) < cacheTTL
	if notExpired {
		if kid != "" {
			if key, ok := globalKeyCache.keys[kid]; ok {
				globalKeyCache.mu.RUnlock()
				return key, nil
			}
		} else {
			for _, k := range globalKeyCache.keys {
				globalKeyCache.mu.RUnlock()
				return k, nil
			}
		}
	}
	globalKeyCache.mu.RUnlock()

	// Fetch fresh JWKS from Supabase
	keys, err := fetchJWKS(jwksURL)
	if err != nil {
		return nil, err
	}

	globalKeyCache.mu.Lock()
	globalKeyCache.keys = keys
	globalKeyCache.fetchedAt = time.Now()
	globalKeyCache.mu.Unlock()

	if kid != "" {
		if key, ok := keys[kid]; ok {
			return key, nil
		}
	}
	// Fallback: use the first available key if kid not matched
	for _, key := range keys {
		return key, nil
	}
	return nil, fmt.Errorf("no ES256 key found for kid=%q", kid)
}

// ── JWKS fetching and parsing ─────────────────────────────────────────────────

type jwksDoc struct {
	Keys []struct {
		Kty string `json:"kty"` // "EC"
		Kid string `json:"kid"`
		Alg string `json:"alg"` // "ES256"
		Crv string `json:"crv"` // "P-256"
		X   string `json:"x"`
		Y   string `json:"y"`
	} `json:"keys"`
}

func fetchJWKS(url string) (map[string]*ecdsa.PublicKey, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("JWKS fetch: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("JWKS endpoint returned HTTP %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)

	var doc jwksDoc
	if err := json.Unmarshal(body, &doc); err != nil {
		return nil, fmt.Errorf("JWKS parse: %w", err)
	}

	keys := make(map[string]*ecdsa.PublicKey)
	for _, k := range doc.Keys {
		if k.Kty != "EC" || k.Crv != "P-256" {
			continue
		}
		pub, err := ecPublicKeyFromJWK(k.X, k.Y)
		if err != nil {
			slog.Warn("Skipping malformed JWK", "kid", k.Kid, "error", err)
			continue
		}
		keys[k.Kid] = pub
	}

	if len(keys) == 0 {
		return nil, fmt.Errorf("no valid P-256 keys in JWKS from %s", url)
	}
	slog.Info("JWKS refreshed", "keys_loaded", len(keys))
	return keys, nil
}

// ecPublicKeyFromJWK builds an *ecdsa.PublicKey from base64url-encoded x/y coords.
// No external crypto libraries needed — uses Go's standard crypto/ecdsa + crypto/elliptic.
func ecPublicKeyFromJWK(xB64url, yB64url string) (*ecdsa.PublicKey, error) {
	xBytes, err := base64.RawURLEncoding.DecodeString(xB64url)
	if err != nil {
		return nil, fmt.Errorf("decode x: %w", err)
	}
	yBytes, err := base64.RawURLEncoding.DecodeString(yB64url)
	if err != nil {
		return nil, fmt.Errorf("decode y: %w", err)
	}
	return &ecdsa.PublicKey{
		Curve: elliptic.P256(),
		X:     new(big.Int).SetBytes(xBytes),
		Y:     new(big.Int).SetBytes(yBytes),
	}, nil
}
