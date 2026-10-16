package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.Skill;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Starred skills filtered by current object access before counting and paging. */
public interface StarredSkillQueryRepository {
    Page<Skill> findAccessible(String userId, Map<Long, NamespaceRole> namespaceRoles,
                               Set<String> platformRoles, Pageable pageable);
}
