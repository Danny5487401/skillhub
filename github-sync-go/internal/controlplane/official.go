package controlplane

import (
	"errors"
	"slices"
	"time"
	"unicode/utf8"
)

const (
	OfficialDiscoveryCapability  = "official-discovery/v1"
	OfficialCheckpointCapability = "official-checkpoint/v1"
	OfficialPauseCapability      = "official-pause/v1"
	OfficialOwnerCapability      = "official-owner/v1"
)

// OfficialDiscoverySnapshot is an immutable Java-owned policy and shard snapshot.
// Decoding it does not imply that this worker implements the official executor.
type OfficialDiscoverySnapshot struct {
	FormatVersion        int                `json:"formatVersion"`
	ConfigurationVersion int64              `json:"configurationVersion"`
	StrategyVersion      string             `json:"strategyVersion"`
	QueryID              string             `json:"queryId"`
	QueryVersion         int64              `json:"queryVersion"`
	ShardID              string             `json:"shardId"`
	Channel              string             `json:"channel"`
	Mode                 string             `json:"mode"`
	Query                OfficialQuery      `json:"query"`
	Range                OfficialQueryRange `json:"range"`
	FixedCutoffAt        time.Time          `json:"fixedCutoffAt"`
	SeedRepositoryID     int64              `json:"seedRepositoryId,omitempty"`
	OwnerID              int64              `json:"ownerId,omitempty"`
	OwnerLogin           string             `json:"ownerLogin,omitempty"`
	ExpansionDepth       int                `json:"expansionDepth,omitempty"`
	Checkpoint           string             `json:"checkpoint,omitempty"`
	Budget               OfficialBudget     `json:"budget"`
}

type OfficialQuery struct {
	Kind  string `json:"kind"`
	Value string `json:"value"`
	Scope string `json:"scope"`
}

type OfficialQueryRange struct {
	Since     time.Time `json:"since"`
	Until     time.Time `json:"until"`
	Partition string    `json:"partition,omitempty"`
}

type OfficialBudget struct {
	Profile         string `json:"profile"`
	MaxRequests     int64  `json:"maxRequests"`
	MaxPages        int64  `json:"maxPages"`
	CandidateBudget int64  `json:"candidateBudget"`
	MaxSplitDepth   int64  `json:"maxSplitDepth"`
	MaxChildShards  int64  `json:"maxChildShards"`
}

type OfficialCheckpoint struct {
	FormatVersion   int    `json:"formatVersion"`
	StrategyVersion string `json:"strategyVersion"`
	QueryID         string `json:"queryId"`
	QueryVersion    int64  `json:"queryVersion"`
	ShardID         string `json:"shardId"`
	NextPage        int64  `json:"nextPage"`
	Cursor          string `json:"cursor,omitempty"`
	Coverage        string `json:"coverage"`
}

type OfficialProvenance struct {
	Channel          string `json:"channel"`
	QueryID          string `json:"queryId"`
	QueryVersion     int64  `json:"queryVersion"`
	StrategyVersion  string `json:"strategyVersion"`
	ShardID          string `json:"shardId"`
	SeedRepositoryID int64  `json:"seedRepositoryId,omitempty"`
}

type OfficialDiscoveryBatch struct {
	OwnerLogin   string               `json:"ownerLogin,omitempty"`
	ShardID      string               `json:"shardId"`
	Coverage     string               `json:"coverage"`
	RequestsUsed int64                `json:"requestsUsed"`
	ChildRanges  []OfficialQueryRange `json:"childRanges,omitempty"`
	GapReason    string               `json:"gapReason,omitempty"`
}

type OfficialTaskControl struct {
	Action               string     `json:"action"`
	ConfigurationVersion int64      `json:"configurationVersion"`
	Reason               string     `json:"reason,omitempty"`
	RetryAt              *time.Time `json:"retryAt,omitempty"`
}

// Null commit/checkpoint values must stay explicit on the wire. No commit is
// required when pausing before the first repository read has completed.
type OfficialRepositoryProgress struct {
	ObservedCommitSHA  *string                `json:"observedCommitSha"`
	PathCheckpoint     *string                `json:"pathCheckpoint"`
	IncompleteSubtrees []string               `json:"incompleteSubtrees"`
	PathResults        []RepositoryPathResult `json:"pathResults"`
}

