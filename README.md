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
