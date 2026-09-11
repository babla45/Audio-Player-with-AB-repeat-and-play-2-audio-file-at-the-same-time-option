package com.arthenica.smartexception.java;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Minimal local replacement for the retired com.arthenica:smartexception-java
 * library, whose artifacts were removed from Maven Central after ffmpeg-kit's
 * retirement (April 2025).
 *
 * The ffmpeg-kit AAR (dev.ffmpegkit-maintained fork) references exactly two
 * static methods of this class (verified from its bytecode):
 *  - getStackTraceString(Throwable)
 *  - registerRootPackage(String)
 *
 * Without this class FFmpegKitConfig's static initializer throws
 * NoClassDefFoundError and crashes the app on the first FFmpeg call.
 */
public final class Exceptions {

    /** Root packages registered by callers (kept for API compatibility). */
    private static String[] rootPackages = new String[0];

    private Exceptions() {
    }

    /** Called by ffmpeg-kit during class init to mark its own package. */
    public static void registerRootPackage(String rootPackage) {
        if (rootPackage != null && !rootPackage.isEmpty()) {
            String[] updated = new String[rootPackages.length + 1];
            System.arraycopy(rootPackages, 0, updated, 0, rootPackages.length);
            updated[rootPackages.length] = rootPackage;
            rootPackages = updated;
        }
    }

    /** Full stack trace of the throwable, similar to Log.getStackTraceString. */
    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return "";
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }
}
