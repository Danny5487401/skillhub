package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.GithubDeepDiscoveryReadResponse.*;
import com.iflytek.skillhub.dto.GithubOfficialWorkerAvailabilityResponse;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfiguration;
import com.iflytek.skillhub.githubsync.GithubDeepDiscoveryConfigurationRepository;
import com.iflytek.skillhub.repository.GithubDeepDiscoveryQueryRepository;
import com.iflytek.skillhub.repository.GithubSyncWorkerPresenceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GithubDeepDiscoveryReadAppService {
    private final GithubDeepDiscoveryQueryRepository queries;
    private final GithubDeepDiscoveryConfigurationRepository configurations;
    private final GithubSyncWorkerPresenceRepository workers;
    public GithubDeepDiscoveryReadAppService(GithubDeepDiscoveryQueryRepository queries,
            GithubDeepDiscoveryConfigurationRepository configurations, GithubSyncWorkerPresenceRepository workers) {
        this.queries=queries;this.configurations=configurations;this.workers=workers;
    }
    public Summary summary(Instant from,Instant until) {
        Instant now=Instant.now();
        if(from==null || until==null || !from.isBefore(until) || until.isAfter(now)
                || Duration.between(from,until).compareTo(Duration.ofDays(90))>0) invalid();
        var config=configurations.get();var worker=workers.officialStatus();
        var channels=queries.channelStates(config.namespaceId());
        var waits=waits(config,null,null);
        boolean running=channels.stream().anyMatch(c->c.running() && queries.compatibleWorker(c.channel()));
        String state=!config.enabled()?"DISABLED":running?"RUNNING"
                :!waits.isEmpty() || channels.stream().anyMatch(c->c.pending()>0 || c.gaps()>0)?"WAITING":"IDLE";
        return new Summary(config.version(),config.strategyVersion(),state,now,worker,
                queries.recent(config.namespaceId(),from,until),queries.current(config.namespaceId(),now),
                waits,queries.recentRuns(config.namespaceId()));
    }
    public Progress progress() {
        var config=configurations.get();var worker=workers.officialStatus();
        var states=queries.channelStates(config.namespaceId());
        var channels=new ArrayList<ChannelProgress>();
        for(Channel channel:Channel.values()) {
            var row=states.stream().filter(c->c.channel()==channel).findFirst().orElse(null);
            var waits=waits(config,channel,null);
            String state=!config.enabled()?"DISABLED":row!=null && row.running() && queries.compatibleWorker(channel)?"RUNNING"
                    :!waits.isEmpty() || row!=null && (row.pending()>0 || row.gaps()>0)?"WAITING":"IDLE";
            channels.add(new ChannelProgress(channel,state,row==null?0:row.completed(),row==null?0:row.pending(),
                    row==null?0:row.gaps(),row==null?null:row.progress(),waits));
        }
        var quotas=queries.quotas();
        return new Progress(config.strategyVersion(),Instant.now(),channels,
                List.copyOf(quotas.subList(0,Math.min(100,quotas.size()))),quotas.size()>100);
    }
    /** Merge bounded reasons from current controls and durable task evidence, never raw errors. */
    private List<Wait> waits(GithubDeepDiscoveryConfiguration config, Channel channel, RepositorySummary repository) {
        long namespace=config.namespaceId();
        Long repositoryId=repository==null?null:repository.repositoryId();
        boolean detail=repository!=null;
        boolean source=detail && repository.sourceId()!=null;
        boolean contactsOnline=workers.status().online();
        var evidence=new ArrayList<Wait>();
        List<String> repositoryWork=source?List.of("SOURCE_UPDATE","PACKAGE_ACQUISITION")
                :List.of("CANDIDATE_VALIDATION","PACKAGE_ACQUISITION");
        if(!contactsOnline) {
            add(evidence,"WORKER_UNAVAILABLE",detail?repositoryWork:List.of("NEW_DISCOVERY","CANDIDATE_VALIDATION","PACKAGE_ACQUISITION","SOURCE_UPDATE"),null,
                    "等待采集服务恢复联系；到达重试时间不表示任务已执行。","CHECK_WORKER");
        } else if(detail) {
            if(queries.repositoryNeedsOfficialWorker(namespace,repositoryId) && !queries.pauseCapableWorker())
                add(evidence,"CAPABILITY_UNAVAILABLE",repositoryWork,null,
                        "等待支持官方候选暂停协议的实例恢复联系。","CHECK_WORKER");
        } else {
            boolean compatible=channel==null
                    ? queries.compatibleWorker(Channel.REPOSITORY_METADATA) && queries.compatibleWorker(Channel.OWNER_EXPANSION)
                    : queries.compatibleWorker(channel);
            if(!compatible) add(evidence,"CAPABILITY_UNAVAILABLE",List.of("NEW_DISCOVERY"),null,
                    "至少一个探索渠道没有兼容实例；不代表其他渠道或普通来源实例已离线。","CHECK_WORKER");
            if(channel==null && queries.hasOfficialOnlyCandidates(namespace) && !queries.pauseCapableWorker())
                add(evidence,"CAPABILITY_UNAVAILABLE",List.of("CANDIDATE_VALIDATION","PACKAGE_ACQUISITION"),null,
                        "等待支持官方候选暂停协议的实例恢复联系。","CHECK_WORKER");
        }
        if (detail && queries.credentialPoolUnavailable("CORE")) {
            add(evidence,"CREDENTIAL_UNAVAILABLE",repositoryWork,null,
                    "当前没有可用于仓库读取的已配置凭证；检查凭证启用状态、有效性和资源额度，恢复后自动领取。","MANAGE_CREDENTIALS");
        } else if (!detail) {
            boolean searchNeeded=channel==null || channel!=Channel.OWNER_EXPANSION;
            boolean coreNeeded=channel==null || channel==Channel.OWNER_EXPANSION;
            var affected=new java.util.LinkedHashSet<String>();
            if(searchNeeded && queries.credentialPoolUnavailable("SEARCH")) affected.add("NEW_DISCOVERY");
            if(coreNeeded && queries.credentialPoolUnavailable("CORE")) {
                affected.add("NEW_DISCOVERY");
                if(channel==null) affected.addAll(List.of("CANDIDATE_VALIDATION","PACKAGE_ACQUISITION","SOURCE_UPDATE"));
            }
            if(!affected.isEmpty()) add(evidence,"CREDENTIAL_UNAVAILABLE",List.copyOf(affected),null,
                    "对应资源没有可用的已配置凭证；检查凭证启用状态、有效性和额度，恢复后自动领取。此状态不等于确定限流。","MANAGE_CREDENTIALS");
        }
        if(!detail) {
            var budgetWait=queries.discoveryBudgetWait();
            if(config.enabled() && budgetWait!=null && budgetWait.blocked()) add(evidence,"BUDGET",List.of("NEW_DISCOVERY"),budgetWait.retryAt(),
                    "新官方租约须同时等待预算时间到达和在途官方租约完成或过期；到时不保证立即执行。已有租约继续，普通来源不受此预算影响。","EDIT_CONFIGURATION");
            if(!config.enabled()) add(evidence,"DISABLED",List.of("NEW_DISCOVERY"),null,
                    "管理员启用探索后保留进度续跑；来源仍使用各自开关。","EDIT_CONFIGURATION");
            if(queries.candidateCapacityBlocked(namespace)) add(evidence,"CANDIDATE_CAPACITY",List.of("NEW_DISCOVERY"),null,
                    "候选处理释放容量后恢复接收新仓库；已存在候选仍可处理。","MANAGE_ADMISSION");
            for(var row:queries.discoveryWaits(namespace,channel)) addTaskEvidence(evidence,row);
        }
        if(detail || channel==null) {
            for(var row:queries.repositoryWaits(namespace,repositoryId)) addTaskEvidence(evidence,row);
        }
        if(queries.admissionBlocked(namespace)) {
            var affected=detail && !source?List.of("CANDIDATE_VALIDATION","PACKAGE_ACQUISITION"):List.of("PACKAGE_ACQUISITION");
            add(evidence,"REVIEW_BACKPRESSURE",affected,null,
                    "水位配置有效且未解决项低于恢复线后允许新包；已准入包可结算，来源轻量检查不因此视为停止。","MANAGE_ADMISSION");
        }
        for(var quota:queries.quotaWaits()) {
            var affected=new java.util.LinkedHashSet<String>();
            boolean allResources=quota.resource()==null;
            if(detail) {
                if(allResources || "CORE".equals(quota.resource())) affected.addAll(repositoryWork);
            } else {
                if(allResources || channel==null && ("SEARCH".equals(quota.resource()) || "CORE".equals(quota.resource()))
                        || channel==Channel.OWNER_EXPANSION && "CORE".equals(quota.resource())
                        || channel!=null && channel!=Channel.OWNER_EXPANSION && "SEARCH".equals(quota.resource()))
                    affected.add("NEW_DISCOVERY");
                if(channel==null && (allResources || "CORE".equals(quota.resource())))
                    affected.addAll(List.of("CANDIDATE_VALIDATION","PACKAGE_ACQUISITION","SOURCE_UPDATE"));
            }
            if(!affected.isEmpty()) add(evidence,quota.reason(),List.copyOf(affected),quota.retryAt(),
                    "共享保护期间暂停对应资源的新任务领取和新包准入；已准入包可结算。旧协议在途任务未必逐请求停止，保护原因不等于确证次级限流。","MANAGE_CREDENTIALS");
        }
        var observedContinuing=contactsOnline?queries.progressingWork(namespace,repositoryId,channel):List.<String>of();
        return mergeWaits(evidence,observedContinuing);
    }
    private static void addTaskEvidence(List<Wait> target,GithubDeepDiscoveryQueryRepository.WaitEvidence evidence) {
        String raw=evidence.reason();
        String reason=waitReason(raw);
        if(reason==null) return;
        // Pool state is re-evaluated above, so an old task label cannot outlive credential recovery.
        if("CREDENTIAL_UNAVAILABLE".equals(reason)) return;
        if(List.of("PRIMARY_RATE_LIMIT","SECONDARY_COOLDOWN","QUOTA_COOLDOWN").contains(reason)
                && evidence.retryAt()!=null && !evidence.retryAt().isAfter(Instant.now())) return;
        // Current eligibility supplies these controls; stale task pause labels must not override a resumed source.
        String recovery="满足持久任务的恢复条件后自动领取；时间仅是最早可领取，不保证执行。";
        String action=switch(reason) {
            case "CREDENTIAL_UNAVAILABLE","PRIMARY_RATE_LIMIT","SECONDARY_COOLDOWN","QUOTA_COOLDOWN" -> "MANAGE_CREDENTIALS";
            case "REVIEW_BACKPRESSURE","CANDIDATE_CAPACITY" -> "MANAGE_ADMISSION";
            case "EXCLUDED","DISABLED" -> "EDIT_CONFIGURATION";
            default -> null;
        };
        if("SOURCE_PAUSED".equals(raw)) {
            recovery="由来源管理员恢复该仓库来源；探索开关和其他命中入口不会覆盖来源自身暂停。";
            action=null;
        } else if("EXCLUDED".equals(raw)) {
            recovery="移除当前命中的排除项后按保留进度继续；已登记来源与独立入口依据自身资格运行。";
        } else if("DISABLED".equals(raw)) {
            recovery="官方独占候选随探索启停；重开后按保留进度续跑。";
        }
        List<String> affected=List.of(evidence.work());
        if("CANDIDATE_VALIDATION".equals(evidence.work()) || "SOURCE_PAUSED".equals(raw))
            affected=List.of(evidence.work(),"PACKAGE_ACQUISITION");
        add(target,reason,affected,evidence.retryAt(),recovery,action);
    }
    private static void add(List<Wait> target,String reason,List<String> affected,Instant retry,String recovery,String action) {
        target.add(new Wait(reason,affected,List.of(),retry,recovery,action));
    }
    private static List<Wait> mergeWaits(List<Wait> evidence,List<String> observedContinuing) {
        var reasons=new java.util.LinkedHashMap<String,List<Wait>>();
        evidence.forEach(wait->reasons.computeIfAbsent(wait.reason(),ignored->new ArrayList<>()).add(wait));
        var blocked=evidence.stream().filter(wait->!"BUDGET".equals(wait.reason())).flatMap(wait->wait.affectedWork().stream()).collect(java.util.stream.Collectors.toSet());
        var continuing=observedContinuing.stream().filter(work->!blocked.contains(work)).distinct().sorted().toList();
        var result=new ArrayList<Wait>();
        for(var entry:reasons.entrySet()) {
            var values=entry.getValue();
            var affected=values.stream().flatMap(wait->wait.affectedWork().stream()).distinct().sorted().toList();
            Instant retry=values.stream().anyMatch(wait->wait.retryAt()==null)?null:
                    values.stream().map(Wait::retryAt).max(Instant::compareTo).orElse(null);
            String recovery=values.stream().map(Wait::recoveryCondition).distinct().collect(java.util.stream.Collectors.joining(" "));
            if(recovery.length()>500) recovery=recovery.substring(0,500);
            String action=values.stream().map(Wait::action).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            result.add(new Wait(entry.getKey(),affected,continuing,retry,recovery,action));
        }
        if(result.size()>32) throw new IllegalStateException("Discovery wait reason catalog exceeds response bound");
        return List.copyOf(result);
    }
    private static String waitReason(String reason) {
        if(reason==null) return null;
        return switch(reason) {
            case "DISABLED","EXCLUDED","BUDGET","PRIMARY_RATE_LIMIT","SECONDARY_COOLDOWN","QUOTA_COOLDOWN",
                "CREDENTIAL_UNAVAILABLE","CANDIDATE_CAPACITY","REVIEW_BACKPRESSURE","COVERAGE_GAP" -> reason;
            case "SOURCE_PAUSED" -> "DISABLED";
            case "GITHUB_RATE_LIMITED" -> "PRIMARY_RATE_LIMIT";
            case "BACKPRESSURE_ACTIVE" -> "REVIEW_BACKPRESSURE";
            case "CANDIDATE_BUDGET_EXCEEDED" -> "BUDGET";
            default -> null;
        };
    }
    public DiscoveryPage<RepositorySummary> repositories(int page,int size,Instant asOf,String q,RepositoryStatus status,Channel channel,Boolean paused) {
        Instant bound=page(page,size,asOf);if(q!=null && q.length()>200) invalid();
        return queries.repositories(configurations.get().namespaceId(),page,size,bound,q,status,channel,paused);
    }
    public RepositoryDetail repository(long id) {
        identity(id);
        var config=configurations.get();
        var detail=queries.detail(config.namespaceId(),id);
        var waits=waits(config,null,detail.repository());
        String reason=detail.notImportedReason();
        if("NOT_CHECKED".equals(reason) && waits.stream().anyMatch(w->
                "REVIEW_BACKPRESSURE".equals(w.reason()))) reason="WAITING_CAPACITY";
        return new RepositoryDetail(detail.repository(),detail.lastCheckedAt(),detail.nextEligibleAt(),reason,
                waits,detail.latestRunId(),detail.historyAvailableSince());
    }
    public DiscoveryPage<Provenance> provenance(long id,int page,int size,Instant asOf) {
        identity(id);Instant bound=page(page,size,asOf);return queries.provenance(configurations.get().namespaceId(),id,page,size,bound);
    }
    public DiscoveryPage<PathResult> paths(long id,int page,int size,Instant asOf) {
        identity(id);Instant bound=page(page,size,asOf);return queries.paths(configurations.get().namespaceId(),id,page,size,bound);
    }
    private static Instant page(int page,int size,Instant asOf) {
        Instant now=Instant.now();if(page<0 || page>1000 || size<1 || size>100 || (long)page*size>100000 || asOf!=null && asOf.isAfter(now)) invalid();
        return asOf==null?now:asOf;
    }
    private static void identity(long id) { if(id<1) invalid(); }
    private static void invalid() { throw new DomainBadRequestException("error.githubSync.deepDiscovery.queryInvalid"); }
}
