package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.settings.SystemSetting;
import com.iflytek.skillhub.auth.settings.SystemSettingRepository;
import com.iflytek.skillhub.domain.audit.AuditDetail;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.dto.AvatarReviewSettingsResponse;
import com.iflytek.skillhub.dto.AvatarReviewSettingsUpdateRequest;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvatarReviewSettingsService {
    public static final String SETTING_KEY = "skill.avatar-review";

    private final SystemSettingRepository settings;
    private final AvatarModerationGateway gateway;
    private final AuditLogService audit;
    private final RequestIdAccessor requestIds;

    public AvatarReviewSettingsService(SystemSettingRepository settings, AvatarModerationGateway gateway,
                                       AuditLogService audit, RequestIdAccessor requestIds) {
        this.settings = settings;
        this.gateway = gateway;
        this.audit = audit;
        this.requestIds = requestIds;
    }

    public AvatarReviewSettingsResponse current() {
        SystemSetting setting = load();
        return new AvatarReviewSettingsResponse(
                Boolean.TRUE.equals(setting.getValue().get("machineEnabled")),
                !Boolean.FALSE.equals(setting.getValue().get("manualEnabled")),
                setting.getVersion(), setting.getUpdatedAt());
    }

    @Transactional
    public AvatarReviewSettingsResponse update(AvatarReviewSettingsUpdateRequest request, String actor,
                                               AuditRequestContext context) {
        if (request.machineEnabled()) {
            gateway.requireConfiguredProvider();
        }
        SystemSetting setting = load();
        if (setting.getVersion() != request.version()) {
            throw new DomainConflictException("error.system.settings.version.conflict");
        }
        Map<String, Object> previous = setting.getValue();
        setting.update(Map.of("machineEnabled", request.machineEnabled(),
                "manualEnabled", request.manualEnabled()), actor);
        settings.saveAndFlush(setting);
        audit.record(actor, "SKILL_AVATAR_SETTINGS_UPDATE", "SYSTEM_SETTING", setting.getId(),
                requestIds.current(), context.clientIp(), context.userAgent(),
                AuditDetail.builder().put("previous", previous).put("current", setting.getValue()).build());
        return current();
    }

    private SystemSetting load() {
        return settings.findBySettingKey(SETTING_KEY)
                .orElseThrow(() -> new IllegalStateException("Missing required skill.avatar-review setting"));
    }
}
