package com.iflytek.skillhub.domain.namespace;

import java.util.Optional;

public interface NamespaceInvitationRepository {
    Optional<NamespaceInvitation> findByNamespaceId(Long namespaceId);
    Optional<NamespaceInvitation> findByTokenHash(String tokenHash);
    NamespaceInvitation save(NamespaceInvitation invitation);
    void deleteByNamespaceId(Long namespaceId);
}
