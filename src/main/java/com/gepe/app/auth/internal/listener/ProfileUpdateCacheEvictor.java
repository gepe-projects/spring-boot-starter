package com.gepe.app.auth.internal.listener;

import com.gepe.app.auth.internal.service.UserDetailsCache;
import com.gepe.app.user.api.ProfileUpdated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Incoming event dari module user (AGENTS.md §2 sub-package rule 3): profil di-update →
 * evict cache komposit GET /auth/me supaya userDTO+profileDTO tidak basi.
 *
 * <p>Idempotent (delete key Redis aman diulang) — sesuai mandat listener wajib idempotent
 * karena event bisa di-resubmit setelah restart instance.
 */
@Slf4j
@Component
class ProfileUpdateCacheEvictor {

    private final UserDetailsCache userDetailsCache;

    ProfileUpdateCacheEvictor(UserDetailsCache userDetailsCache) {
        this.userDetailsCache = userDetailsCache;
    }

    @ApplicationModuleListener
    void onProfileUpdated(ProfileUpdated event) {
        userDetailsCache.evict(event.userId());
        log.debug("Evicted user details cache for profile update: userId={}", event.userId());
    }
}
