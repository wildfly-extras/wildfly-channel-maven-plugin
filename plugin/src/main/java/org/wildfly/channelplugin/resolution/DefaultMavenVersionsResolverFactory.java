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

package org.wildfly.channelplugin.resolution;

import java.util.List;

import org.wildfly.channel.spi.MavenVersionsResolver;

public class DefaultMavenVersionsResolverFactory implements MavenVersionsResolver.Factory {
    private final List<String> repositoryUrls;
    private final String localRepositoryPath;
    private final boolean disableTlsVerification;

    public DefaultMavenVersionsResolverFactory(List<String> repositoryUrls, String localRepositoryPath,
            boolean disableTlsVerification) {
        this.repositoryUrls = repositoryUrls;
        this.localRepositoryPath = localRepositoryPath;
        this.disableTlsVerification = disableTlsVerification;
    }

    @Override
    public MavenVersionsResolver create() {
        return new DefaultMavenVersionsResolver(repositoryUrls, localRepositoryPath, disableTlsVerification);
    }
}
