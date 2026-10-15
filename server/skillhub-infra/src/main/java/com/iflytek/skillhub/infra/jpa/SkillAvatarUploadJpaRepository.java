package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillAvatarUpload;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillAvatarUploadJpaRepository extends JpaRepository<SkillAvatarUpload, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM SkillAvatarUpload u WHERE u.id = :id")
    Optional<SkillAvatarUpload> findLockedById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM SkillAvatarUpload u WHERE u.candidateId IS NULL AND u.objectKey IS NOT NULL AND u.expiresAt <= :now ORDER BY u.expiresAt")
    List<SkillAvatarUpload> findExpired(@Param("now") Instant now, Pageable page);
}
