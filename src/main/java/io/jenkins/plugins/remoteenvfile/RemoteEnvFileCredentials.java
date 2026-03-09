package io.jenkins.plugins.remoteenvfile;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

final class RemoteEnvFileCredentials {

    private RemoteEnvFileCredentials() {
    }

    static ListBoxModel fillCredentialsIdItems(Item item, String credentialsId, String sourceUrl) {
        if (item == null || !item.hasPermission(Item.CONFIGURE)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }

        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        item instanceof Queue.Task ? Tasks.getAuthenticationOf2((Queue.Task) item) : ACL.SYSTEM2,
                        item,
                        StandardCredentials.class,
                        requirementsFor(sourceUrl),
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StringCredentials.class),
                                CredentialsMatchers.instanceOf(UsernamePasswordCredentials.class)))
                .includeCurrentValue(credentialsId);
    }

    private static List<DomainRequirement> requirementsFor(String sourceUrl) {
        String trimmed = Util.fixEmptyAndTrim(sourceUrl);
        if (trimmed == null) {
            return Collections.emptyList();
        }

        try {
            return URIRequirementBuilder.fromUri(trimmed).build();
        } catch (IllegalArgumentException exception) {
            return Collections.emptyList();
        }
    }
}
