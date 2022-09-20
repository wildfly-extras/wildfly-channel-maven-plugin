# Wildfly Channel Maven Plugin

This plugin overrides dependency versions in a Maven project according to provided Wildfly Channel definition.

The plugin has ability to modify dependency versions directly defined in one of project's submodules. Dependencies can
be located in either the "dependencyManagement" or "dependencies" sections. It can handle dependencies where the version
is inlined directly in the version element, or referenced via a property.

It is not possible to override dependencies or properties inheritted from parents outside the project. As well, it is
not possible to override transitively inheritted dependencies.

### Goals

* `upgrade`: Overrides dependencies version in the project according to given channel file.

### Configuration Parameters

* `channelFile`: Path to a channel file on a local filesystem.
* `channelGAV`: Alternative to above, the channel file would be obtained from a maven repo.
* `localRepository`: Local maven repository path. Defaults to `~/.m2/repository`.
* `remoteRepositories`: Comma delimited list of remote repositories, which will be used for resolution of available 
  component versions. This is only needed when working with channel containing version patterns.
* `ignoreStreams`: Comma delimited list of "groupId:artifactId" strings, representing dependencies that should not be 
   modified.
* `ignoreProperties`: Comma delimited list of property names in the project that should not be modified.
* `ignorePropertiesPrefixedWith`: Comma delimited list of property name prefixes. Properties starting with one of these
  prefixes should not be modified.
* `ignoreModules`: Comma delimited list of "groupId:artifactId" strings, representing project submodules that should not
  be processed (no dependencies or properties in given modules will be modified).
* `overrideProperties`: Comma delimited list of "propertyName=newValue" strings, meaning that diven properties (in any 
  project module) should be overridden to given values. This takes preference over modifications inferred from the 
  channel.
* `overrideDependencies`: Comma delimited list of "groupId:artifactId:newVersion" strings, representing dependency 
  versions overrides. All dependencies with given groupId and artifactId in all project submodules will be overridden to
  given versions. The version will be inlined in the version element. This takes preference over modifications inferred
  from the channel.
* `disableTlsVerification`: If set to true, TLS certificates of maven repositories will not be validated.
* `writeRecordedChannel`: Should a "recorded channel" be written to `target/recorder-channel.yaml`? Default is 'true'.
  ("Recorded channel" contains a subset of streams from the input channel that were detected in the project which
  the plugin is applied to, and the streams are always resolved to specific versions.)
<!--
* `injectMissingDependencies`: Inject all streams from the channel, that weren't already present in the POM file, as
  new managed dependencies. The dependency management section must already exist. This is very experimental, the point
  is to allow overriding transitive dependencies. If we wanted to have this, the final implementation should take 
  the real dependency tree into account, when figuring out which dependencies to inject.
-->

## Usage Examples

Simple use case, allign all dependencies according to a channel file:

```shell
# navigate to a maven project
cd some-maven-project/

# execute the plugin
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
   -DchannelFile=<path/to/channel.yaml>

# review changes
git diff
```

More complex use case - also overrides the "version.org.wildfly.core" property, suppresses any changes in properties
that start with the "legacy." string, and completely leaves out the "org.jboss.eap:wildfly-legacy-ee-bom" submodule
from the processing:

```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
   -DchannelFile=<path/to/channel.yaml> \
   -DoverrideProperties=version.org.wildfly.core=19.0.0.Beta16-redhat-00001 \
   -DignorePropertiesPrefixedWith=legacy. \
   -DignoreModules=org.jboss.eap:wildfly-legacy-ee-bom
```
