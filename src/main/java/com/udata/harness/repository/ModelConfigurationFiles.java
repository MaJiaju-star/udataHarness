package com.udata.harness.repository;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keep saved API keys out of Agent file tool results. */
public final class ModelConfigurationFiles {
    private static final Set<Path> PROTECTED = ConcurrentHashMap.newKeySet();
    private ModelConfigurationFiles() { }
    public static void protect(Path file) { PROTECTED.add(file.toAbsolutePath().normalize()); }
    public static boolean isProtected(Path path) { return PROTECTED.contains(path.toAbsolutePath().normalize()); }
    public static void requireAllowed(Path path) {
        if (isProtected(path)) throw new SecurityException("权限拒绝：模型配置包含 API Key");
    }
}