type OfficialCheckpointRecovery struct {
	Lease              LeaseProof `json:"lease"`
	Reason             string     `json:"reason"`
	RejectedCheckpoint string     `json:"rejectedCheckpoint"`
}

type OfficialQuotaObservation struct {
	Resource          string     `json:"resource"`
	ObservedAt        time.Time  `json:"observedAt"`
	State             string     `json:"state"`
	Remaining         *int       `json:"remaining,omitempty"`
	ResetAt           *time.Time `json:"resetAt,omitempty"`
	RetryAfterSeconds *int       `json:"retryAfterSeconds,omitempty"`
}

func validateCapabilities(capabilities []string) error {
	if len(capabilities) > 32 {
		return errors.New("too many worker capabilities")
	}
	for _, capability := range capabilities {
		if !boundedOfficialString(capability, 128) {
			return errors.New("invalid worker capability")
		}
	}
	return nil
}

func boundedOfficialString(value string, maximum int) bool {
	return value != "" && utf8.ValidString(value) && utf8.RuneCountInString(value) <= maximum
}

func validOfficialTime(value time.Time) bool {
	_, offset := value.Zone()
	return !value.IsZero() && value.Nanosecond() == 0 && offset == 0
}

func validOfficialRange(window OfficialQueryRange) bool {
	return validOfficialTime(window.Since) && validOfficialTime(window.Until) &&
		window.Since.Before(window.Until) && window.Partition == ""
}

func validateOfficialTask(task ClaimedTask, capabilities []string) error {
	if err := validateCapabilities(task.RequiredCapabilities); err != nil {
		return err
	}
	for _, required := range task.RequiredCapabilities {
		if !slices.Contains(capabilities, required) {
			return errors.New("internal API returned a task requiring an undeclared capability")
		}
	}
	if task.PlatformControlVersion != nil && (*task.PlatformControlVersion < 1 || !slices.Contains(task.RequiredCapabilities, PlatformPauseCapability)) {
		return errors.New("internal API returned invalid platform control capability")
	}
	if slices.Contains(task.RequiredCapabilities, PlatformPauseCapability) && task.PlatformControlVersion == nil {
		return errors.New("internal API omitted platform control version")
	}
	snapshot := task.OfficialDiscovery
	if snapshot == nil {
		if task.Kind == "DISCOVERY" {
			for _, required := range task.RequiredCapabilities {
				if required != PlatformPauseCapability {
					return errors.New("internal API returned capabilities without an official discovery snapshot")
				}
			}
		}
		return nil
	}
	if task.Kind != "DISCOVERY" || task.Discovery != nil || task.Repository != nil {
		return errors.New("internal API returned conflicting discovery snapshots")
	}
	for _, required := range []string{OfficialDiscoveryCapability, OfficialCheckpointCapability, OfficialPauseCapability} {
		if !slices.Contains(task.RequiredCapabilities, required) {
			return errors.New("internal API omitted a required official discovery capability")
		}
	}
	if snapshot.FormatVersion != 1 || snapshot.ConfigurationVersion < 1 || snapshot.QueryVersion < 1 ||
		!boundedOfficialString(snapshot.StrategyVersion, 128) || !boundedOfficialString(snapshot.QueryID, 128) ||
		snapshot.ShardID == "" || !boundedOfficialString(snapshot.Query.Value, 256) ||
		!validOfficialRange(snapshot.Range) || !validOfficialTime(snapshot.FixedCutoffAt) ||
		snapshot.Range.Until.After(snapshot.FixedCutoffAt) || len(snapshot.Checkpoint) > 4096 ||
		!utf8.ValidString(snapshot.Checkpoint) {
		return errors.New("internal API returned an invalid official discovery snapshot")
	}
	if !slices.Contains([]string{"HISTORICAL", "INCREMENTAL", "GAP_RECHECK", "OWNER_RECHECK"}, snapshot.Mode) {
		return errors.New("internal API returned an unsupported official discovery mode")
	}
	budget := snapshot.Budget
	if !slices.Contains([]string{"ECONOMY", "BALANCED", "ACCELERATED"}, budget.Profile) ||
		budget.MaxRequests < 1 || budget.MaxPages < 1 || budget.CandidateBudget < 1 ||
		budget.MaxSplitDepth < 0 || budget.MaxSplitDepth > 32 || budget.MaxChildShards < 0 || budget.MaxChildShards > 126 {
		return errors.New("internal API returned an invalid official discovery budget")
	}
	switch snapshot.Channel {
	case "REPOSITORY_METADATA":
		if snapshot.Query.Scope != "NAME_DESCRIPTION_TOPICS" ||
			(snapshot.Query.Kind != "KEYWORD" && snapshot.Query.Kind != "TOPIC") {
			return errors.New("invalid official metadata query")
		}
	case "README":
		if snapshot.Query.Scope != "README" || snapshot.Query.Kind != "KEYWORD" {
			return errors.New("invalid official README query")
		}
	case "OWNER_EXPANSION":
		if !slices.Contains(task.RequiredCapabilities, OfficialOwnerCapability) || snapshot.Mode != "OWNER_RECHECK" || budget.MaxRequests < 3 || budget.MaxSplitDepth != 0 || budget.MaxChildShards != 0 || snapshot.Range.Partition != "" || snapshot.Query.Scope != "OWNER_REPOSITORIES" || snapshot.Query.Kind != "OWNER" ||
			snapshot.SeedRepositoryID <= 0 || snapshot.OwnerID <= 0 ||
			!boundedOfficialString(snapshot.OwnerLogin, 39) || snapshot.ExpansionDepth != 1 ||
			snapshot.Query.Value != snapshot.OwnerLogin {
			return errors.New("invalid official owner expansion")
		}
	default:
		return errors.New("unsupported official discovery channel")
	}
	if snapshot.Channel != "OWNER_EXPANSION" &&
		(snapshot.SeedRepositoryID != 0 || snapshot.OwnerID != 0 || snapshot.OwnerLogin != "" || snapshot.ExpansionDepth != 0) {
		return errors.New("official search contains owner expansion metadata")
	}
	return nil
}

