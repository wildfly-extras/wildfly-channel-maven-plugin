/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.channelplugin.prospero;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;

import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.ssl.SSLContexts;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.AuthenticationDigest;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.version.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.channel.spi.MavenVersionsResolver;

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

public class WfChannelMavenResolver implements MavenVersionsResolver {

    public static final Logger logger = LoggerFactory.getLogger(WfChannelMavenResolver.class);

    private static final File NULL_FILE = new File("/dev/null");

    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;

    private final List<RemoteRepository> remoteRepositories;

    private final MavenSessionManager mavenSessionManager;

    WfChannelMavenResolver(MavenSessionManager mavenSessionManager, List<String> repositoryUrls) {
        this.mavenSessionManager = mavenSessionManager;
        remoteRepositories = new ArrayList<>(repositoryUrls.size());
        for (int i = 0; i < repositoryUrls.size(); i++) {
            logger.info("Adding remote repository {}", repositoryUrls.get(i));

            // hack to disable TLS verification
            SSLContext sslcontext;
            try {
                sslcontext = SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
            } catch (GeneralSecurityException e) {
                throw new RuntimeException("Couldn't build SSLContext", e);
            }

            remoteRepositories.add(new RemoteRepository.Builder("repo-" + i, "default", repositoryUrls.get(i))
                    .setAuthentication(new AuthenticationBuilder()
                            .addHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                            .addCustom(new Authentication() {
                                @Override
                                public void fill(AuthenticationContext context, String key, Map<String, String> data) {
                                    context.put(AuthenticationContext.SSL_CONTEXT, sslcontext);
                                }

                                @Override
                                public void digest(AuthenticationDigest digest) {
                                    digest.update(AuthenticationContext.SSL_CONTEXT, sslcontext.getClass().getName());
                                }
                            })
                            .build())
                    .build());
        }

        system = mavenSessionManager.newRepositorySystem();
        session = mavenSessionManager.newRepositorySystemSession(system, false);
    }

    @Override
    public Set<String> getAllVersions(String groupId, String artifactId, String extension, String classifier) {
        requireNonNull(groupId);
        requireNonNull(artifactId);
        logger.trace("Resolving the latest version of {}:{} in repositories: {}",
                groupId, artifactId,
                remoteRepositories.stream().map(RemoteRepository::getUrl).collect(Collectors.joining(",")));

        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, extension, "[0,)");
        VersionRangeRequest versionRangeRequest = new VersionRangeRequest();
        versionRangeRequest.setArtifact(artifact);
        versionRangeRequest.setRepositories(remoteRepositories);

        try {
            VersionRangeResult versionRangeResult = system.resolveVersionRange(session, versionRangeRequest);
            Set<String> versions = versionRangeResult.getVersions()
                    .stream()
                    .map(Version::toString)
                    .collect(Collectors.toSet());
            if (versionRangeResult.getExceptions().size() > 0) {
                logger.warn("Error when resolving {}:{} versions, printing exceptions bellow:", groupId, artifact);
                for (Exception e : versionRangeResult.getExceptions()) {
                    logger.warn("", e);
                }
            }
            logger.trace("All versions in the repositories: {}", versions);
            return versions;
        } catch (VersionRangeResolutionException e) {
            return emptySet();
        }
    }

    @Override
    public File resolveLatestVersionFromMavenMetadata(String groupId, String artifactId, String extension,
            String classifier) {
        // artifact file is not needed
        return null;
    }

    @Override
    public File resolveArtifact(String groupId, String artifactId, String extension, String classifier,
            String version) {
        // artifact file is not needed, but returning null is not allowed ATM
        return NULL_FILE;
    }

}
