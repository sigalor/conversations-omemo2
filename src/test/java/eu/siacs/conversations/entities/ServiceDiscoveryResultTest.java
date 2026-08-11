package eu.siacs.conversations.entities;

import eu.siacs.conversations.xml.Element;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Assert;
import org.junit.Test;

/**
 * Lives in this package so the protected {@code ver} and {@code forms} fields are reachable
 * without going through {@code getVer()}, which needs {@code android.util.Base64}.
 */
public class ServiceDiscoveryResultTest {

    private static Iq discoInfo(final Element... children) {
        final var iq = new Iq(Iq.Type.RESULT);
        final var query = iq.query("http://jabber.org/protocol/disco#info");
        for (final Element child : children) {
            query.addChild(child);
        }
        return iq;
    }

    private static Element feature(final String var) {
        final var element = new Element("feature");
        if (var != null) {
            element.setAttribute("var", var);
        }
        return element;
    }

    /** A data form; {@code formType} null means no FORM_TYPE field at all. */
    private static Element form(final String formType) {
        final var x = new Element("x");
        x.setAttribute("xmlns", "jabber:x:data");
        if (formType != null) {
            final var field = x.addChild("field");
            field.setAttribute("var", "FORM_TYPE");
            field.addChild("value").setContent(formType);
        }
        return x;
    }

    /**
     * The crash this replaces: {@code getAttribute("xmlns").equals(...)} on an {@code <x/>} that
     * carries no namespace. Reachable from any entity whose caps we resolve.
     */
    @Test
    public void elementWithoutNamespaceDoesNotThrow() {
        final var x = new Element("x");
        final var result = new ServiceDiscoveryResult(discoInfo(x));
        Assert.assertNotNull(result);
    }

    @Test
    public void wellFormedDiscoInfoProducesAHash() {
        final var result =
                new ServiceDiscoveryResult(
                        discoInfo(feature("urn:xmpp:ping"), form("urn:xmpp:dataforms:softwareinfo")));
        Assert.assertNotNull("a well formed disco#info must still hash", result.ver);
    }

    @Test
    public void featureOnlyDiscoInfoProducesAHash() {
        final var result = new ServiceDiscoveryResult(discoInfo(feature("urn:xmpp:ping")));
        Assert.assertNotNull(result.ver);
    }

    /**
     * XEP-0115 §5.4: a form with no FORM_TYPE leaves the hash ambiguous, and the caps cache is
     * keyed by that hash and shared between entities. No hash means no cache entry.
     */
    @Test
    public void formWithoutFormTypeSuppressesTheHash() {
        final var result =
                new ServiceDiscoveryResult(discoInfo(feature("urn:xmpp:ping"), form(null)));
        Assert.assertNull(
                "an ambiguous disco#info must not produce a caps hash", result.ver);
    }

    @Test
    public void formWithEmptyFormTypeSuppressesTheHash() {
        final var result = new ServiceDiscoveryResult(discoInfo(form("")));
        Assert.assertNull(result.ver);
    }

    @Test
    public void featureWithoutVarIsSkippedRatherThanHashed() {
        final var result = new ServiceDiscoveryResult(discoInfo(feature(null), feature("a")));
        Assert.assertNotNull(result.ver);
        Assert.assertEquals(1, result.getFeatures().size());
    }

    /** Two documents differing only in an unhashed part must not collide on one cache key. */
    @Test
    public void differentFeaturesProduceDifferentHashes() {
        final var first = new ServiceDiscoveryResult(discoInfo(feature("urn:xmpp:ping")));
        final var second = new ServiceDiscoveryResult(discoInfo(feature("urn:xmpp:time")));
        Assert.assertNotNull(first.ver);
        Assert.assertNotNull(second.ver);
        Assert.assertFalse(java.util.Arrays.equals(first.ver, second.ver));
    }
}
