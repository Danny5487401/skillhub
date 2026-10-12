package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed namespace repository that also fulfills the domain namespace repository contract.
 */
@Repository
public interface NamespaceJpaRepository
        extends JpaRepository<Namespace, Long>, NamespaceRepository {
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Namespace n where n.id = :id")
    Optional<Namespace> findLockedById(@Param("id") Long id);
    List<Namespace> findByIdIn(List<Long> ids);
    List<Namespace> findBySlugIn(List<String> slugs);
    Page<Namespace> findByIdIn(List<Long> ids, Pageable pageable);
    Optional<Namespace> findBySlug(String slug);
    @Override
    @Query("select n.id from Namespace n where n.slug = :slug")
    Optional<Long> findIdBySlug(@Param("slug") String slug);
    Page<Namespace> findByStatus(NamespaceStatus status, Pageable pageable);
}
