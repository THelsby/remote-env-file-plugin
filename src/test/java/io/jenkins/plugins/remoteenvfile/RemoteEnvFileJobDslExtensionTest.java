package io.jenkins.plugins.remoteenvfile;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class RemoteEnvFileJobDslExtensionTest {

    @Test
    public void buildsOrderedSourcesFromHelperAndNestedStyles() {
        RemoteEnvFilesJobDslContext context = new RemoteEnvFilesJobDslContext();
        context.source("https://example.com/base.env");
        context.source("https://example.com/secure.env", "fixture-bearer");

        RemoteEnvSourceJobDslContext nested = new RemoteEnvSourceJobDslContext();
        nested.sourceUrl("https://example.com/prod.env");
        nested.credentialsId("fixture-basic");
        context.source(nested);

        List<RemoteEnvSource> sources = context.buildSources();
        Assert.assertEquals(3, sources.size());
        Assert.assertEquals("https://example.com/base.env", sources.get(0).getSourceUrl());
        Assert.assertNull(sources.get(0).getCredentialsId());
        Assert.assertEquals("https://example.com/secure.env", sources.get(1).getSourceUrl());
        Assert.assertEquals("fixture-bearer", sources.get(1).getCredentialsId());
        Assert.assertEquals("https://example.com/prod.env", sources.get(2).getSourceUrl());
        Assert.assertEquals("fixture-basic", sources.get(2).getCredentialsId());
    }

    @Test
    public void buildsWrapperFromConfiguredSources() {
        RemoteEnvFilesJobDslContext context = new RemoteEnvFilesJobDslContext();
        context.source("https://example.com/base.env");
        context.source("https://example.com/prod.env", "fixture-basic");

        RemoteEnvFileBuildWrapper wrapper = RemoteEnvFileWrapperJobDslExtension.buildWrapper(context);

        Assert.assertEquals(2, wrapper.getSources().size());
        Assert.assertEquals("https://example.com/base.env", wrapper.getSources().get(0).getSourceUrl());
        Assert.assertEquals("fixture-basic", wrapper.getSources().get(1).getCredentialsId());
    }

    @Test
    public void buildsJobPropertyFromConfiguredSources() {
        RemoteEnvFilesJobDslContext context = new RemoteEnvFilesJobDslContext();
        RemoteEnvSourceJobDslContext nested = new RemoteEnvSourceJobDslContext();
        nested.sourceUrl("https://example.com/base.env");
        context.source(nested);
        context.source("https://example.com/prod.env");

        RemoteEnvFileJobProperty property = RemoteEnvFilePropertyJobDslExtension.buildProperty(context);

        Assert.assertEquals(2, property.getSources().size());
        Assert.assertEquals("https://example.com/base.env", property.getSources().get(0).getSourceUrl());
        Assert.assertEquals("https://example.com/prod.env", property.getSources().get(1).getSourceUrl());
    }

    @Test
    public void rejectsEmptySourceConfiguration() {
        RemoteEnvFilesJobDslContext context = new RemoteEnvFilesJobDslContext();

        IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class, context::buildSources);
        Assert.assertEquals("At least one remote source is required", exception.getMessage());
    }

    @Test
    public void rejectsNestedSourcesWithoutUrl() {
        RemoteEnvSourceJobDslContext context = new RemoteEnvSourceJobDslContext();

        IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class, context::build);
        Assert.assertEquals("A remote source URL is required", exception.getMessage());
    }
}
