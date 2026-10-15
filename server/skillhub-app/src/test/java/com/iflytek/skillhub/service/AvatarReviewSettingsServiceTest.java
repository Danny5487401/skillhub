package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.auth.settings.SystemSetting;
import com.iflytek.skillhub.auth.settings.SystemSettingRepository;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainConflictException;
import com.iflytek.skillhub.dto.AvatarReviewSettingsUpdateRequest;
import com.iflytek.skillhub.observability.RequestIdAccessor;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AvatarReviewSettingsServiceTest {
    @Mock private SystemSettingRepository repository;
    @Mock private AvatarModerationGateway gateway;
    @Mock private AuditLogService audit;
    @Mock private RequestIdAccessor requestIds;
    private AvatarReviewSettingsService service;
    private SystemSetting setting;

    @BeforeEach
    void setup() {
        service = new AvatarReviewSettingsService(repository, gateway, audit, requestIds);
        setting = new SystemSetting(AvatarReviewSettingsService.SETTING_KEY,
                Map.of("machineEnabled", false, "manualEnabled", true));
    }

    @ParameterizedTest
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void updatesAllFourPolicies(boolean machine, boolean manual) {
        when(repository.findBySettingKey(AvatarReviewSettingsService.SETTING_KEY)).thenReturn(Optional.of(setting));
        if (machine) when(gateway.requireConfiguredProvider()).thenReturn("xfyun");
        var updated = service.update(new AvatarReviewSettingsUpdateRequest(machine, manual, 0), "admin",
                new AuditRequestContext(null, null));
        assertThat(updated.machineEnabled()).isEqualTo(machine);
        assertThat(updated.manualEnabled()).isEqualTo(manual);
        verify(repository).saveAndFlush(setting);
    }

    @Test
    void cannotEnableMachineReviewWithoutProvider() {
        when(gateway.requireConfiguredProvider()).thenThrow(new DomainBadRequestException("providerUnavailable"));
        assertThatThrownBy(() -> service.update(new AvatarReviewSettingsUpdateRequest(true, false, 0), "admin",
                new AuditRequestContext(null, null))).isInstanceOf(DomainBadRequestException.class);
        verify(repository, never()).saveAndFlush(setting);
    }

    @Test
    void staleVersionDoesNotOverwriteSettings() {
        when(repository.findBySettingKey(AvatarReviewSettingsService.SETTING_KEY)).thenReturn(Optional.of(setting));
        assertThatThrownBy(() -> service.update(new AvatarReviewSettingsUpdateRequest(false, false, 1), "admin",
                new AuditRequestContext(null, null))).isInstanceOf(DomainConflictException.class);
        verify(repository, never()).saveAndFlush(setting);
    }
}
