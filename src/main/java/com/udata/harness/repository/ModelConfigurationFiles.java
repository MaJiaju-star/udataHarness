package com.udata.harness.repository;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keep managed private configuration and memory outside ordinary Agent file tools.
 */
public final class ModelConfigurationFiles {

    private static final Set<Path> PROTECTED = ConcurrentHashMap.newKeySet();

    private ModelConfigurationFiles() {
    }

    public static void protect(Path file) {
        PROTECTED.add(file.toAbsolutePath().normalize());
    }

    public static void unprotect(Path path) {
        PROTECTED.remove(path.toAbsolutePath().normalize());
    }

    public static boolean isProtected(Path path) {
        return PROTECTED.contains(path.toAbsolutePath().normalize());
    }

    public static void requireAllowed(Path path) {
        if (isProtected(path)) {
            throw new SecurityException("权限拒绝：此文件由后台管理，可能包含私密配置或记忆，请使用对应管理功能");
        }
    }
}
