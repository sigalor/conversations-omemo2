package eu.siacs.conversations.utils;

import com.google.common.base.Strings;
import eu.siacs.conversations.xmpp.Jid;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Percent encoding for the JID that sits in the path of an {@code xmpp:} URI (XEP-0147).
 *
 * <p>A JID may legally carry characters that are delimiters in a URI. RFC 7622 only forbids {@code
 * "&'/:<>@} and space in a localpart, so {@code %}, {@code #} and {@code ?} are all permitted -
 * and the resourcepart is barely restricted at all. Gateways produce such addresses in practice:
 * biboumi maps IRC channels to {@code #channel%irc.example.org@gateway.example.com}. Written into
 * a URI unescaped, the {@code #} starts a fragment and the JID is lost.
 *
 * <p>This deliberately mirrors {@code android.net.Uri.encode(s, "@/+")}, which the rest of the app
 * uses for locally consumed URIs, so both sides agree on the output. It is written in plain Java
 * rather than delegating so that it can be exercised by unit tests - {@code android.net.Uri} is
 * stubbed on the JVM and returns null.
 */
public final class XmppUriEscaper {

    private XmppUriEscaper() {}

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** Kept verbatim: unreserved characters, plus the JID delimiters that are safe in a path. */
    private static boolean isSafe(final char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || "-_.!~*'()".indexOf(c) >= 0
                || "@/+".indexOf(c) >= 0;
    }

    /** Percent encodes {@code input} so it can be placed in the path of an {@code xmpp:} URI. */
    public static String escape(final CharSequence input) {
        if (input == null) {
            return null;
        }
        final byte[] bytes = input.toString().getBytes(StandardCharsets.UTF_8);
        final StringBuilder builder = new StringBuilder(bytes.length);
        for (final byte b : bytes) {
            final int value = b & 0xff;
            if (value < 0x80 && isSafe((char) value)) {
                builder.append((char) value);
            } else {
                builder.append('%').append(HEX[value >> 4]).append(HEX[value & 0x0f]);
            }
        }
        return builder.toString();
    }

    /**
     * Reverses {@link #escape(CharSequence)}.
     *
     * @throws IllegalArgumentException if a {@code %} is not followed by two hex digits. Callers
     *     rely on this to recognize input that was never escaped in the first place.
     */
    public static String unescape(final String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            final char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("truncated escape sequence");
                }
                final int hi = Character.digit(value.charAt(i + 1), 16);
                final int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("invalid escape sequence");
                }
                out.write((hi << 4) | lo);
                i += 3;
            } else {
                final byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Reads the JID out of the path of an {@code xmpp:} URI, accepting both the escaped form we
     * now write and the unescaped form published by older builds and by other clients.
     *
     * <p>The escaped reading is attempted first because that is what XEP-0147 prescribes. The two
     * forms are not fully distinguishable: a legacy JID carrying a literal {@code %} followed by
     * two hex digits reads as an escape. That is accepted - it needs a gateway address chosen to
     * collide, and the fallback covers every case where the escaped reading does not yield a JID.
     *
     * @return the JID, or null if neither reading produces one.
     */
    public static Jid parseJidOrNull(final String path) {
        if (Strings.isNullOrEmpty(path)) {
            return null;
        }
        try {
            return Jid.of(unescape(path));
        } catch (final IllegalArgumentException e) {
            // not escaped, or escaped to something that is not a JID - try it verbatim
        }
        try {
            return Jid.of(path);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}