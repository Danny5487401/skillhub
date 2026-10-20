package com.iflytek.skillhub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.githubsync.GithubCredentialCipher;
import com.iflytek.skillhub.githubsync.GithubOfficialQuotaRepository;
import com.iflytek.skillhub.githubsync.GithubOfficialDiscoveryBudgetRepository;
import com.iflytek.skillhub.githubsync.GithubSyncLeaseService;
import com.iflytek.skillhub.githubsync.GithubSyncTaskKind;
import com.iflytek.skillhub.githubsync.GithubSystemCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class GithubCredentialLeaseAppService {
    private final GithubSyncLeaseService leases;
    private final GithubSystemCredentialRepository credentials;
    private final GithubCredentialCipher cipher;
    private final ObjectMapper mapper;
    private final GithubOfficialQuotaRepository officialQuota;
    private final GithubOfficialDiscoveryBudgetRepository officialBudget;

    public GithubCredentialLeaseAppService(GithubSyncLeaseService leases,
            GithubSystemCredentialRepository credentials, GithubCredentialCipher cipher,
            ObjectMapper mapper) {
        this(leases, credentials, cipher, mapper, null);
    }

    public GithubCredentialLeaseAppService(GithubSyncLeaseService leases,
            GithubSystemCredentialRepository credentials, GithubCredentialCipher cipher,
            ObjectMapper mapper, GithubOfficialQuotaRepository officialQuota) {
        this(leases, credentials, cipher, mapper, officialQuota, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GithubCredentialLeaseAppService(GithubSyncLeaseService leases,
            GithubSystemCredentialRepository credentials, GithubCredentialCipher cipher,
            ObjectMapper mapper, GithubOfficialQuotaRepository officialQuota,
            GithubOfficialDiscoveryBudgetRepository officialBudget) {
        this.officialBudget = officialBudget;
        this.leases = leases;
        this.credentials = credentials;
        this.cipher = cipher;
        this.mapper = mapper;
        this.officialQuota = officialQuota;
    }

    @Transactional
    public Optional<Claimed> claim(GithubSyncTaskKind kind, String workerId) {
        return claim(kind, workerId, List.of());
    }

    @Transactional
    public Optional<Claimed> claim(GithubSyncTaskKind kind, String workerId, List<String> capabilities) {
        var task = capabilities.isEmpty() ? leases.claim(kind, workerId) : leases.claim(kind, workerId, capabilities);
        return task.flatMap(leased -> {
            Instant now = Instant.now();
            String resource = quotaResource(leased.task().kind(), leased.task().snapshotJson());
            Instant cooldown = officialQuota == null ? null : officialQuota.cooldown(resource, now);
            if (cooldown != null) {
                officialQuota.deferClaim(leased.task().taskId(), leased.task().leaseGeneration(), cooldown, now);
                return Optional.empty();
            }
            var available = credentials.availableForClaim(now, resource);
            if (available.isEmpty()) {
                if (officialQuota != null && credentials.hasConfiguredCredentials()) {
                    officialQuota.deferClaim(leased.task().taskId(), leased.task().leaseGeneration(), now.plusSeconds(60), now, "CREDENTIAL_UNAVAILABLE");
                    return Optional.empty();
                }
                if (officialQuota != null) officialQuota.clearClaimWait(leased.task().taskId(), leased.task().leaseGeneration());
                reserve(leased);
                return Optional.of(new Claimed(leased, null));
            }
            var credential = available.get();
            String token = cipher.decrypt(credential.id(), credential.keyVersion(),
                    credential.nonce(), credential.ciphertext());
            if (!credentials.assignToLease(leased.task().taskId(),
                    leased.task().leaseGeneration(), credential.id(),
                    credential.fingerprint(), Instant.now())) {
                throw new IllegalStateException("Could not bind GitHub credential to current lease");
            }
            if (officialQuota != null) officialQuota.clearClaimWait(leased.task().taskId(), leased.task().leaseGeneration());
            reserve(leased);
            return Optional.of(new Claimed(leased, new LeasedCredential(credential.id(), token)));
        });
    }

    private void reserve(GithubSyncLeaseService.LeasedTask leased) {
        if (officialBudget != null && leased.task().kind() == GithubSyncTaskKind.DISCOVERY) {
            officialBudget.reserveSuccessfulClaim(leased.task().taskId(), leased.task().leaseGeneration());
        }
    }

    private String quotaResource(GithubSyncTaskKind kind, String snapshotJson) {
        if (kind == GithubSyncTaskKind.REPOSITORY_SYNC) {
            return "CORE";
        }
        try {
            var snapshot = mapper.readTree(snapshotJson);
            if (snapshot.has("officialDiscovery")) {
                return switch (snapshot.path("officialDiscovery").path("channel").asText()) {
                    case "REPOSITORY_METADATA", "README" -> "SEARCH";
                    case "OWNER_EXPANSION" -> "CORE";
                    default -> throw new IllegalStateException("Unsupported official discovery channel");
                };
            }
            String entryType = snapshot.path("entryType").asText();
            return switch (entryType) {
                case "SEARCH_RULE", "SEARCH_PREVIEW" -> "SEARCH";
                case "CURATED_OWNER", "CURATED_REPOSITORY" -> "CORE";
                default -> throw new IllegalStateException("Unsupported GitHub discovery entry type");
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid GitHub discovery task snapshot", exception);
        }
    }

    @Transactional
    public boolean reportStatus(UUID taskId, long generation, String leaseToken,
                                UUID credentialId, String status, Instant retryAt, Quota quota) {
        return reportStatus(taskId, generation, leaseToken, credentialId, status, retryAt, quota, null);
    }

    @Transactional
    public boolean reportStatus(UUID taskId, long generation, String leaseToken,
                                UUID credentialId, String status, Instant retryAt, Quota quota,
                                GithubOfficialQuotaRepository.Observation observation) {
        if (taskId == null || credentialId == null || generation < 1 || leaseToken == null
                || !leaseToken.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid GitHub credential lease proof");
        }
        Instant now = Instant.now();
        String tokenHash = hash(leaseToken);
        if (quota != null && (quota.resource() == null
                || !java.util.Set.of("CORE", "SEARCH", "GRAPHQL", "OTHER").contains(quota.resource())
                || quota.remaining() < 0 || quota.resetAt() == null)) {
            throw new IllegalArgumentException("Invalid GitHub quota observation");
        }
        if (observation != null) {
            if (officialQuota == null || status == null
                    || "OBSERVED".equals(status) && (quota == null || retryAt != null || !"OBSERVED".equals(observation.state()))
                    || "AUTH_INVALID".equals(status) && (retryAt != null || !"AUTH_INVALID".equals(observation.state()))
                    || "RATE_LIMITED".equals(status) && (retryAt == null || !retryAt.isAfter(now)
                        || !List.of("PRIMARY_EXHAUSTED", "SECONDARY_COOLDOWN").contains(observation.state()))
                    || !List.of("OBSERVED", "AUTH_INVALID", "RATE_LIMITED").contains(status)) {
                throw new IllegalArgumentException("Credential status and official quota observation disagree");
            }
            if (quota != null && (!quota.resource().equals(observation.resource())
                    || observation.remaining() != null && quota.remaining() != observation.remaining()
                    || observation.resetAt() != null && !quota.resetAt().equals(observation.resetAt()))) {
                throw new IllegalArgumentException("Credential quota values disagree");
            }
            if ("SECONDARY_COOLDOWN".equals(observation.state()) && observation.retryAfterSeconds() == null) {
                observation = new GithubOfficialQuotaRepository.Observation(observation.resource(), observation.observedAt(),
                        observation.state(), observation.remaining(), observation.resetAt(),
                        Math.toIntExact(java.time.Duration.between(now, retryAt).getSeconds() + 1));
            }
            return officialQuota.record(taskId, generation, tokenHash, credentialId, observation, now);
        }
        boolean accepted = switch (status) {
            case "OBSERVED" -> {
                if (retryAt != null || quota == null) {
                    throw new IllegalArgumentException("Quota observation requires quota without retry time");
                }
                yield credentials.recordQuotaForLease(taskId, generation, tokenHash, credentialId,
                        quota.resource(), quota.remaining(), quota.resetAt(), now);
            }
            case "AUTH_INVALID" -> {
                if (retryAt != null) {
                    throw new IllegalArgumentException("Invalid credential cannot have a retry time");
                }
                yield credentials.markInvalid(taskId, generation, tokenHash, credentialId, now);
            }
            case "RATE_LIMITED" -> {
                if (retryAt == null || !retryAt.isAfter(now)) {
                    throw new IllegalArgumentException("Rate limit requires a future retry time");
                }
                yield credentials.markRateLimited(taskId, generation, tokenHash,
                        credentialId, retryAt, now);
            }
            default -> throw new IllegalArgumentException("Unsupported GitHub credential status");
        };
        if (accepted && quota != null && !"OBSERVED".equals(status)) {
            credentials.recordQuota(credentialId, quota.resource(), quota.remaining(),
                    quota.resetAt(), now);
        }
        return accepted;
    }

    @Transactional
    public boolean reportOfficialQuota(UUID taskId, long generation, String token,
                                       GithubOfficialQuotaRepository.Observation observation) {
        if (taskId == null || generation < 1 || token == null || !token.matches("[0-9a-f]{64}") || officialQuota == null) {
            throw new IllegalArgumentException("Invalid official quota lease proof");
        }
        return officialQuota.record(taskId, generation, hash(token), null, observation, Instant.now());
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LeasedCredential(UUID credentialId, String token) {}
    public record Claimed(GithubSyncLeaseService.LeasedTask lease, LeasedCredential credential) {}
    public record Quota(String resource, int remaining, Instant resetAt) {}
}
