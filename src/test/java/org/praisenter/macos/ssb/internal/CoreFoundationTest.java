package org.praisenter.macos.ssb.internal;

import static java.lang.foreign.MemorySegment.NULL;
import static org.junit.jupiter.api.Assertions.*;
import static org.praisenter.macos.ssb.internal.generated.CoreFoundationBindings.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.praisenter.macos.ssb.SecurityScopedBookmarks;

@EnabledOnOs(OS.MAC)
class CoreFoundationTest {
    @TempDir Path directory;

    @Test
    void verifiesMacosScalarLayoutsAndGeneratedCarriers() {
        CoreFoundation.get();
        assertEquals(8, C_LONG.byteSize());
        assertEquals(8, C_POINTER.byteSize());
        assertEquals(1, C_CHAR.byteSize());
        assertEquals(4, C_INT.byteSize());
        assertEquals(long.class, CFDataGetLength$descriptor().toMethodType().returnType());
        assertEquals(long.class, CFURLCreateBookmarkData$descriptor().toMethodType().parameterType(2));
        assertEquals(byte.class, CFURLStartAccessingSecurityScopedResource$descriptor().toMethodType().returnType());
        assertEquals(int.class, CFStringCreateWithBytes$descriptor().toMethodType().parameterType(3));
        assertEquals(2048, kCFURLBookmarkCreationWithSecurityScope());
        assertEquals(1024, kCFURLBookmarkResolutionWithSecurityScope());
    }

    @Test
    void roundTripsUnicodeText() {
        CoreFoundation core = CoreFoundation.get();
        MemorySegment text = NULL;
        try (Arena arena = Arena.ofConfined()) {
            text = core.newString("space % # ? \u00E9 \u65E5\u672C \uD83C\uDFB5", arena);
            assertEquals("space % # ? \u00E9 \u65E5\u672C \uD83C\uDFB5", core.string(text));
        } finally { core.release(text); }
    }

    @Test
    void bookmarksPathsWithSpacesUnicodeAndLiteralUrlCharacters() throws Exception {
        Path file = Files.writeString(directory.resolve("space % # ? \u00E9 \u65E5\u672C \uD83C\uDFB5.txt"), "bookmark test");
        Path folder = Files.createDirectory(directory.resolve("folder with spaces \u00E9"));
        CoreFoundation core = CoreFoundation.get();
        for (Path path : new Path[] { file, folder }) {
            for (int iteration = 0; iteration < 20; iteration++) {
                byte[] bookmark = Base64.getDecoder().decode(SecurityScopedBookmarks.createBookmark(path.toString()));
                MemorySegment url = core.resolve(bookmark);
                try {
                    assertTrue(Files.isSameFile(path, Path.of(URI.create(core.absoluteUrl(url)))));
                } finally { core.release(url); }
            }
        }
    }

    @Test
    void malformedNativeBookmarkReportsAnError() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CoreFoundation.get().resolve(new byte[] { 1, 2, 3 }));
        assertTrue(failure.getMessage().contains("Resolve security scoped bookmark"));
    }

    @Test
    @EnabledIfSystemProperty(named = "ssb.sandbox.bookmark", matches = ".+")
    void accessesPersistedBookmarkInsideSandbox() throws Exception {
        String bookmark = System.getProperty("ssb.sandbox.bookmark");
        String first = SecurityScopedBookmarks.startResourceAccessing(bookmark);
        try {
            String second = SecurityScopedBookmarks.startResourceAccessing(bookmark);
            try {
                assertEquals(first, second);
                Path path = Path.of(URI.create(first));
                if (Files.isDirectory(path)) {
                    try (var children = Files.list(path)) { children.count(); }
                } else { Files.readAllBytes(path); }
            } finally { SecurityScopedBookmarks.stopResourceAccessing(bookmark); }
        } finally { SecurityScopedBookmarks.stopResourceAccessing(bookmark); }
        SecurityScopedBookmarks.stopResourceAccessing(bookmark);
        SecurityScopedBookmarks.startResourceAccessing(bookmark);
        SecurityScopedBookmarks.stopResourceAccessing(bookmark);
    }
}