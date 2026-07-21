# Enable 16 KB page size support for Android 15+ / Play Store requirement.
# NDK r28 does this by default, but explicit is safer for build verification.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
