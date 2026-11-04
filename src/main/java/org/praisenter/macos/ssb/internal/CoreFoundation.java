package org.praisenter.macos.ssb.internal;

import static java.lang.foreign.MemorySegment.NULL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.praisenter.macos.ssb.internal.generated.CoreFoundationBindings.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class CoreFoundation {
    private static CoreFoundation instance;

    private CoreFoundation() {
        if (C_LONG.byteSize() != 8 || C_POINTER.byteSize() != 8 || C_CHAR.byteSize() != 1 || C_INT.byteSize() != 4) {
            throw new UnsupportedOperationException("Core Foundation requires the macOS 64-bit LP64 ABI");
        }
        CFRelease$handle();
    }

    public static synchronized CoreFoundation get() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(os.startsWith("mac") || os.equals("darwin"))
                || !(architecture.equals("aarch64") || architecture.equals("arm64")
                    || architecture.equals("x86_64") || architecture.equals("amd64"))) {
            throw new UnsupportedOperationException("Security scoped bookmarks require 64-bit macOS: " + os + " / " + architecture);
        }
        Module module = CoreFoundation.class.getModule();
        if (!module.isNativeAccessEnabled()) {
            String target = module.isNamed() ? module.getName() : "ALL-UNNAMED";
            throw new IllegalStateException("Enable FFM native access using --enable-native-access=" + target);
        }
        if (instance == null) {
            try {
                instance = new CoreFoundation();
            } catch (ExceptionInInitializerError failure) {
                throw new IllegalStateException("Could not initialize macOS Core Foundation FFM bindings", failure);
            }
        }
        return instance;
    }

    public static void validatePath(String location) {
        Objects.requireNonNull(location, "location");
        if (location.isEmpty() || location.indexOf('\0') >= 0 || location.startsWith("file:")) {
            throw new IllegalArgumentException("Location must be a nonempty filesystem path, not a file URI");
        }
        utf8(location);
    }

    private static byte[] utf8(String text) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Text must contain well-formed Unicode", failure);
        }
    }

    public byte[] create(String location) {
        validatePath(location);
        Path path = Path.of(location).toAbsolutePath();
        MemorySegment string = NULL;
        MemorySegment url = NULL;
        MemorySegment data = NULL;
        try (Arena arena = Arena.ofConfined()) {
            string = newString(path.toString(), arena);
            url = require(CFURLCreateWithFileSystemPath(NULL, string, kCFURLPOSIXPathStyle(),
                    (byte)(Files.isDirectory(path) ? 1 : 0)), "Create filesystem URL");
            MemorySegment error = arena.allocate(ADDRESS);
            data = result(CFURLCreateBookmarkData(NULL, url, kCFURLBookmarkCreationWithSecurityScope(),
                    NULL, NULL, error), "Create security scoped bookmark", error);
            return copyData(data);
        } finally {
            release(data);
            release(url);
            release(string);
        }
    }

    public MemorySegment resolve(byte[] bookmark) {
        Objects.requireNonNull(bookmark, "bookmark");
        if (bookmark.length == 0) { throw new IllegalArgumentException("Bookmark data must not be empty"); }
        MemorySegment data = NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(JAVA_BYTE, bookmark);
            data = require(CFDataCreate(NULL, bytes, bookmark.length), "Create bookmark CFData");
            MemorySegment stale = arena.allocate(JAVA_BYTE);
            MemorySegment error = arena.allocate(ADDRESS);
            return result(CFURLCreateByResolvingBookmarkData(NULL, data, kCFURLBookmarkResolutionWithSecurityScope(),
                    NULL, NULL, stale, error), "Resolve security scoped bookmark", error);
        } finally {
            release(data);
        }
    }

    public String absoluteUrl(MemorySegment url) {
        return string(require(CFURLGetString(url), "Get resolved URL string"));
    }

    public boolean startAccessing(MemorySegment url) {
        return CFURLStartAccessingSecurityScopedResource(url) != 0;
    }

    public void stopAccessing(MemorySegment url) {
        CFURLStopAccessingSecurityScopedResource(url);
    }

    public void release(MemorySegment object) {
        if (object.address() != 0) { CFRelease(object); }
    }

    MemorySegment newString(String text, Arena arena) {
        byte[] bytes = utf8(text);
        return require(CFStringCreateWithBytes(NULL, arena.allocateFrom(JAVA_BYTE, bytes), bytes.length,
                kCFStringEncodingUTF8(), (byte)0), "Create UTF-8 CFString");
    }

    String string(MemorySegment string) {
        long capacity = Math.addExact(CFStringGetMaximumSizeForEncoding(CFStringGetLength(string), kCFStringEncodingUTF8()), 1);
        if (capacity < 1 || capacity > Integer.MAX_VALUE) {
            throw new IllegalStateException("Core Foundation string is too large to copy into Java");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(capacity);
            if (CFStringGetCString(string, bytes, capacity, kCFStringEncodingUTF8()) == 0) {
                throw new IllegalStateException("Could not convert Core Foundation string to UTF-8");
            }
            return bytes.getString(0, StandardCharsets.UTF_8);
        }
    }

    byte[] copyData(MemorySegment data) {
        long length = CFDataGetLength(data);
        if (length < 1 || length > Integer.MAX_VALUE) {
            throw new IllegalStateException("Core Foundation returned empty or oversized bookmark data");
        }
        return require(CFDataGetBytePtr(data), "Get bookmark data bytes").reinterpret(length).toArray(JAVA_BYTE);
    }

    private MemorySegment require(MemorySegment value, String operation) {
        if (value.address() == 0) { throw new IllegalStateException(operation + " returned null"); }
        return value;
    }

    private MemorySegment result(MemorySegment value, String operation, MemorySegment errorSlot) {
        MemorySegment error = errorSlot.get(ADDRESS, 0);
        try {
            if (value.address() != 0) { return value; }
            if (error.address() == 0) { throw new IllegalStateException(operation + " failed without a CFError"); }
            MemorySegment description = CFErrorCopyDescription(error);
            try {
                String domain = string(require(CFErrorGetDomain(error), "Get error domain"));
                String detail = description.address() == 0 ? "No description" : string(description);
                throw new IllegalStateException(operation + " failed: " + domain + " / " + CFErrorGetCode(error) + ": " + detail);
            } finally {
                release(description);
            }
        } finally {
            release(error);
        }
    }
}