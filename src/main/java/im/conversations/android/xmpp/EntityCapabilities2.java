package im.conversations.android.xmpp;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;

import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.Hash;
import im.conversations.android.xmpp.model.data.Data;
import im.conversations.android.xmpp.model.data.Field;
import im.conversations.android.xmpp.model.data.Value;
import im.conversations.android.xmpp.model.disco.info.Feature;
import im.conversations.android.xmpp.model.disco.info.Identity;
import im.conversations.android.xmpp.model.disco.info.InfoQuery;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public class EntityCapabilities2 {

    private static final Set<ExtensionFactory.Id> ALLOW_LIST_EXTENSIONS =
            ImmutableSet.of(
                    ExtensionFactory.id(Identity.class),
                    ExtensionFactory.id(Feature.class),
                    ExtensionFactory.id(Data.class));

    private static final char UNIT_SEPARATOR = 0x1f;
    private static final char RECORD_SEPARATOR = 0x1e;

    private static final char GROUP_SEPARATOR = 0x1d;

    private static final char FILE_SEPARATOR = 0x1c;

    public static EntityCaps2Hash hash(final InfoQuery info) throws IllegalInfoQueryException {
        return hash(Hash.Algorithm.SHA_256, info);
    }

    public static EntityCaps2Hash hash(final Hash.Algorithm algorithm, final InfoQuery info)
            throws IllegalInfoQueryException {
        final String result = algorithm(info);
        final var hashFunction = toHashFunction(algorithm);
        return new EntityCaps2Hash(
                algorithm, hashFunction.hashString(result, StandardCharsets.UTF_8).asBytes());
    }

    private static HashFunction toHashFunction(final Hash.Algorithm algorithm) {
        switch (algorithm) {
            case SHA_1:
                return Hashing.sha1();
            case SHA_256:
                return Hashing.sha256();
            case SHA_512:
                return Hashing.sha512();
            default:
                throw new IllegalArgumentException("Unknown hash algorithm");
        }
    }

    private static String asHex(final String message) {
        return Joiner.on(' ')
                .join(
                        Collections2.transform(
                                Bytes.asList(message.getBytes(StandardCharsets.UTF_8)),
                                b -> String.format("%02x", b)));
    }

    private static String algorithm(final InfoQuery infoQuery) throws IllegalInfoQueryException {
        checkElementsAllowList(infoQuery);
        return features(infoQuery.getFeatures())
                + identities(infoQuery.getIdentities())
                + extensions(infoQuery.getExtensions(Data.class));
    }

    /**
     * XEP-0390 abort conditions. The hash only identifies a disco#info unambiguously as long as
     * every part of the document feeds into it. An element the algorithm does not know about would
     * be silently skipped, which lets two different documents share one hash - and because the caps
     * cache is keyed by that hash and shared between entities, a colliding pair is enough to serve
     * one entity's features under another's key. Refuse to produce a hash in that case.
     */
    private static void checkElementsAllowList(final InfoQuery infoQuery)
            throws IllegalInfoQueryException {
        for (final var id : infoQuery.getExtensionIds()) {
            if (!ALLOW_LIST_EXTENSIONS.contains(id)) {
                throw new IllegalInfoQueryException("InfoQuery contains invalid elements");
            }
        }
    }

    private static String identities(final Collection<Identity> identities) {
        return Joiner.on("")
                        .join(
                                Ordering.natural()
                                        .sortedCopy(
                                                Collections2.transform(
                                                        identities, EntityCapabilities2::identity)))
                + FILE_SEPARATOR;
    }

    private static String identity(final Identity identity) {
        return Strings.nullToEmpty(identity.getCategory())
                + UNIT_SEPARATOR
                + Strings.nullToEmpty(identity.getType())
                + UNIT_SEPARATOR
                + Strings.nullToEmpty(identity.getLang())
                + UNIT_SEPARATOR
                + Strings.nullToEmpty(identity.getIdentityName())
                + UNIT_SEPARATOR
                + RECORD_SEPARATOR;
    }

    private static String features(Collection<Feature> features) {
        return Joiner.on("")
                        .join(
                                Ordering.natural()
                                        .sortedCopy(
                                                Collections2.transform(
                                                        features, EntityCapabilities2::feature)))
                + FILE_SEPARATOR;
    }

    private static String feature(final Feature feature) {
        return Strings.nullToEmpty(feature.getVar()) + UNIT_SEPARATOR;
    }

    private static String value(final Value value) {
        return Strings.nullToEmpty(value.getContent()) + UNIT_SEPARATOR;
    }

    private static String values(final Collection<Value> values) {
        return Joiner.on("")
                .join(
                        Ordering.natural()
                                .sortedCopy(
                                        Collections2.transform(
                                                values, EntityCapabilities2::value)));
    }

    private static String field(final Field field) {
        return Strings.nullToEmpty(field.getFieldName())
                + UNIT_SEPARATOR
                + values(field.getExtensions(Value.class))
                + RECORD_SEPARATOR;
    }

    private static String fields(final Collection<Field> fields) {
        return Joiner.on("")
                        .join(
                                Ordering.natural()
                                        .sortedCopy(
                                                Collections2.transform(
                                                        fields, EntityCapabilities2::field)))
                + GROUP_SEPARATOR;
    }

    private static String extension(final Data data) {
        return fields(data.getExtensions(Field.class));
    }

    private static String extensions(final Collection<Data> extensions)
            throws IllegalInfoQueryException {
        for (final var data : extensions) {
            // Forms are ordered by FORM_TYPE, so one without it has no defined position; and a
            // multi-item form's <item/>/<reported/> content never reaches the hash at all.
            if (Strings.isNullOrEmpty(data.getFormType())) {
                throw new IllegalInfoQueryException("A data extension is missing a form_type");
            }
            if (data.hasChild("item", Namespace.DATA)) {
                throw new IllegalInfoQueryException("data form extension contains item");
            }
            if (data.hasChild("reported", Namespace.DATA)) {
                throw new IllegalInfoQueryException("data form extension contains reported");
            }
        }
        return Joiner.on("")
                        .join(
                                Ordering.natural()
                                        .sortedCopy(
                                                Collections2.transform(
                                                        extensions,
                                                        EntityCapabilities2::extension)))
                + FILE_SEPARATOR;
    }

    public static class EntityCaps2Hash extends EntityCapabilities.Hash {

        public final Hash.Algorithm algorithm;

        protected EntityCaps2Hash(final Hash.Algorithm algorithm, byte[] hash) {
            super(hash);
            this.algorithm = algorithm;
        }

        public static EntityCaps2Hash of(final Hash.Algorithm algorithm, final String encoded) {
            return new EntityCaps2Hash(algorithm, BaseEncoding.base64().decode(encoded));
        }

        @Override
        public String capabilityNode(String node) {
            return String.format(
                    "%s#%s.%s", Namespace.ENTITY_CAPABILITIES_2, algorithm.toString(), encoded());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;
            EntityCaps2Hash that = (EntityCaps2Hash) o;
            return algorithm == that.algorithm;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), algorithm);
        }
    }

    /**
     * Raised when a disco#info cannot be hashed unambiguously. Callers must skip caching rather
     * than fall back to a partial hash.
     */
    public static class IllegalInfoQueryException extends Exception {
        private IllegalInfoQueryException(final String message) {
            super(message);
        }
    }
}
