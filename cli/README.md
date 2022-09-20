# Wildfly Channel CLI Tool

This is an experimental CLI tool implementing additional commands on top of the wildfly-channel library.

## Commands

* `resolve-channel`: Takes a channel file containing version patterns, and turns it into a channel file containing
  concrete versions.
* `generate-bom`: Takes all dependencies defined in a project pom.xml file, and generates a BOM file.

## Usage Example

```shell
java -jar wildfly-channel-tools-cli-1.0-SNAPSHOT-runnable.jar resolve-channel \
     --channel-file eap-74-proposed-7.4-channel.yaml \
     -o resolved-channel.yaml \
     --remote-repo <maven repository url>
```
