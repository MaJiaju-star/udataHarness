package com.udata.harness.common.domain;

/** Tool availability preset, independent of the session's approval policy. */
public final class CapabilityMode {
    public static final String ECONOMY = "economy";
    public static final String STANDARD = "standard";
    public static final String SUPER = "super";

    private CapabilityMode() { }

    public static String require(String value) {
        if (value == null || value.trim().isEmpty()) return STANDARD;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (ECONOMY.equals(normalized) || STANDARD.equals(normalized) || SUPER.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("capabilityMode must be economy, standard or super");
    }
}