func validateOfficialBatch(batch DiscoveryBatch) error {
	official := batch.OfficialBatch
	for _, repository := range batch.Repositories {
		if len(repository.OfficialProvenance) > 16 ||
			(official == nil) != (len(repository.OfficialProvenance) == 0) {
			return errors.New("invalid official provenance count or batch")
		}
		for _, provenance := range repository.OfficialProvenance {
			if (official.OwnerLogin != "" && provenance.Channel != "OWNER_EXPANSION") || !boundedOfficialString(provenance.QueryID, 128) || provenance.QueryVersion < 1 ||
				!boundedOfficialString(provenance.StrategyVersion, 128) || provenance.ShardID != official.ShardID ||
				!slices.Contains([]string{"REPOSITORY_METADATA", "README", "OWNER_EXPANSION"}, provenance.Channel) ||
				(provenance.Channel == "OWNER_EXPANSION" && provenance.SeedRepositoryID < 1) ||
				(provenance.Channel != "OWNER_EXPANSION" && provenance.SeedRepositoryID != 0) {
				return errors.New("invalid official repository provenance")
			}
		}
	}
	if official == nil {
		return nil
	}
	if (official.OwnerLogin != "" && !boundedOfficialString(official.OwnerLogin, 39)) || official.ShardID == "" || official.RequestsUsed < 0 || len(official.ChildRanges) > 16 ||
		!slices.Contains([]string{"IN_PROGRESS", "COMPLETE", "SPLIT", "GAP"}, official.Coverage) ||
		(official.Coverage == "SPLIT") != (len(official.ChildRanges) > 0) ||
		(official.Coverage == "GAP") != (official.GapReason != "") {
		return errors.New("invalid official discovery batch")
	}
	if official.GapReason != "" && !slices.Contains([]string{"RESULT_LIMIT", "INCOMPLETE_RESULTS", "UNSPLITTABLE", "WORK_BUDGET"}, official.GapReason) {
		return errors.New("invalid official discovery gap reason")
	}
	for _, child := range official.ChildRanges {
		if !validOfficialRange(child) {
			return errors.New("invalid official child range")
		}
	}
	return nil
}
