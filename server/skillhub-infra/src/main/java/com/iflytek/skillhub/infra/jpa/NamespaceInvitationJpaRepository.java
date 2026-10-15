package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.NamespaceInvitation;
import com.iflytek.skillhub.domain.namespace.NamespaceInvitationRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NamespaceInvitationJpaRepository extends JpaRepository<NamespaceInvitation, Long>, NamespaceInvitationRepository {
    Optional<NamespaceInvitation> findByTokenHash(String tokenHash);
    @Query("select invitation.namespaceId from NamespaceInvitation invitation where invitation.tokenHash = :tokenHash")
    Optional<Long> findNamespaceIdByTokenHash(@Param("tokenHash") String tokenHash);
    void deleteByNamespaceId(Long namespaceId);
}
