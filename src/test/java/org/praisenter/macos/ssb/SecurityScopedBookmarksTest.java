package org.praisenter.macos.ssb;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class SecurityScopedBookmarksTest {
    @Test
    void preservesExactlyThreeStaticNonNativeMethods() throws Exception {
        Class<?> api = Class.forName(SecurityScopedBookmarks.class.getName());
        var publicMethods = java.util.Arrays.stream(api.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .toList();
        assertEquals(3, publicMethods.size());
        assertEquals(String.class, api.getMethod("createBookmark", String.class).getReturnType());
        assertEquals(String.class, api.getMethod("startResourceAccessing", String.class).getReturnType());
        assertEquals(void.class, api.getMethod("stopResourceAccessing", String.class).getReturnType());
        for (var method : publicMethods) {
            assertTrue(Modifier.isStatic(method.getModifiers()));
            assertFalse(Modifier.isNative(method.getModifiers()));
            assertEquals(0, method.getExceptionTypes().length);
        }
        for (var method : api.getDeclaredMethods()) {
            assertFalse(Modifier.isPublic(method.getModifiers()) && Modifier.isNative(method.getModifiers()));
        }
    }

    @Test
    void validatesWithoutInitializingNativeBindings() {
        assertThrows(NullPointerException.class, () -> SecurityScopedBookmarks.createBookmark(null));
        for (String invalid : List.of("", "file:///tmp/file", "a\0b", "\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> SecurityScopedBookmarks.createBookmark(invalid));
        }
        assertThrows(NullPointerException.class, () -> SecurityScopedBookmarks.startResourceAccessing(null));
        assertThrows(NullPointerException.class, () -> SecurityScopedBookmarks.stopResourceAccessing(null));
        for (String invalid : List.of("", "not base64", "%%", "AQ==\n", "A", "-_==")) {
            assertThrows(IllegalArgumentException.class, () -> SecurityScopedBookmarks.startResourceAccessing(invalid));
            assertThrows(IllegalArgumentException.class, () -> SecurityScopedBookmarks.stopResourceAccessing(invalid));
        }
        SecurityScopedBookmarks.stopResourceAccessing("AQ==");
    }

    @Test
    void generatedSignaturesUseMacosCarriersWithoutInitializingBindings() throws Exception {
        Class<?> generated = Class.forName(
                "org.praisenter.macos.ssb.internal.generated.CoreFoundationBindings",
                false, getClass().getClassLoader());
        Class<?> pointer = java.lang.foreign.MemorySegment.class;
        assertEquals(long.class, generated.getMethod("CFDataGetLength", pointer).getReturnType());
        assertEquals(long.class, generated.getMethod("CFStringGetLength", pointer).getReturnType());
        assertEquals(long.class, generated.getMethod("CFErrorGetCode", pointer).getReturnType());
        assertEquals(byte.class, generated.getMethod("CFURLStartAccessingSecurityScopedResource", pointer).getReturnType());
        assertEquals(pointer, generated.getMethod("CFStringCreateWithBytes", pointer, pointer, long.class, int.class, byte.class).getReturnType());
        assertEquals(pointer, generated.getMethod("CFURLCreateBookmarkData", pointer, pointer, long.class, pointer, pointer, pointer).getReturnType());
    }

    @Test
    @DisabledOnOs(OS.MAC)
    void reportsUnsupportedPlatformsBeforeLoadingNativeLibraries() {
        assertThrows(UnsupportedOperationException.class, () -> SecurityScopedBookmarks.createBookmark("/tmp/path with spaces"));
        assertThrows(UnsupportedOperationException.class, () -> SecurityScopedBookmarks.startResourceAccessing("AQ=="));
    }
}