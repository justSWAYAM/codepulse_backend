package com.codepulse_backend.common.enums;

/** Why a result is NEEDS_REVIEW. Rank is provisional until these are resolved or acknowledged. */
public enum ReviewReason {
    UNRESOLVED_SYSTEM_ERROR,
    OVERRIDE_OUTDATED
}
