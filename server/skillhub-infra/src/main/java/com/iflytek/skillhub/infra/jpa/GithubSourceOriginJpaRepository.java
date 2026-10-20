package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSourceOrigin;
import com.iflytek.skillhub.githubsync.GithubSourceOriginRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GithubSourceOriginJpaRepository
        extends JpaRepository<GithubSourceOrigin, Long>, GithubSourceOriginRepository {
}
