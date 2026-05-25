package fcm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const fcmSendURL = "https://fcm.googleapis.com/v1/projects/%s/messages:send"

// Client sends Firebase Cloud Messaging notifications via the HTTP v1 API.
// Authentication uses the service account file at GOOGLE_APPLICATION_CREDENTIALS.
type Client struct {
	projectID  string
	tokenSrc   oauth2.TokenSource
	httpClient *http.Client
}

// NewClient reads GOOGLE_APPLICATION_CREDENTIALS, parses the project ID,
// and returns an authenticated FCM client ready to send notifications.
func NewClient(ctx context.Context) (*Client, error) {
	credFile := os.Getenv("GOOGLE_APPLICATION_CREDENTIALS")
	if credFile == "" {
		return nil, fmt.Errorf("GOOGLE_APPLICATION_CREDENTIALS is not set")
	}

	data, err := os.ReadFile(credFile)
	if err != nil {
		return nil, fmt.Errorf("reading credentials file %q: %w", credFile, err)
	}

	// Extract project_id from the service account JSON
	var sa struct {
		ProjectID string `json:"project_id"`
	}
	if err := json.Unmarshal(data, &sa); err != nil || sa.ProjectID == "" {
		return nil, fmt.Errorf("could not parse project_id from credentials file")
	}

	// Build an OAuth2 token source scoped to Firebase Messaging
	creds, err := google.CredentialsFromJSON(ctx, data,
		"https://www.googleapis.com/auth/firebase.messaging")
	if err != nil {
		return nil, fmt.Errorf("creating Google credentials: %w", err)
	}

	return &Client{
		projectID:  sa.ProjectID,
		tokenSrc:   creds.TokenSource,
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}, nil
}

// Message is the FCM payload.
type Message struct {
	Token        string            `json:"token"`
	Notification *Notification     `json:"notification,omitempty"`
	Data         map[string]string `json:"data,omitempty"`
}

// Notification is the visible push shown on the device lock screen / tray.
type Notification struct {
	Title string `json:"title"`
	Body  string `json:"body"`
}

// Send fires a push notification.
// FCM failures are non-fatal — a failed push must never block a trip operation.
func (c *Client) Send(ctx context.Context, msg Message) error {
	if msg.Token == "" {
		return fmt.Errorf("fcm: empty device token")
	}

	tok, err := c.tokenSrc.Token()
	if err != nil {
		return fmt.Errorf("fcm: getting access token: %w", err)
	}

	body, _ := json.Marshal(map[string]any{"message": msg})

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf(fcmSendURL, c.projectID),
		bytes.NewBuffer(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+tok.AccessToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("fcm: request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("fcm: HTTP %d: %s", resp.StatusCode, string(b))
	}

	prefix := msg.Token
	if len(prefix) > 10 {
		prefix = prefix[:10]
	}
	slog.Debug("FCM notification sent", "token_prefix", prefix)
	return nil
}

// SendSilent sends a data-only message with no visible notification.
// Used for Realtime nudges where the app handles the UI itself.
func (c *Client) SendSilent(ctx context.Context, token string, data map[string]string) error {
	return c.Send(ctx, Message{Token: token, Data: data})
}
