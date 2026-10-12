package com.iflytek.skillhub.domain.namespace;

import com.iflytek.skillhub.domain.user.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures newly active users belong to the built-in global namespace.
 */
@Service
public class GlobalNamespaceMembershipService {

    private static final String GLOBAL_NAMESPACE_SLUG = "global";

    private final NamespaceRepository namespaceRepository;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private final UserAccountRepository userAccountRepository;

    public GlobalNamespaceMembershipService(NamespaceRepository namespaceRepository,
                                            NamespaceMemberRepository namespaceMemberRepository,
                                            UserAccountRepository userAccountRepository) {
        this.namespaceRepository = namespaceRepository;
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public void ensureMember(String userId) {
        userAccountRepository.findLockedById(userId);
        Long namespaceId = namespaceRepository.findIdBySlug(GLOBAL_NAMESPACE_SLUG)
                .orElseThrow(() -> new IllegalStateException("Missing built-in global namespace"));
        namespaceRepository.findLockedById(namespaceId);

        namespaceMemberRepository.findByNamespaceIdAndUserId(namespaceId, userId)
                .orElseGet(() -> namespaceMemberRepository.save(
                        new NamespaceMember(namespaceId, userId, NamespaceRole.MEMBER)
                ));
    }
}
