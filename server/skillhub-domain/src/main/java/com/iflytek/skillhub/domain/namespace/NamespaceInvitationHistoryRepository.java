package com.iflytek.skillhub.domain.namespace;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NamespaceInvitationHistoryRepository {
    NamespaceInvitationHistory save(NamespaceInvitationHistory history);
    Optional<NamespaceInvitationHistory> findById(String id);
    Page<NamespaceInvitationHistory> findByNamespaceId(Long namespaceId, Pageable pageable);
}
