# Additional Examples with Actual Data

This directory contains sample channel files to demonstrate usage of the *wildfly-channel-maven-plugin*.

## Example: Align the wildfly-core Project

The [wildfly-core-channel.yaml](wildfly-core-channel.yaml) channel file can be used to align dependencies in the 
wildfly-core project. The channel contains dependency versions from wildfly-core version 20.0.0.Beta3.

### Preparation

Checkout the 20.0.0.Beta1 tag of the wildfly-core project (intentionally an older tag, for demonstration purposes):

```shell
git clone git@github.com:wildfly/wildfly-core.git
cd wildfly-core/
git checkout 20.0.0.Beta1
```

### Plugin Usage

```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
  -DchannelFile=path/to/wildfly-channel-maven-plugin/examples/wildfly-core-channel.yaml \
  -DignorePropertiesPrefixedWith=legacy.
```

### Result

Following changes were made to the parent pom.xml (no other POMs were modified in this case):

```shell
$ git diff -U1
diff --git a/pom.xml b/pom.xml
index ab87421067..5672c32d23 100644
--- a/pom.xml
+++ b/pom.xml
@@ -198,3 +198,3 @@
         <version.commons-lang3>3.12.0</version.commons-lang3>
-        <version.io.smallrye.jandex>3.0.1</version.io.smallrye.jandex>
+        <version.io.smallrye.jandex>3.0.3</version.io.smallrye.jandex>
         <version.io.undertow>2.3.0.Final</version.io.undertow>
@@ -221,3 +221,3 @@
         <version.org.apache.sshd>2.8.0</version.org.apache.sshd>
-        <version.org.bouncycastle>1.72</version.org.bouncycastle>
+        <version.org.bouncycastle>1.72.1</version.org.bouncycastle>
         <version.org.codehaus.plexus.plexus-utils>3.1.1</version.org.codehaus.plexus.plexus-utils>
@@ -228,3 +228,3 @@
         <version.org.jboss.byteman>4.0.19</version.org.jboss.byteman>
-        <version.org.jboss.classfilewriter>1.2.5.Final</version.org.jboss.classfilewriter>
+        <version.org.jboss.classfilewriter>1.3.0.Final</version.org.jboss.classfilewriter>
         <version.org.jboss.invocation>1.7.0.Final</version.org.jboss.invocation>
@@ -240,3 +240,3 @@
         <version.org.jboss.modules.jboss-modules>2.1.0.Final</version.org.jboss.modules.jboss-modules>
-        <version.org.jboss.msc.jboss-msc>1.5.0.Beta3</version.org.jboss.msc.jboss-msc>
+        <version.org.jboss.msc.jboss-msc>1.5.0.Beta4</version.org.jboss.msc.jboss-msc>
         <version.org.jboss.remoting>5.0.26.Final</version.org.jboss.remoting>
@@ -1308,3 +1308,3 @@
                 <artifactId>bcpkix-jdk18on</artifactId>
-                <version>${version.org.bouncycastle}</version>
+                <version>1.72</version>
                 <exclusions>
@@ -1319,3 +1319,3 @@
                 <artifactId>bcprov-jdk18on</artifactId>
-                <version>${version.org.bouncycastle}</version>
+                <version>1.72</version>
             </dependency>
@@ -1324,3 +1324,3 @@
                 <artifactId>bcutil-jdk18on</artifactId>
-                <version>${version.org.bouncycastle}</version>
+                <version>1.72</version>
             </dependency>
```
