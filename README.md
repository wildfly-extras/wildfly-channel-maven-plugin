# Wildfly Channel Maven Plugin

This plugin overrides dependency versions of a Maven project according to provided Wildfly Channel definition.

## Usage

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

## Goals

* `upgrade`: Overrides dependencies version in the project according to given channel file.

## Configuration Parameters

* `channelFile`: Path to a channel file on a local filesystem.
* `channelGAV`: Alternative to above, the channel file would be obtained from a maven repo. (Not yet implemented.) 
* `remoteRepositories`: Comma delimited list of remote repositories, which will be used for resolution of available 
  component versions. This resolution happens only if the channel contains stream with version patterns.
* `writeRecordedChannel`: Should a recorded channel be written to `target/recorder-channel.yaml`? Default is 'true'.
* `injectMissingDependencies`: Inject all streams from the channel, that weren't already present in the POM file, as
  new managed dependencies. The dependency management section must already exist. This is very experimental, the point
  is to allow overriding transitive dependencies. If we wanted to have this, the final implementation should take 
  the real dependency tree into account, when figuring out which dependencies to inject.