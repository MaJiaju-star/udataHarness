package com.udata.harness.repository;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Credentials are encrypted separately; the master key lives outside the application data/workspace. */
public final class ModelSecretVault {
    private static final java.util.Set<Path> PROTECTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path root, master;
    private final SecureRandom random = new SecureRandom();
    public ModelSecretVault(Path data, Path master) {
        this.root = data.resolve("model-secrets").toAbsolutePath().normalize(); this.master = master.toAbsolutePath().normalize();
        PROTECTED.add(this.root); PROTECTED.add(this.master);
    }
    public static boolean isProtected(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return PROTECTED.stream().anyMatch(absolute::startsWith);
    }
    public static void requireAllowed(Path path) {
        if (isProtected(path)) throw new SecurityException("权限拒绝：模型密钥存储受保护");
    }
    private byte[] key(boolean create) throws IOException {
        Files.createDirectories(master.getParent());
        if (!Files.exists(master)) {
            if (!create) throw new IOException("Model credential master key is missing; restore it from backup");
            if (Files.isDirectory(root)) try (java.util.stream.Stream<Path> existing = Files.walk(root)) {
                if (existing.anyMatch(Files::isRegularFile)) throw new IOException("Model credential master key is missing; restore it from backup");
            }
            byte[] key = new byte[32]; random.nextBytes(key);
            try { Files.write(master, key, StandardOpenOption.CREATE_NEW); restrict(master); }
            catch (FileAlreadyExistsException ignored) { }
        }
        byte[] key = Files.readAllBytes(master);
        if (key.length != 32) throw new IOException("Invalid model credential master key");
        return key;
    }
    private static void restrict(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        java.nio.file.attribute.AclFileAttributeView acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) acl.setAcl(java.util.Collections.singletonList(java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
    }
    public synchronized String save(String user, String secret) throws IOException {
        String ref = UUID.randomUUID().toString();
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] body = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, body, nonce.length, encrypted.length);
            Path file = path(user, ref); Files.createDirectories(file.getParent());
            Files.write(file, body, StandardOpenOption.CREATE_NEW); restrict(file);
            return ref;
        } catch (java.security.GeneralSecurityException e) { throw new IOException("Cannot encrypt model credential", e); }
    }
    public synchronized String read(String user, String ref) throws IOException {
        if (ref == null || ref.isEmpty()) return "";
        try {
            byte[] body = Files.readAllBytes(path(user, ref));
            if (body.length < 28) throw new IOException("Invalid model credential");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(body, 12)));
            cipher.updateAAD(user.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(body, 12, body.length)), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) { throw new IOException("Cannot decrypt model credential", e); }
    }
    private Path path(String user, String ref) {
        if (!ref.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid credential reference");
        return root.resolve(com.udata.harness.service.UserWorkspaceService.requireUserId(user)).resolve(ref + ".key");
    }
}
