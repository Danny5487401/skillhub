package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.NamespaceInvitation;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NamespaceInvitationJpaRepository extends JpaRepository<NamespaceInvitation, Long>, NamespaceInvitationRepository {
    Optional<NamespaceInvitation> findByTokenHash(String tokenHash);
    void deleteByNamespaceId(Long namespaceId);
}
