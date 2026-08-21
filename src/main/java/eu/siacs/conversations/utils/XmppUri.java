package eu.siacs.conversations.utils;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import eu.siacs.conversations.xmpp.Jid;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class XmppUri {

    public static final String ACTION_JOIN = "join";
    public static final String ACTION_MESSAGE = "message";
    public static final String ACTION_REGISTER = "register";
    public static final String ACTION_ROSTER = "roster";
    public static final String PARAMETER_PRE_AUTH = "preauth";
    public static final String PARAMETER_IBR = "ibr";
    /**
     * The fingerprint parameter every OMEMO client understands. Throughout the
     * ecosystem it means the XEP-0384 v0.3 (legacy) identity key, so that is what
     * we put there whenever this device has one — a Conversations/Dino/Gajim user
     * scanning our code has to end up with the key they will actually see on the
     * wire. Putting the PQ key here would leave them pre-verifying a fingerprint
     * that never shows up, which also takes them out of blind trust for us.
     */
    private static final String OMEMO_URI_PARAM = "omemo-sid-";

    /**
     * The PQ OMEMO2 ({@code urn:monocles:omemo-pq:1}) identity key. Its own
     * parameter because the two stacks keep separate identity keys under the same
     * device id, and because clients that do not speak PQ OMEMO2 must ignore it.
     */
    private static final String OMEMO_PQ_URI_PARAM = "omemo-pq-sid-";

    private static final String OTR_URI_PARAM = "otr-fingerprint";
    protected Uri uri;
    protected String jid;
    private List<Fingerprint> fingerprints = new ArrayList<>();
    private Map<String, String> parameters = Collections.emptyMap();
    private boolean safeSource = true;

    public static final String INVITE_DOMAIN = "monocles.chat";

    public XmppUri(final String uri) {
        try {
            parse(Uri.parse(uri));
        } catch (IllegalArgumentException e) {
            try {
                jid = Jid.of(uri).asBareJid().toString();
            } catch (final IllegalArgumentException e2) {
                jid = null;
            }
        }
    }

    public XmppUri(Uri uri) {
        parse(uri);
    }

    public XmppUri(Uri uri, boolean safeSource) {
        this.safeSource = safeSource;
        parse(uri);
    }

    private static Map<String, String> parseParameters(final String query, final char seperator) {
        // Keeps the first occurrence of a repeated key instead of throwing the way
        // ImmutableMap.Builder does: the input is a scanned code or a link from a
        // web page, and a duplicate parameter must not take down whatever is
        // parsing it. First wins so a later copy cannot override an earlier value.
        final Map<String, String> parameters = new LinkedHashMap<>();
        final String[] pairs =
                query == null ? new String[0] : query.split(String.valueOf(seperator));
        for (String pair : pairs) {
            final String[] parts = pair.split("=", 2);
            if (parts.length == 0) {
                continue;
            }
            final String key = parts[0].toLowerCase(Locale.US);
            final String value;
            if (parts.length == 2) {
                String decoded;
                try {
                    decoded = URLDecoder.decode(parts[1], "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    decoded = "";
                }
                value = decoded;
            } else {
                value = "";
            }
            if (!parameters.containsKey(key)) {
                parameters.put(key, value);
            }
        }
        return ImmutableMap.copyOf(parameters);
    }

    /**
     * An OMEMO identity fingerprint as it travels in a URI: the hex of the Curve25519 public
     * key WITHOUT the leading {@code 05} type byte, i.e. exactly 64 hex characters. Callers
     * put the {@code 05} back before storing it.
     */
    private static final int FINGERPRINT_HEX_LENGTH = 64;

    /**
     * Upper bound on how many fingerprints one URI may carry. A legitimate code holds this
     * device's key per stack plus one per verified other device of the same account — a
     * couple of dozen at the very outside. Without a cap a single scanned code could ask us
     * to write hundreds of rows into the identities table.
     */
    private static final int MAX_FINGERPRINTS = 64;

    /**
     * Whether {@code value} can actually be an OMEMO fingerprint.
     *
     * <p>Nothing checked this before, so a scanned code could carry arbitrary text and it was
     * stored verbatim as a VERIFIED identity row — junk that never matches a real key, but
     * which pollutes the trust table and makes the app report a successful verification.
     * Hex is checked explicitly rather than with a regex so the accepted alphabet is obvious;
     * the value has already been lower-cased by the caller.
     */
    private static boolean isValidFingerprint(final String value) {
        if (value == null || value.length() != FINGERPRINT_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); ++i) {
            final char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    static List<Fingerprint> parseFingerprints(Map<String, String> parameters) {
        ImmutableList.Builder<Fingerprint> builder = new ImmutableList.Builder<>();
        int count = 0;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            final String key = parameter.getKey();
            final String value = parameter.getValue().toLowerCase(Locale.US);
            final FingerprintType type;
            final int id;
            if (key.startsWith(OMEMO_PQ_URI_PARAM)) {
                type = FingerprintType.OMEMO_PQ;
                id = parseDeviceId(key.substring(OMEMO_PQ_URI_PARAM.length()));
            } else if (key.startsWith(OMEMO_URI_PARAM)) {
                type = FingerprintType.OMEMO;
                id = parseDeviceId(key.substring(OMEMO_URI_PARAM.length()));
            } else if ("omemo".equals(key)) {
                type = FingerprintType.OMEMO;
                id = 0;
            } else {
                continue;
            }
            if (id < 0 || !isValidFingerprint(value)) {
                // invalid device id or not a fingerprint at all
                continue;
            }
            if (++count > MAX_FINGERPRINTS) {
                break;
            }
            builder.add(new Fingerprint(type, value, id));
        }
        return builder.build();
    }

    /** The device id of an {@code omemo[-pq]-sid-<id>} parameter, or -1 when it is not one. */
    private static int parseDeviceId(final String suffix) {
        try {
            final int id = Integer.parseInt(suffix);
            return id < 0 ? -1 : id;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    public static String getFingerprintUri(
            final String base, final List<XmppUri.Fingerprint> fingerprints, char separator) {
        final StringBuilder builder = new StringBuilder(base);
        builder.append('?');
        for (int i = 0; i < fingerprints.size(); ++i) {
            XmppUri.FingerprintType type = fingerprints.get(i).type;
            if (type == XmppUri.FingerprintType.OMEMO) {
                builder.append(XmppUri.OMEMO_URI_PARAM);
                builder.append(fingerprints.get(i).deviceId);
            } else if (type == XmppUri.FingerprintType.OMEMO_PQ) {
                builder.append(XmppUri.OMEMO_PQ_URI_PARAM);
                builder.append(fingerprints.get(i).deviceId);
            } else if (type == XmppUri.FingerprintType.OTR) {
                builder.append(XmppUri.OTR_URI_PARAM);
            }
            builder.append('=');
            builder.append(fingerprints.get(i).fingerprint);
            if (i != fingerprints.size() - 1) {
                builder.append(separator);
            }
        }
        return builder.toString();
    }

    private static String lameUrlDecode(String url) {
        return url.replace("%23", "#").replace("%25", "%");
    }

    public static String lameUrlEncode(String url) {
        return url.replace("%", "%25").replace("#", "%23");
    }

    public boolean isSafeSource() {
        return safeSource;
    }

    protected void parse(final Uri uri) {
        if (uri == null) {
            return;
        }
        this.uri = uri;
        final String scheme = uri.getScheme();
        final String host = uri.getHost();
        List<String> segments = uri.getPathSegments();
        if ("https".equalsIgnoreCase(scheme) && INVITE_DOMAIN.equalsIgnoreCase(host)) {
            if (segments.size() >= 2 && segments.get(1).contains("@")) {
                // sample : https://conversations.im/i/foo@bar.com
                try {
                    jid = Jid.of(lameUrlDecode(segments.get(1))).toString();
                } catch (final Exception e) {
                    jid = null;
                }
            } else if (segments.size() >= 3) {
                // sample : https://conversations.im/i/foo/bar.com
                jid = segments.get(1) + "@" + segments.get(2);
            }
            if (segments.size() > 1 && "j".equalsIgnoreCase(segments.get(0))) {
                this.parameters = ImmutableMap.of(ACTION_JOIN, "");
            }
            final Map<String, String> parameters = parseParameters(uri.getQuery(), '&');
            this.fingerprints = parseFingerprints(parameters);
        } else if ("xmpp".equalsIgnoreCase(scheme)) {
            // sample: xmpp:foo@bar.com
            this.parameters = parseParameters(uri.getQuery(), ';');
            if (uri.getAuthority() != null) {
                jid = uri.getAuthority();
            } else {
                final String[] parts = uri.getSchemeSpecificPart().split("\\?");
                if (parts.length > 0) {
                    jid = parts[0];
                } else {
                    return;
                }
            }
            this.fingerprints = parseFingerprints(parameters);
        } else if ("imto".equalsIgnoreCase(scheme)
                && Arrays.asList("xmpp", "jabber").contains(uri.getHost())) {
            // sample: imto://xmpp/foo@bar.com
            try {
                jid = URLDecoder.decode(uri.getEncodedPath(), "UTF-8").split("/")[1].trim();
            } catch (final UnsupportedEncodingException ignored) {
                jid = null;
            }
        } else {
            jid = null;
        }
    }

    @Override
    @NonNull
    public String toString() {
        if (uri != null) {
            return uri.toString();
        }
        return "";
    }

    public boolean isAction(final String action) {
        return Collections2.transform(
                        parameters.keySet(),
                        s ->
                                CharMatcher.inRange('a', 'z')
                                        .or(CharMatcher.inRange('A', 'Z'))
                                        .retainFrom(s))
                .contains(action);
    }

    public Jid getJid() {
        try {
            return this.jid == null ? null : Jid.ofUserInput(this.jid);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isValidJid() {
        if (jid == null) {
            return false;
        }
        try {
            Jid.ofUserInput(jid);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    public String getBody() {
        return parameters.get("body");
    }

    public String getName() {
        return parameters.get("name");
    }

    public String getParameter(String key) {
        return this.parameters.get(key);
    }

    public String parameterString() {
        final StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> param : parameters.entrySet()) {
            if (param.getValue() == null || param.getValue().isEmpty()) continue;

            s.append(";");
            s.append(param.getKey());
            s.append("=");
            s.append(param.getValue());
        }
        return s.toString();
    }

    public String displayParameterString() {
        final StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> param : parameters.entrySet()) {
            if (param.getValue() == null || param.getValue().isEmpty()) continue;
            if (param.getKey().startsWith(OMEMO_URI_PARAM)) continue;
            if (param.getKey().startsWith(OMEMO_PQ_URI_PARAM)) continue;

            s.append(";");
            s.append(param.getKey());
            s.append("=");
            s.append(param.getValue());
        }
        return s.toString();
    }

    public List<Fingerprint> getFingerprints() {
        return this.fingerprints;
    }

    public boolean hasFingerprints() {
        return !fingerprints.isEmpty();
    }

    public enum FingerprintType {
        /** XEP-0384 v0.3 (legacy) OMEMO identity key. */
        OMEMO,
        /** PQ OMEMO2 ({@code urn:monocles:omemo-pq:1}) identity key. */
        OMEMO_PQ,
        OTR
    }

    public static class Fingerprint {
        public final FingerprintType type;
        public final String fingerprint;
        final int deviceId;

        public Fingerprint(FingerprintType type, String fingerprint) {
            this(type, fingerprint, 0);
        }

        public Fingerprint(FingerprintType type, String fingerprint, int deviceId) {
            this.type = type;
            this.fingerprint = fingerprint;
            this.deviceId = deviceId;
        }

        @NonNull
        @Override
        public String toString() {
            return type.toString() + ": " + fingerprint + (deviceId != 0 ? " " + deviceId : "");
        }
    }
}
