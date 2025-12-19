package eu.siacs.conversations.utils;

import org.junit.Assert;
import org.junit.Test;

public class MimeUtilsTest {

    @Test
    public void wasmMimeType() {
        Assert.assertEquals(
                "application/wasm", MimeUtils.guessMimeTypeFromExtension("wasm"));
    }

    @Test
    public void wasmExtension() {
        Assert.assertEquals("wasm", MimeUtils.guessExtensionFromMimeType("application/wasm"));
    }
}
