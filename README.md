# Wildfly Channel Tools

This repository contains set of experimental tools related to the 
[wildfly-channel](https://github.com/wildfly-extras/wildfly-channel) library. Namely:

* [Wildfly Channel Maven Plugin](#plugin),
* [Wildfly Channel CLI Tool](#cli).

## <a name="plugin"></a> Wildfly Channel Maven Plugin

This plugin overrides dependency versions of a Maven project according to provided Wildfly Channel definition.

### Usage Example

(Currently, the project is not released, so you need to `mvn install` it locally...) 

```shell
# navigate to a maven project
cd some-maven-project/

# execute the plugin
mvn org.wildfly:wildfly-channel-maven-plugin:1.0-SNAPSHOT:upgrade \
  -DchannelFile=<path/to/channel.yaml> \
  -DremoteRepositories=<brew-repo-url1>[,<brew-repo-url2>...]

# review changes
git diff
```

### Goals

* `upgrade`: Overrides dependencies version in the project according to given channel file.

### Configuration Parameters

* `channelFile`: Path to a channel file on a local filesystem.
* `channelGAV`: Alternative to above, the channel file would be obtained from a maven repo. (Not yet implemented.) 
* `remoteRepositories`: Comma delimited list of remote repositories, which will be used for resolution of available 
  component versions. This resolution happens only if the channel contains stream with version patterns.
* `writeRecordedChannel`: Should a recorded channel be written to `target/recorder-channel.yaml`? Default is 'true'.
* `injectMissingDependencies`: Inject all streams from the channel, that weren't already present in the POM file, as
  new managed dependencies. The dependency management section must already exist. This is very experimental, the point
  is to allow overriding transitive dependencies. If we wanted to have this, the final implementation should take 
  the real dependency tree into account, when figuring out which dependencies to inject.
* `disableTlsVerification`: If true, TLS certificates of maven repositories are not validated.
* `ignoreGAs`: Comma delimited list of dependency GAs that should not be upgraded in the project.
* `processRootOnly`: If true (default), only dependencies in a root module are upgraded, submodules are not.

## <a name="cli"></a> Wildfly Channel CLI Tool

### Commands

* `resolve-channel`: Takes a channel file containing version patterns, and turns it into a channel file containing
  concrete versions.
* `generate-bom`: Takes all dependencies defined in a project pom.xml file, and generates a BOM file.

### Usage Example

```shell
java -jar wildfly-channel-tools-cli-1.0-SNAPSHOT-runnable.jar resolve-channel \
     --channel-file eap-74-proposed-7.4-channel.yaml \
     -o resolved-channel.yaml \
     --remote-repo <maven repository url>
```
