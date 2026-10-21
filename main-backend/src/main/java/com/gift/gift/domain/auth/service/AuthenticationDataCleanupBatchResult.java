package com.gift.gift.domain.auth.service;

public record AuthenticationDataCleanupBatchResult(
        int selectedCount,
        int deletedCount
) {
}
