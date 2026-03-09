package io.jenkins.plugins.remoteenvfile;

import hudson.AbortException;
import java.io.IOException;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class DotenvParserTest {

    @Test
    public void parsesSupportedDotenvSyntax() throws Exception {
        Map<String, String> values = DotenvParser.parse(
                "\uFEFF# comment\n"
                        + "FOO=bar\n"
                        + "EMPTY=\n"
                        + "SPACED = value with spaces\n"
                        + "SINGLE='single quoted'\n"
                        + "DOUBLE=\"line\\nvalue\"\n");

        Assert.assertEquals("bar", values.get("FOO"));
        Assert.assertEquals("", values.get("EMPTY"));
        Assert.assertEquals("value with spaces", values.get("SPACED"));
        Assert.assertEquals("single quoted", values.get("SINGLE"));
        Assert.assertEquals("line\nvalue", values.get("DOUBLE"));
    }

    @Test
    public void rejectsDuplicateKeys() {
        AbortException exception = parseFailure("FOO=one\nFOO=two\n");
        Assert.assertTrue(exception.getMessage().contains("duplicate variable 'FOO'"));
    }

    @Test
    public void rejectsMalformedKeys() {
        AbortException exception = parseFailure("1BAD=value\n");
        Assert.assertTrue(exception.getMessage().contains("invalid variable name"));
    }

    @Test
    public void rejectsInterpolation() {
        AbortException exception = parseFailure("FOO=${BAR}\n");
        Assert.assertTrue(exception.getMessage().contains("interpolation"));
    }

    @Test
    public void rejectsExportPrefix() {
        AbortException exception = parseFailure("export FOO=bar\n");
        Assert.assertTrue(exception.getMessage().contains("export prefixes"));
    }

    @Test
    public void rejectsUnsupportedEscapeSequences() {
        AbortException exception = parseFailure("FOO=\"bad\\xvalue\"\n");
        Assert.assertTrue(exception.getMessage().contains("unsupported escape sequence"));
    }

    private AbortException parseFailure(String content) {
        try {
            DotenvParser.parse(content);
            Assert.fail("Expected parsing to fail");
            return null;
        } catch (AbortException exception) {
            return exception;
        } catch (IOException exception) {
            throw new AssertionError("Unexpected IOException", exception);
        }
    }
}
