package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.NamespaceInvitationHistory;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationHistoryRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NamespaceInvitationHistoryJpaRepository extends JpaRepository<NamespaceInvitationHistory, String>, NamespaceInvitationHistoryRepository {
    @Override
    java.util.Optional<NamespaceInvitationHistory> findById(String id);
}
