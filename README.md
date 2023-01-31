# Wildfly Channel Maven Plugin

This plugin overrides dependency versions in a Maven project according to provided 
[Wildfly Channel](https://github.com/wildfly-extras/wildfly-channel) definition.

The plugin has the following abilities:

* Modify versions of dependencies defined in both the project root module and project submodules.
* Dependencies to modify can be defined in both the "dependencyManagement" and the "dependencies" sections of the 
  `pom.xml` file.
* Dependency version strings that should be modified can be inlined directly in the `<dependency>/<version>` element, 
  or referenced via a property.

The plugin is not able to align dependencies when:

* A dependency is inherited from a parent `pom.xml` which is not part of the target project structure.
* A dependency version property is inherited from a parent `pom.xml` which is not part of the target project structure.

### Goals

* `upgrade`: Overrides dependencies versions in a project according to given Wildfly Channel file.

### Configuration Parameters

* `channelFile`: Path to a Wildfly Channel file on a local filesystem.
* `channelGAV`: Alternative to above, the channel file would be obtained from a maven repo.
* `manifestFile`: Path to a Wildfly Channel manifest file on a local filesystem.
* `manifestGAV`: Alternative to above, the manifest file would be obtained from a maven repo.
* `localRepository`: Local maven repository path. Defaults to `~/.m2/repository`.
* `remoteRepositories`: Comma delimited list of remote repositories, which will be used for resolution of available 
  dependency versions. This is only needed when working with channel containing version patterns (final dependency 
  version is determined dynamically according to what versions are available in given remote Maven repositories).
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
<!--
* `injectMissingDependencies`: Inject all streams from the channel, that weren't already present in the POM file, as
  new managed dependencies. The dependency management section must already exist. This is very experimental, the point
  is to allow overriding transitive dependencies. If we wanted to have this, the final implementation should take 
  the real dependency tree into account, when figuring out which dependencies to inject.
-->

## Usage Examples

(See additional examples, including sample files, in the [examples](examples/README.md) directory.)

### Example 1

Align all dependencies according to a channel file:

```shell
# navigate to a maven project
cd some-maven-project/

# execute the plugin
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
   -DchannelFile=<path/to/channel.yaml>

# review changes
git diff
```

### Example 2

More complex use case:

* override the "version.dep1" property to "1.0.2",
* suppress any changes in properties that start with the "legacy." string,
* do not process the "project.group:submodule1" submodule (no changes will be made to that `pom.xml`),
* align all remaining dependencies, not affected by above rules, to versions defined in given channel file.

```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
   -DchannelFile=<path/to/channel.yaml> \
   -DoverrideProperties=version.dep1=1.0.2 \
   -DignorePropertiesPrefixedWith=legacy. \
   -DignoreModules=project.group:submodule1
```
