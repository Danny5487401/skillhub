package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AvatarModerationGateway {
    private final String selectedProvider;
    private final List<AvatarModerationProvider> providers;

    public AvatarModerationGateway(@Value("${skillhub.avatar.moderation.provider:}") String selectedProvider,
                                   List<AvatarModerationProvider> providers) {
        this.selectedProvider = selectedProvider;
        this.providers = providers;
    }

    public String requireConfiguredProvider() {
        return providers.stream()
                .filter(provider -> provider.code().equals(selectedProvider) && provider.configured())
                .findFirst()
                .map(AvatarModerationProvider::code)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.avatar.providerUnavailable"));
    }

    public AvatarModerationProvider.Context context(String providerCode) {
        return providers.stream()
                .filter(provider -> provider.code().equals(providerCode) && providerCode.equals(selectedProvider)
                        && provider.configured())
                .findFirst().map(AvatarModerationProvider::context)
                .orElse(AvatarModerationProvider.Context.UNKNOWN);
    }

    public AvatarModerationProvider.Verdict moderate(String providerCode, byte[] image) {
        if (!providerCode.equals(selectedProvider)) {
            return AvatarModerationProvider.Verdict.ERROR;
        }
        return providers.stream()
                .filter(provider -> provider.code().equals(providerCode) && provider.configured())
                .findFirst()
                .map(provider -> provider.moderate(image))
                .orElse(AvatarModerationProvider.Verdict.ERROR);
    }
}
