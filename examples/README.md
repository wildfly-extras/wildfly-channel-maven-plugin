# Additional Examples with Actual Data

This directory contains sample channel files to demonstrate usage of the *wildfly-channel-maven-plugin*.

## Example 1: Align the wildfly-core Project

The [sample-manifest1.yaml](sample-manifest1.yaml) channel file can be used to align dependencies in the 
Wildfly Core project.

### Preparation

Checkout the 20.0.0.Beta1 tag of the wildfly-core project (intentionally an older tag, for demonstration purposes):

```shell
git clone git@github.com:wildfly/wildfly-core.git
cd wildfly-core/
git checkout 20.0.0.Beta1
```

### Plugin Usage

Run the plugin with [sample-manifest1.yaml](sample-manifest1.yaml) as an input:

```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
  -DmanifestFile=path/to/wildfly-channel-maven-plugin/examples/sample-manifest1.yaml \
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

## Example 2: Override Transitive Dependencies

This example uses the Wildfly repository and shows how to override some of transitive dependencies inherited from Wildfly 
Core.

### Preparation

Checkout the 27.0.0.Final tag of the Wildfly project:

```shell
git clone git@github.com:wildfly/wildfly.git
cd wildfly/
git checkout 27.0.0.Final
```

### Plugin Usage

Run the plugin with [sample-manifest2.yaml](sample-manifest2.yaml) as an input:

```shell
mvn org.wildfly:wildfly-channel-maven-plugin:upgrade \
  -DmanifestFile=path/to/wildfly-channel-maven-plugin/examples/sample-manifest2.yaml
```

### Result

Following changes were made to the parent pom.xml. The transitive dependencies that needed to be upgraded were injected into the
dependency management section of the root module:

```shell
$ git diff -U1
diff --git a/pom.xml b/pom.xml
index e8ed5bbcc7..a9cb6ee7c3 100644
--- a/pom.xml
+++ b/pom.xml
@@ -318,7 +318,7 @@
         <version.com.beust>1.78</version.com.beust>
-        <version.com.carrotsearch.hppc>0.8.1</version.com.carrotsearch.hppc>
+        <version.com.carrotsearch.hppc>0.8.1.redhat-00001</version.com.carrotsearch.hppc>
         <version.org.elasticsearch.client.rest-client>7.16.3</version.org.elasticsearch.client.rest-client>
-        <version.com.fasterxml.classmate>1.5.1</version.com.fasterxml.classmate>
+        <version.com.fasterxml.classmate>1.5.1.redhat-00001</version.com.fasterxml.classmate>
         <version.com.fasterxml.jackson>2.13.4</version.com.fasterxml.jackson>
-        <version.com.fasterxml.jackson.databind>${version.com.fasterxml.jackson}.2</version.com.fasterxml.jackson.databind>
+        <version.com.fasterxml.jackson.databind>2.13.4.redhat-00001</version.com.fasterxml.jackson.databind>
         <version.com.fasterxml.jackson.jr.jackson-jr-objects>${version.com.fasterxml.jackson}</version.com.fasterxml.jackson.jr.jackson-jr-objects>
@@ -1455,2 +1455,28 @@
 
+            <dependency>
+                <groupId>org.apache.httpcomponents</groupId>
+                <artifactId>httpclient</artifactId>
+                <version>4.5.13.redhat-00002</version>
+                <exclusions>
+                    <exclusion>
+                        <groupId>commons-logging</groupId>
+                        <artifactId>commons-logging</artifactId>
+                    </exclusion>
+                    <exclusion>
+                        <groupId>commons-codec</groupId>
+                        <artifactId>commons-codec</artifactId>
+                    </exclusion>
+                </exclusions>
+            </dependency>
+            <dependency>
+                <groupId>org.jboss.remoting</groupId>
+                <artifactId>jboss-remoting</artifactId>
+                <version>5.0.27.Final-redhat-00001</version>
+                <exclusions>
+                    <exclusion>
+                        <groupId>org.wildfly.security</groupId>
+                        <artifactId>wildfly-elytron</artifactId>
+                    </exclusion>
+                </exclusions>
+            </dependency>
         </dependencies>
```