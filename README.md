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

## Goals

* [`upgrade`](#upgrade)
* [`inject-repositories`](#inject-repositories)
* [`verify-dependencies`](#verify-dependencies)

### `upgrade`

Overrides dependencies versions in a project according to given Wildfly Channel file.

Example:

`mvn org.wildfly:wildfly-channel-maven-plugin:upgrade -DmanifestFile=manifest.yaml`

#### Parameters

Channel location parameters:

* `channelFile`: Path to a Wildfly Channel file on a local filesystem.
* `channelGAV`: Alternative to above, the channel file would be obtained from a maven repo.
* `manifestFile`: Path to a Wildfly Channel manifest file on a local filesystem.
* `manifestGAV`: Alternative to above, the manifest file would be obtained from a maven repo.
* `remoteRepositories`: Comma delimited list of remote repositories used by the channel session.

Additional configuration - all of these are optional:

* `localRepository`: Local maven repository path. Defaults to `~/.m2/repository`.
* `ignoreStreams`: Comma delimited list of "groupId:artifactId" strings (can be also "groupId:*"), representing
  dependencies that should not be modified.
* `ignoreProperties`: Comma delimited list of property names in the project that should not be modified.
* `ignorePropertiesPrefixedWith`: Comma delimited list of property name prefixes. Properties starting with one of these
  prefixes should not be modified.
* `ignoreModules`: Comma delimited list of "groupId:artifactId" strings, representing project submodules that should not
  be processed (no dependencies or properties in given modules will be modified).
* `ignoreTestDependencies`: If true, dependencies that are only in the test scope will not be upgraded. True by default.
* `injectTransitiveDependencies`: If true, transitive dependencies are upgraded too, by injecting new declarations into
  the \<dependencyManagement\> section.
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

### `inject-repositories`

Extracts repositories from given channel file, and adds these repositories to the project POM. The project build should
then have access to all the repositories that the channel file references. 

Example:

`mvn org.wildfly:wildfly-channel-maven-plugin:inject-repositories -DfromChannelFile=channel.yaml`

#### Parameters

* `fromChannelFile`: Channel file to extract repositories from.

### `verify-dependencies`

Checks that all project dependencies are aligned with specified channel, otherwise fails the build.

#### Parameters

Channel location parameters:

* `channelFile`: Path to a Wildfly Channel file on a local filesystem.
* `channelGAV`: Alternative to above, the channel file would be obtained from a maven repo.
* `manifestFile`: Path to a Wildfly Channel manifest file on a local filesystem.
* `manifestGAV`: Alternative to above, the manifest file would be obtained from a maven repo.
* `remoteRepositories`: Comma delimited list of remote repositories used by the channel session.

Other parameters:

* `failBuild`: Should the build fail in case unaligned dependencies are found? Defaults to true.
* `failWhenStreamNotFound`: Should the build fail in case when dependencies are found that are not represented in 
  specified channels? Defaults to false.
* `ignoreStreams`: Comma delimited list of dependencies GAs that should be ignored - build will not fail even if these
  are unaligned.
* `ignoreScopes`: Comma delimited list of scopes that should be ignored, meaning dependencies belonging to given scopes
  will not be checked. Defaults to "test".

Example:

```shell
mvn clean org.wildfly:wildfly-channel-maven-plugin:1.0.13-SNAPSHOT:verify-dependencies \
    -DmanifestGAV=org.jboss.eap.channels:eap-xp-5.0
```
  
## Static Configuration

Configuration parameters can be stored in a file `.wildfly-channel-maven-plugin` located in the root of the project 
that's being processed. The file should be formatted so that single configuration parameter is put on a line. The
parameters are then going to be combined with command line provided parameters. 

For instance, to simplify a plugin call like this:

```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
  -DmanifestFile=path/to/manifest.yaml \
  -DignoreModules=groupId:artifactId \
  -DignoreProperties=property1,property2
```

you can create the `.wildfly-channel-maven-plugin` file in your project with following content:

```shell
-DignoreModules=groupId:artifactId
-DignoreProperties=property1,property2
```

and then just call the following command to achieve the same result as the original command:


```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
  -DmanifestFile=path/to/manifest.yaml
```

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

## Developing This Plugin

### Debugging Integration Tests

The [maven-it-extension](https://github.com/khmarbaise/maven-it-extension) is used for integration testing of this maven
plugin.

Set the `ITF_DEBUG` system property to remotely debug integration tests, e.g.:

```shell
mvn verify -f integration-tests/ -DITF_DEBUG
```

Then connect to the 8000 port after you see the "Running x.y.z.TestName" message:

```shell

[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running org.wildfly.channelplugin.it.UpgradeComponentsMojoIT
# Now connect with a debugger.
```
