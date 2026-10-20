package github

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

type OfficialQueryKind string
type OfficialQueryScope string

const (
	OfficialKeyword OfficialQueryKind = "KEYWORD"
	OfficialTopic   OfficialQueryKind = "TOPIC"

	OfficialMetadataScope OfficialQueryScope = "NAME_DESCRIPTION_TOPICS"
	OfficialReadmeScope   OfficialQueryScope = "README"
)

// OfficialRepositoryQuery is the executable search portion of a Java-owned
// policy snapshot. Policy versions and query catalogs remain in the control plane.
type OfficialRepositoryQuery struct {
	Kind  OfficialQueryKind
	Value string
	Scope OfficialQueryScope
}

// OfficialRepositoryRange is a half-open pushed-time interval. Partition values
// require an agreed compiler and are currently rejected rather than ignored.
type OfficialRepositoryRange struct {
	Since     time.Time
	Until     time.Time
	Partition string
}

// CompileOfficialRepositoryQuery only accepts structured repository searches.
// It does not compile code search or owner expansion. Whole-second boundaries
// avoid silently changing coverage when translating to GitHub's inclusive range.
func CompileOfficialRepositoryQuery(query OfficialRepositoryQuery, window OfficialRepositoryRange,
	fixedCutoffAt time.Time) (string, error) {
	_, sinceOffset := window.Since.Zone()
	_, untilOffset := window.Until.Zone()
	_, cutoffOffset := fixedCutoffAt.Zone()
	if window.Since.IsZero() || window.Until.IsZero() || fixedCutoffAt.IsZero() ||
		!window.Since.Before(window.Until) || window.Until.After(fixedCutoffAt) ||
		window.Since.Nanosecond() != 0 || window.Until.Nanosecond() != 0 || fixedCutoffAt.Nanosecond() != 0 ||
		sinceOffset != 0 || untilOffset != 0 || cutoffOffset != 0 {
		return "", errors.New("invalid official search window or cutoff")
	}
	if window.Partition != "" {
		return "", errors.New("unsupported official search partition")
	}
	value := strings.TrimSpace(query.Value)
	if !utf8.ValidString(value) || value == "" || utf8.RuneCountInString(value) > 256 {
		return "", errors.New("invalid official search value")
	}
	var selector string
	switch query.Kind {
	case OfficialKeyword:
		for _, char := range value {
			if !unicode.IsLetter(char) && !unicode.IsDigit(char) && char != ' ' &&
				char != '-' && char != '_' && char != '.' && char != '/' {
				return "", errors.New("official keyword contains unsupported search syntax")
			}
		}
		switch query.Scope {
		case OfficialMetadataScope:
			selector = `"` + value + `" in:name,description,topics`
		case OfficialReadmeScope:
			selector = `"` + value + `" in:readme`
		default:
			return "", errors.New("unsupported official keyword scope")
		}
	case OfficialTopic:
		if query.Scope != OfficialMetadataScope || !topicPattern.MatchString(value) {
			return "", errors.New("invalid official topic or scope")
		}
		selector = "topic:" + strings.ToLower(value)
	default:
		return "", errors.New("unsupported official repository query kind")
	}
	const timestampFormat = "2006-01-02T15:04:05+00:00"
	return selector + " is:public pushed:" + window.Since.UTC().Format(timestampFormat) +
		".." + window.Until.Add(-time.Second).UTC().Format(timestampFormat), nil
}

// SearchOfficialRepositories executes one page; the caller retains incomplete
// coverage and limit signals and owns durable checkpoints and shard splitting.
func (c *Client) SearchOfficialRepositories(ctx context.Context, query OfficialRepositoryQuery,
	window OfficialRepositoryRange, fixedCutoffAt time.Time, page int) (SearchPage, Quota, error) {
	if page < 1 || page > 10 {
		return SearchPage{}, Quota{}, errors.New("invalid official search page")
	}
	compiled, err := CompileOfficialRepositoryQuery(query, window, fixedCutoffAt)
	if err != nil {
		return SearchPage{}, Quota{}, err
	}
	return c.searchRepositoryPage(ctx, compiled, page)
}
