package com.loopcamera.loop20;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Persists the last uncaught crash so the next launch can show it in-app. */
public final class CrashLog {

    private static final String FILE = "last_crash.txt";

    private CrashLog() {
    }

    public static void write(Context context, Throwable t) {
        try {
            File f = new File(context.getFilesDir(), FILE);
            try (PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
                out.println(System.currentTimeMillis());
                t.printStackTrace(out);
            }
        } catch (Exception ignored) {
        }
    }

    public static String readAndClear(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.isFile()) {
            return null;
        }
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            f.delete();
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    public static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
