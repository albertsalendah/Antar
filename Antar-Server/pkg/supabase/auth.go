package supabase

import (
	"antar/config"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// ── Public types ──────────────────────────────────────────────────────────────

// SignUpResult is returned by SignUp.
//
// When Supabase email-confirmation is ON:
//   - UserID is populated (Supabase issues the UUID immediately).
//   - AccessToken / RefreshToken are EMPTY — Supabase withholds them.
//   - NeedsConfirmation = true.
//   - The handler MUST still insert driver_profiles using UserID; the row
//     will sit there harmlessly until the user confirms and then logs in.
//
// When email-confirmation is OFF:
//   - All fields populated, NeedsConfirmation = false.
type SignUpResult struct {
	UserID            string
	AccessToken       string
	RefreshToken      string
	NeedsConfirmation bool
}

// AuthResponse is returned by SignIn.
type AuthResponse struct {
	AccessToken  string
	RefreshToken string
	User         AuthUser
}

type AuthUser struct {
	ID    string `json:"id"`
	Email string `json:"email"`
}

// ── Internal Supabase JSON shapes ─────────────────────────────────────────────

// supabaseSignUpResponse covers both shapes Supabase can return on /signup:
//
// Shape A — email confirmation OFF (user receives tokens immediately):
//
//	{ "access_token":"...", "refresh_token":"...", "user":{"id":"uuid",...} }
//
// Shape B — email confirmation ON (tokens withheld, user object at root):
//
//	{ "id":"uuid", "email":"...", "identities":[...] }
type supabaseSignUpResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	User         struct {
		ID    string `json:"id"`
		Email string `json:"email"`
	} `json:"user"`
	// Shape B — root-level fields present when confirmation is required
	ID    string `json:"id"`
	Email string `json:"email"`
}

type supabaseSignInResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	User         struct {
		ID    string `json:"id"`
		Email string `json:"email"`
	} `json:"user"`
}

type supabaseError struct {
	Message string `json:"message"`
	Msg     string `json:"msg"`
}

func (e supabaseError) text() string {
	if e.Message != "" {
		return e.Message
	}
	return e.Msg
}

// ── Client ────────────────────────────────────────────────────────────────────

type Client struct {
	baseURL string
	anonKey string
	http    *http.Client
}

func NewClient(cfg *config.Config) *Client {
	return &Client{
		baseURL: cfg.SupabaseURL,
		anonKey: cfg.SupabaseAnonKey,
		http:    &http.Client{Timeout: 10 * time.Second},
	}
}

// SignUp creates a new Supabase auth user.
// Always returns a valid UserID on success regardless of confirmation state.
func (c *Client) SignUp(email, password string) (*SignUpResult, error) {
	body, _ := json.Marshal(map[string]string{"email": email, "password": password})

	respBytes, status, err := c.doRequest(http.MethodPost, "/auth/v1/signup", body)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		var e supabaseError
		json.Unmarshal(respBytes, &e)
		return nil, fmt.Errorf("%s", e.text())
	}

	var raw supabaseSignUpResponse
	if err := json.Unmarshal(respBytes, &raw); err != nil {
		return nil, fmt.Errorf("failed to parse signup response: %w", err)
	}

	// Shape A → user.id, Shape B → root id
	userID := raw.User.ID
	if userID == "" {
		userID = raw.ID
	}
	if userID == "" {
		return nil, fmt.Errorf("signup response missing user ID")
	}

	return &SignUpResult{
		UserID:            userID,
		AccessToken:       raw.AccessToken,
		RefreshToken:      raw.RefreshToken,
		NeedsConfirmation: raw.AccessToken == "",
	}, nil
}

// SignIn authenticates with email + password.
// Supabase returns 400 automatically if the email is not confirmed yet.
func (c *Client) SignIn(email, password string) (*AuthResponse, error) {
	body, _ := json.Marshal(map[string]string{"email": email, "password": password})

	respBytes, status, err := c.doRequest(
		http.MethodPost, "/auth/v1/token?grant_type=password", body,
	)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		var e supabaseError
		json.Unmarshal(respBytes, &e)
		return nil, fmt.Errorf("%s", e.text())
	}

	var raw supabaseSignInResponse
	if err := json.Unmarshal(respBytes, &raw); err != nil {
		return nil, fmt.Errorf("failed to parse signin response: %w", err)
	}
	if raw.User.ID == "" {
		return nil, fmt.Errorf("signin response missing user ID")
	}

	return &AuthResponse{
		AccessToken:  raw.AccessToken,
		RefreshToken: raw.RefreshToken,
		User:         AuthUser{ID: raw.User.ID, Email: raw.User.Email},
	}, nil
}

// ── Internal helper ───────────────────────────────────────────────────────────

func (c *Client) doRequest(method, path string, body []byte) ([]byte, int, error) {
	req, err := http.NewRequest(method, c.baseURL+path, bytes.NewBuffer(body))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("apikey", c.anonKey)

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return b, resp.StatusCode, nil
}

// RefreshSession exchanges a refresh token for a new access + refresh token pair.
// Called by POST /api/v1/driver/refresh (public route, no JWT needed).
func (c *Client) RefreshSession(refreshToken string) (*AuthResponse, error) {
	body, _ := json.Marshal(map[string]string{"refresh_token": refreshToken})

	respBytes, status, err := c.doRequest(
		http.MethodPost, "/auth/v1/token?grant_type=refresh_token", body,
	)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		var e supabaseError
		json.Unmarshal(respBytes, &e)
		return nil, fmt.Errorf("%s", e.text())
	}

	var raw supabaseSignInResponse
	if err := json.Unmarshal(respBytes, &raw); err != nil {
		return nil, fmt.Errorf("failed to parse refresh response: %w", err)
	}
	if raw.User.ID == "" {
		return nil, fmt.Errorf("refresh succeeded but user ID missing")
	}

	return &AuthResponse{
		AccessToken:  raw.AccessToken,
		RefreshToken: raw.RefreshToken,
		User:         AuthUser{ID: raw.User.ID, Email: raw.User.Email},
	}, nil
}
