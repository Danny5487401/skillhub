package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.githubsync.GithubSourceBinding;
import com.iflytek.skillhub.githubsync.GithubSourceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GithubSourceJpaRepository extends JpaRepository<GithubSourceBinding, Long>, GithubSourceRepository {
}
