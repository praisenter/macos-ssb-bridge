package org.praisenter.macos.ssb;

import java.lang.foreign.MemorySegment;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.praisenter.macos.ssb.internal.CoreFoundation;

/**
 * Persistent, app-scoped macOS bookmarks accessed through the Java FFM API.
 * The application must enable native access and provide its own sandbox entitlements.
 * Each successful start must be balanced by a stop using the original bookmark.
 */
public final class SecurityScopedBookmarks {
    private static final class ActiveAccess {
        private final CoreFoundation coreFoundation;
        private final MemorySegment url;
        private final String absoluteUrl;
        private int callers = 1;

        private ActiveAccess(CoreFoundation coreFoundation, MemorySegment url, String absoluteUrl) {
            this.coreFoundation = coreFoundation;
            this.url = url;
            this.absoluteUrl = absoluteUrl;
        }
    }

    private static final Map<String, ActiveAccess> activeAccesses = new HashMap<>();

    private SecurityScopedBookmarks() {
    }

    /**
     * Creates a read-write security scoped bookmark for a filesystem path.
     * Spaces and Unicode are treated as path characters, not URL syntax.
     * Relative paths are resolved against the current working directory on macOS.
     * The caller persists the returned Base64 string for later application launches.
     *
     * @param location filesystem path, not a file URI
     * @return standard Base64 encoding of Apple's opaque bookmark data
     * @throws NullPointerException if location is null
     * @throws IllegalArgumentException if the path is empty or contains invalid text
     * @throws UnsupportedOperationException on unsupported platforms
     * @throws IllegalStateException if macOS cannot create the bookmark
     */
    public static String createBookmark(String location) {
        CoreFoundation.validatePath(location);
        return Base64.getEncoder().encodeToString(CoreFoundation.get().create(location));
    }

    /**
     * Resolves a persisted bookmark and activates scoped access.
     * Repeated starts share one native session and require matching stops.
     * Usable stale bookmarks resolve normally; this method does not refresh persisted data.
     *
     * @param bookmark original Base64 bookmark data
     * @return absolute, percent-encoded file URL; decode it using URI, not string slicing
     * @throws NullPointerException if bookmark is null
     * @throws IllegalArgumentException if bookmark is empty or not standard Base64
     * @throws UnsupportedOperationException on unsupported platforms
     * @throws IllegalStateException if macOS cannot resolve the bookmark
     * @throws SecurityException if scoped access cannot be activated
     */
    public static synchronized String startResourceAccessing(String bookmark) {
        byte[] data = decodeBookmark(bookmark);
        String key = Base64.getEncoder().encodeToString(data);
        ActiveAccess existing = activeAccesses.get(key);
        if (existing != null) {
            existing.callers = Math.incrementExact(existing.callers);
            return existing.absoluteUrl;
        }

        CoreFoundation coreFoundation = CoreFoundation.get();
        MemorySegment url = Objects.requireNonNull(coreFoundation.resolve(data), "resolved URL");
        if (url.address() == 0) {
            throw new IllegalStateException("Bookmark resolution returned a null URL");
        }
        boolean started = false;
        try {
            String absoluteUrl = Objects.requireNonNull(coreFoundation.absoluteUrl(url), "absolute URL");
            started = coreFoundation.startAccessing(url);
            if (!started) {
                throw new SecurityException("Could not activate security-scoped resource access");
            }
            activeAccesses.put(key, new ActiveAccess(coreFoundation, url, absoluteUrl));
            return absoluteUrl;
        } catch (RuntimeException | Error failure) {
            if (started) {
                try {
                    coreFoundation.stopAccessing(url);
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                coreFoundation.release(url);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Ends one caller's access to the original bookmark.
     * The final matching stop deactivates and releases the native URL.
     * Unknown or already stopped valid bookmarks are a no-op.
     * Stopping does not invalidate persisted bookmark data; later starts are permitted.
     *
     * @param bookmark original Base64 bookmark, not the URL returned by start
     * @throws NullPointerException if bookmark is null
     * @throws IllegalArgumentException if bookmark is empty or not standard Base64
     */
    public static synchronized void stopResourceAccessing(String bookmark) {
        String key = Base64.getEncoder().encodeToString(decodeBookmark(bookmark));
        ActiveAccess access = activeAccesses.get(key);
        if (access == null) {
            return;
        }
        if (--access.callers != 0) {
            return;
        }
        activeAccesses.remove(key);
        try {
            access.coreFoundation.stopAccessing(access.url);
        } catch (RuntimeException | Error failure) {
            try {
                access.coreFoundation.release(access.url);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        access.coreFoundation.release(access.url);
    }

    private static byte[] decodeBookmark(String bookmark) {
        java.util.Objects.requireNonNull(bookmark, "bookmark");
        byte[] data;
        try {
            data = Base64.getDecoder().decode(bookmark);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Bookmark must contain standard Base64 data", failure);
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("Bookmark data must not be empty");
        }
        return data;
    }
}