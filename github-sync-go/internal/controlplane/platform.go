package controlplane

import (
	"context"
	"errors"
	"net/url"
)

const PlatformPauseCapability = "platform-pause/v1"

type PlatformControl struct {
	Action          string `json:"action"`
	PlatformVersion int64  `json:"platformVersion"`
}
type PlatformPause struct {
	Lease               LeaseProof                  `json:"lease"`
	ObservedVersion     int64                       `json:"observedVersion"`
	Kind                string                      `json:"kind"`
	DiscoveryCheckpoint *string                     `json:"discoveryCheckpoint,omitempty"`
	RepositoryProgress  *OfficialRepositoryProgress `json:"repositoryProgress,omitempty"`
}

func (c *Client) GetPlatformControl(ctx context.Context, task string, lease LeaseProof) (PlatformControl, error) {
	var result PlatformControl
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(task)+"/platform-control", struct {
		Lease LeaseProof `json:"lease"`
	}{lease}, &result)
	if err == nil && (result.PlatformVersion < 1 || result.Action != "CONTINUE" && result.Action != "PAUSE") {
		err = errors.New("invalid platform control")
	}
	return result, err
}
func (c *Client) PausePlatform(ctx context.Context, task string, request PlatformPause) error {
	var result struct {
		Status string `json:"status"`
	}
	_, err := c.post(ctx, "/tasks/"+url.PathEscape(task)+"/platform-pause", request, &result)
	if err == nil && result.Status != "PAUSED" {
		err = errors.New("platform pause not acknowledged")
	}
	return err
}

type checkpointObserverKey struct{}

func WithCheckpointObserver(ctx context.Context, observer func(string)) context.Context {
	return context.WithValue(ctx, checkpointObserverKey{}, observer)
}
