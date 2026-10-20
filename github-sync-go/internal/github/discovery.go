package github

import (
	"context"
	"errors"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const repositoryPageSize = 100

var topicPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9-]*$`)

type SearchPage struct {
	Repositories      []Repository
	TotalCount        int
	IncompleteResults bool
	LimitReached      bool
	HasMore           bool
}

type OwnerType string

const (
	UserOwner         OwnerType = "User"
	OrganizationOwner OwnerType = "Organization"
)

type OwnerPage struct {
	Repositories []Repository
	HasMore      bool
}

// SearchRepositories accepts only structured selectors. Callers must retain the
// incomplete/limit signals and split the fixed window instead of marking it done.
func (c *Client) SearchRepositories(ctx context.Context, kind, value string,
	since *time.Time, until time.Time, page int) (SearchPage, Quota, error) {
	if page < 1 || page > 10 || until.IsZero() || (since != nil && since.After(until)) {
		return SearchPage{}, Quota{}, errors.New("invalid search page or window")
	}
	selector, err := searchSelector(kind, value)
	if err != nil {
		return SearchPage{}, Quota{}, err
	}
	windowEnd := until.UTC().Format("2006-01-02T15:04:05+00:00")
	window := "pushed:*.." + windowEnd
	if since != nil {
		window = "pushed:" + since.UTC().Format("2006-01-02T15:04:05+00:00") + ".." + windowEnd
	}
	return c.searchRepositoryPage(ctx, selector+" is:public "+window, page)
}

// searchRepositoryPage preserves GitHub's coverage signals for both custom and
// official discovery. Its callers compile and validate the query before use.
func (c *Client) searchRepositoryPage(ctx context.Context, compiled string, page int) (SearchPage, Quota, error) {
	query := url.Values{}
	query.Set("q", compiled)
	query.Set("sort", "updated")
	query.Set("order", "desc")
	query.Set("per_page", strconv.Itoa(repositoryPageSize))
	query.Set("page", strconv.Itoa(page))
	var response struct {
		TotalCount        *int         `json:"total_count"`
		IncompleteResults *bool        `json:"incomplete_results"`
		Items             []Repository `json:"items"`
	}
	quota, err := c.get(ctx, "/search/repositories?"+query.Encode(), &response)
	if err != nil {
		return SearchPage{}, quota, err
	}
	if response.TotalCount == nil || response.IncompleteResults == nil || *response.TotalCount < 0 {
		return SearchPage{}, quota, errors.New("GitHub search returned incomplete metadata")
	}
	accessible := min(*response.TotalCount, 1000)
	return SearchPage{
		Repositories:      publicRepositories(response.Items),
		TotalCount:        *response.TotalCount,
		IncompleteResults: *response.IncompleteResults,
		LimitReached:      *response.TotalCount > 1000,
		HasMore:           page*repositoryPageSize < accessible,
	}, quota, nil
}

func (c *Client) GetOwnerType(ctx context.Context, owner string) (OwnerType, Quota, error) {
	if !ownerPattern.MatchString(owner) {
		return "", Quota{}, errors.New("invalid GitHub owner")
	}
	var response struct {
		Type string `json:"type"`
	}
	quota, err := c.get(ctx, "/users/"+owner, &response)
	if err != nil {
		return "", quota, err
	}
	ownerType := OwnerType(response.Type)
	if ownerType != UserOwner && ownerType != OrganizationOwner {
		return "", quota, errors.New("GitHub owner is not a user or organization")
	}
	return ownerType, quota, nil
}

// OwnerIdentity is resolved through GitHub's durable account ID endpoint.
type OwnerIdentity struct {
	ID    int64     `json:"id"`
	Login string    `json:"login"`
	Type  OwnerType `json:"type"`
}

func (c *Client) GetOwnerByID(ctx context.Context, id int64) (OwnerIdentity, Quota, error) {
	if id < 1 {
		return OwnerIdentity{}, Quota{}, errors.New("invalid GitHub owner ID")
	}
	var owner OwnerIdentity
	quota, err := c.get(ctx, "/user/"+strconv.FormatInt(id, 10), &owner)
	if err != nil {
		return OwnerIdentity{}, quota, err
	}
	if owner.ID != id || !ownerPattern.MatchString(owner.Login) || (owner.Type != UserOwner && owner.Type != OrganizationOwner) {
		return OwnerIdentity{}, quota, errors.New("GitHub returned inconsistent owner identity")
	}
	return owner, quota, nil
}

// ListPublicOwnerRepositories uses the public-only endpoint for the account type
// resolved by GetOwnerType. Candidate budgeting and page checkpoints belong to the worker.
func (c *Client) ListPublicOwnerRepositories(ctx context.Context, owner string,
	ownerType OwnerType, page int) (OwnerPage, Quota, error) {
	if !ownerPattern.MatchString(owner) || page < 1 ||
		(ownerType != UserOwner && ownerType != OrganizationOwner) {
		return OwnerPage{}, Quota{}, errors.New("invalid GitHub owner or page")
	}
	path := "/users/" + owner + "/repos"
	query := url.Values{}
	if ownerType == OrganizationOwner {
		path = "/orgs/" + owner + "/repos"
		query.Set("type", "public")
	} else {
		query.Set("type", "owner")
	}
	query.Set("sort", "full_name")
	query.Set("direction", "asc")
	query.Set("per_page", strconv.Itoa(repositoryPageSize))
	query.Set("page", strconv.Itoa(page))
	var repositories []Repository
	quota, err := c.get(ctx, path+"?"+query.Encode(), &repositories)
	if err != nil {
		return OwnerPage{}, quota, err
	}
	return OwnerPage{
		Repositories: publicRepositories(repositories),
		HasMore:      len(repositories) == repositoryPageSize,
	}, quota, nil
}

func searchSelector(kind, value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" || utf8.RuneCountInString(value) > 100 {
		return "", errors.New("invalid search selector")
	}
	switch kind {
	case "KEYWORD":
		for _, char := range value {
			if !unicode.IsLetter(char) && !unicode.IsDigit(char) && char != ' ' &&
				char != '-' && char != '_' {
				return "", errors.New("keyword contains unsupported search syntax")
			}
		}
		return `"` + value + `" in:name,description,topics`, nil
	case "TOPIC":
		if !topicPattern.MatchString(value) {
			return "", errors.New("invalid GitHub topic selector")
		}
		return "topic:" + strings.ToLower(value), nil
	default:
		return "", errors.New("unsupported search selector kind")
	}
}

func publicRepositories(repositories []Repository) []Repository {
	public := make([]Repository, 0, len(repositories))
	for _, repository := range repositories {
		if !repository.Private && repository.ID > 0 &&
			validateRepository(repository.Owner.Login, repository.Name) == nil {
			public = append(public, repository)
		}
	}
	return public
}
