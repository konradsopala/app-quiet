package com.booking.util;

/**
 * Normalizes free-text phone numbers into a consistent display format.
 *
 * Customer records carry phone numbers as raw strings entered at the CLI,
 * so the same number can appear as "+48 601-234-567", "0048601234567", or
 * "601 234 567". Normalizing before storing/printing keeps snapshots and
 * reports comparable.
 */
public final class PhoneNumberFormatter {

    private PhoneNumberFormatter() {
    }

    /**
     * Normalizes a phone number to a canonical form: an optional leading
     * "+" followed by digits only (e.g. "+48601234567").
     *
     * Spaces, dashes, dots and parentheses are stripped. A leading "00"
     * international prefix is rewritten to "+". Returns null when the
     * input is null, blank, or contains no digits.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[\\s\\-.()]", "");
        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2);
        }
        boolean hasPlus = cleaned.startsWith("+");
        String digits = cleaned.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        return hasPlus ? "+" + digits : digits;
    }

    /**
     * Returns true when the value normalizes to a plausible phone number:
     * 7 to 15 digits, per the E.164 length ceiling.
     */
    public static boolean isPlausible(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return false;
        }
        String digits = normalized.startsWith("+") ? normalized.substring(1) : normalized;
        return digits.length() >= 7 && digits.length() <= 15;
    }
}
