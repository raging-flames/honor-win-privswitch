package com.local.privswitch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Minimal root shell runner (su -c). */
public final class Shell {

    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/data/adb/ksu/bin/su",
            "/system/xbin/su",
            "/sbin/su"
    };

    private static String suPath = null;

    private static String su() {
        if (suPath != null) return suPath;
        for (String p : SU_CANDIDATES) {
            try {
                if (new File(p).exists()) {
                    suPath = p;
                    return p;
                }
            } catch (Exception ignored) {
            }
        }
        suPath = "su";
        return suPath;
    }

    public static class R {
        public int code = -1;
        public String out = "";
        public String err = "";
        public boolean timeout = false;

        public String all() {
            String s = out == null ? "" : out.trim();
            String e = err == null ? "" : err.trim();
            if (s.isEmpty()) return e;
            if (e.isEmpty()) return s;
            return s + "\n" + e;
        }
    }

    private Shell() {}

    public static R su(String cmd) {
        R r = new R();
        Process p = null;
        try {
            p = new ProcessBuilder(su(), "-c", cmd).start();
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            if (!done) {
                r.timeout = true;
                p.destroy();
            }
            r.out = read(p.getInputStream());
            r.err = read(p.getErrorStream());
            if (done) r.code = p.exitValue();
        } catch (Exception e) {
            r.err = e.toString();
            if (p != null) p.destroy();
        }
        return r;
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } catch (IOException ignored) {
        }
        try {
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
