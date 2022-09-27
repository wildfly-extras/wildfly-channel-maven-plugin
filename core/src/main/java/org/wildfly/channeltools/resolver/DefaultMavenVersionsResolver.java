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

package org.wildfly.channeltools.resolver;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.ssl.SSLContexts;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.AuthenticationDigest;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.wagon.WagonTransporterFactory;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.version.Version;
import org.jboss.logging.Logger;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.spi.MavenVersionsResolver;

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

public class DefaultMavenVersionsResolver implements MavenVersionsResolver {

    public static final Logger logger = Logger.getLogger(DefaultMavenVersionsResolver.class);

    private static final File NULL_FILE = new File("/dev/null");
    private static final String LOCAL_MAVEN_REPO = System.getProperty("user.home") + "/.m2/repository";

    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> remoteRepositories;
    private final String localRepositoryPath;
    private final boolean disableTlsVerification;

    DefaultMavenVersionsResolver(List<String> remoteRepositoryUrls, String localRepositoryPath, boolean disableTlsVerification) {
        this.disableTlsVerification = disableTlsVerification;
        this.localRepositoryPath = Objects.requireNonNullElse(localRepositoryPath, LOCAL_MAVEN_REPO);
        this.remoteRepositories = new ArrayList<>(remoteRepositoryUrls.size());
        for (int i = 0; i < remoteRepositoryUrls.size(); i++) {
            logger.debugf("Adding remote repository %s", remoteRepositoryUrls.get(i));

            // hack to disable TLS verification
            SSLContext sslcontext;
            try {
                sslcontext = SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
            } catch (GeneralSecurityException e) {
                throw new RuntimeException("Couldn't build SSLContext", e);
            }

            RemoteRepository.Builder remoteRepositoryBuilder = new RemoteRepository.Builder("repo-" + i, "default",
                    remoteRepositoryUrls.get(i));
            if (this.disableTlsVerification) {
                remoteRepositoryBuilder.setAuthentication(new AuthenticationBuilder()
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
                        }).build());
            }
            remoteRepositories.add(remoteRepositoryBuilder.build());
        }

        system = newRepositorySystem();
        session = newRepositorySystemSession();
    }

    @Override
    public Set<String> getAllVersions(String groupId, String artifactId, String extension, String classifier) {
        requireNonNull(groupId);
        requireNonNull(artifactId);
        logger.debugf("Resolving the latest version of %s:%s in repositories: %s", groupId, artifactId,
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
            reportExceptions(versionRangeResult);
            logger.debugf("All versions in the repositories: %s", versions);
            return versions;
        } catch (VersionRangeResolutionException e) {
            return emptySet();
        }
    }

    @Override
    public File resolveArtifact(String groupId, String artifactId, String extension, String classifier,
            String version) {
        // artifact file is not needed, but returning null is not allowed ATM
        return NULL_FILE;
    }

    @Override
    public List<File> resolveArtifacts(List<ArtifactCoordinate> list) throws UnresolvedMavenArtifactException {
        throw new NotImplementedException("Not implemented");
    }

    private static void reportExceptions(VersionRangeResult versionRangeResult) {
        // report all exceptions that are not MetadataNotFoundException, metadata are always missing in local
        // repositories
        if (versionRangeResult.getExceptions() != null) {
            List<Exception> exceptions = versionRangeResult.getExceptions()
                    .stream()
                    .filter(e -> !(e instanceof MetadataNotFoundException))
                    .collect(Collectors.toList());
            if (exceptions.size() > 0) {
                Artifact artifact = versionRangeResult.getRequest().getArtifact();
                logger.warnf("Error when resolving %s:%s versions, printing exceptions bellow:",
                        artifact.getGroupId(), artifact.getArtifactId());
                for (Exception e : exceptions) {
                    logger.warn(e);
                }
            }
        }
    }

    private DefaultRepositorySystemSession newRepositorySystemSession() {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(this.localRepositoryPath);
        session.setLocalRepositoryManager(this.system.newLocalRepositoryManager(session, localRepo));
        session.setOffline(false);
        return session;
    }

    public RepositorySystem newRepositorySystem() {
        final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, WagonTransporterFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                throw new RuntimeException("Failed to initiate maven repository system");
            }
        });
        return locator.getService(RepositorySystem.class);
    }

}
