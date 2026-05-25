package supabase

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
)

// UploadAvatar uploads imageBytes to the "avatars/<driverID>/avatar.<ext>" path
// in Supabase Storage and returns the public URL.
//
// userJWT is the driver's Supabase access token (without "Bearer " prefix).
// It is required by Supabase Storage RLS to verify the upload is coming from
// the correct driver — the policy checks auth.uid() against the folder name.
//
// Supabase Storage expects a raw binary body (NOT multipart/form-data).
func (c *Client) UploadAvatar(driverID, userJWT string, imageBytes []byte, contentType string) (string, error) {
	ext := extensionFromMime(contentType)

	objectPath := fmt.Sprintf("%s/avatar.%s", driverID, ext)
	uploadURL := fmt.Sprintf("%s/storage/v1/object/avatars/%s", c.baseURL, objectPath)

	req, err := http.NewRequest(http.MethodPost, uploadURL, bytes.NewReader(imageBytes))
	if err != nil {
		return "", err
	}

	// Supabase Storage requires both headers:
	//   apikey          — identifies the Supabase project
	//   Authorization   — proves which user is uploading (drives RLS)
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("apikey", c.anonKey)
	req.Header.Set("Authorization", "Bearer "+userJWT)
	// x-upsert: true replaces the file if it already exists (re-upload avatar)
	req.Header.Set("x-upsert", "true")

	resp, err := c.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("storage upload request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("storage error %d: %s", resp.StatusCode, string(body))
	}

	// Public URL — readable by anyone since the bucket is public
	publicURL := fmt.Sprintf("%s/storage/v1/object/public/avatars/%s", c.baseURL, objectPath)
	return publicURL, nil
}

func extensionFromMime(contentType string) string {
	switch contentType {
	case "image/png":
		return "png"
	case "image/webp":
		return "webp"
	default:
		return "jpg"
	}
}
