# HAPI-FHIR Starter Project

This project is a complete starter project you can use to deploy a FHIR server using HAPI FHIR JPA.

Note that this project is specifically intended for end users of the HAPI FHIR JPA server module (in other words, it helps you implement HAPI FHIR, it is not the source of the library itself). If you are looking for the main HAPI FHIR project, see here: https://github.com/hapifhir/hapi-fhir

While this project shows how you can use many parts of the HAPI FHIR framework there are a set of features which you should be aware of are missing or something you need to supply yourself or get professional support ahead of using it directly in production:

1) The service comes with no security implementation. See how it can be done [here](https://hapifhir.io/hapi-fhir/docs/security/introduction.html)
2) The service comes with no enterprise logging. See how it can be done [here](https://hapifhir.io/hapi-fhir/docs/security/balp_interceptor.html)
3) The internal topic cache used by subscriptions in HAPI FHIR are not shared across multiple instances as the [default supplied implementation is in-mem](https://github.com/hapifhir/hapi-fhir/blob/master/hapi-fhir-jpaserver-subscription/src/main/java/ca/uhn/fhir/jpa/topic/ActiveSubscriptionTopicCache.java)
4) The internal message broker channel in HAPI FHIR is not shared across multiple instances as the [default supplied implementation is in-mem](https://github.com/hapifhir/hapi-fhir/blob/master/hapi-fhir-storage/src/main/java/ca/uhn/fhir/jpa/subscription/channel/api/IChannelFactory.java). This impacts the use of modules listed [here](https://smilecdr.com/docs/installation/message_broker.html#modules-dependent-on-message-brokers)

Need Help? Please see: https://github.com/hapifhir/hapi-fhir/wiki/Getting-Help

## Prerequisites

In order to use this sample, you should have:

- [This project](https://github.com/hapifhir/hapi-fhir-jpaserver-starter) checked out. You may wish to create a GitHub Fork of the project and check that out instead so that you can customize the project and save the results to GitHub.

### and either
 - Oracle Java (JDK) installed: Minimum JDK17 or newer.
 - Apache Maven build tool (newest version)

### or
 - Docker, as the entire project can be built using multistage docker (with both JDK and maven wrapped in docker) or used directly from [Docker Hub](https://hub.docker.com/r/hapiproject/hapi)

## Running via [Docker Hub](https://hub.docker.com/r/hapiproject/hapi)

Each tagged/released version of `hapi-fhir-jpaserver` is built as a Docker image and published to Docker hub. To run the published Docker image from DockerHub:

```
docker pull hapiproject/hapi:latest
docker run -p 8080:8080 hapiproject/hapi:latest
```

This will run the docker image with the default configuration, mapping port 8080 from the container to port 8080 in the host. Once running, you can access `http://localhost:8080/` in the browser to access the HAPI FHIR server's UI or use `http://localhost:8080/fhir/` as the base URL for your REST requests.

If you change the mapped port, you need to change the configuration used by HAPI to have the correct `hapi.fhir.tester` property/value.

### Configuration via environment variables

You can customize HAPI directly from the `run` command using environment variables. For example:

```
docker run -p 8080:8080 -e hapi.fhir.default_encoding=xml hapiproject/hapi:latest
```

HAPI looks in the environment variables for properties in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file for defaults.

### Binary storage configuration

To stream large `Binary` payloads to disk instead of the database, configure the starter with filesystem storage properties:

```
hapi:
  fhir:
    binary_storage_enabled: true
    binary_storage_mode: FILESYSTEM
    binary_storage_filesystem_base_directory: /binstore
    # inline_resource_storage_below_size: 131072   # optional override
```

When `binary_storage_mode` is set to `FILESYSTEM` and `inline_resource_storage_below_size` is omitted, the starter automatically applies a 102400 byte (100 KB) inline threshold so smaller payloads remain in the database. Ensure the directory you point to is writable by the process (for Docker builds, mount it into the container with appropriate permissions).

### Configuration via overridden application.yaml file and using Docker

You can customize HAPI by telling HAPI to look for the configuration file in a different location, e.g.:

```
docker run -p 8090:8080 -v $(pwd)/yourLocalFolder:/configs -e "--spring.config.location=file:///configs/another.application.yaml" hapiproject/hapi:latest
```
Here, the configuration file (*another.application.yaml*) is placed locally in the folder *yourLocalFolder*.

```
docker run -p 8090:8080 -e "--spring.config.location=classpath:/another.application.yaml" hapiproject/hapi:latest
```
Here, the configuration file (*another.application.yaml*) is part of the compiled set of resources.

### Configuration with additional override files

You can layer additional configuration files on top of the default application.yaml while preserving all the base settings. This approach allows you to create specific override files for different environments without duplicating the entire configuration.

```bash
# Using Maven
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.config.additional-location=classpath:your-overrides.yaml"

# Using Docker
docker run -p 8080:8080 -e "--spring.config.additional-location=classpath:your-overrides.yaml" hapiproject/hapi:latest
```

Here, the additional configuration file (*your-overrides.yaml*) contains only the specific properties you want to override or add, while all default values from application.yaml remain unchanged.

### One-liner for quickly getting an Implementation Guide installed into HAPI

```
docker run -p 8080:8080 -e "hapi.fhir.implementationguides.someIg.name=com.org.something" -e "hapi.fhir.implementationguides.someIg.version=1.2.3" -e "hapi.fhir.implementationguides.someIg.packageUrl=https://build.fhir.org/ig/yourOrg/yourIg/package.tgz" -e "hapi.fhir.implementationguides.someIg.installMode=STORE_AND_INSTALL" hapiproject/hapi:latest
```

### Example using ``docker-compose.yml`` for docker-compose

```yaml
version: '3.7'

services:
  fhir:
    container_name: fhir
    image: "hapiproject/hapi:latest"
    ports:
      - "8080:8080"
    configs:
      - source: hapi
        target: /app/config/application.yaml
    depends_on:
      - db


  db:
    image: postgres
    restart: always
    environment:
      POSTGRES_PASSWORD: admin
      POSTGRES_USER: admin
      POSTGRES_DB: hapi
    volumes:
      - ./hapi.postgress.data:/var/lib/postgresql/data

configs:
  hapi:
     file: ./hapi.application.yaml
```

Provide the following content in ``./hapi.application.yaml``:

```yaml
spring:
  datasource:
    url: 'jdbc:postgresql://db:5432/hapi'
    username: admin
    password: admin
    driverClassName: org.postgresql.Driver
  jpa:
    properties:
      hibernate.dialect: ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect
      hibernate.search.enabled: false
```

### Example running custom interceptor using docker-compose

This example is an extension of the above one, now adding a custom interceptor.

```yaml
version: '3.7'

services:
  fhir:
    container_name: fhir
    image: "hapiproject/hapi:latest"
    ports:
      - "8080:8080"
    configs:
      - source: hapi
        target: /app/config/application.yaml
      - source: hapi-extra-classes
        target: /app/extra-classes
    depends_on:
      - db

  db:
    image: postgres
    restart: always
    environment:
      POSTGRES_PASSWORD: admin
      POSTGRES_USER: admin
      POSTGRES_DB: hapi
    volumes:
      - ./hapi.postgress.data:/var/lib/postgresql/data

configs:
  hapi:
     file: ./hapi.application.yaml
  hapi-extra-classes:
     file: ./hapi-extra-classes
```

Provide the following content in ``./hapi.application.yaml``:

```yaml
spring:
  datasource:
    url: 'jdbc:postgresql://db:5432/hapi'
    username: admin
    password: admin
    driverClassName: org.postgresql.Driver
  jpa:
    properties:
      hibernate.dialect: ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect
      hibernate.search.enabled: false
hapi:
  fhir:
    custom-bean-packages: the.package.containing.your.interceptor
    custom-interceptor-classes: the.package.containing.your.interceptor.YourInterceptor
```

The basic interceptor structure would be like this:

```java
package the.package.containing.your.interceptor;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;

@Component
@Interceptor
public class YourInterceptor
{
    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void resourceCreated(IBaseResource newResource)
    {
        System.out.println("YourInterceptor.resourceCreated");
    }
}
```

## Running locally

The easiest way to run this server entirely depends on your environment requirements. The following ways are supported:

### Using jetty
```bash
mvn -Pjetty spring-boot:run
```

The Server will then be accessible at http://localhost:8080/fhir and the CapabilityStatement will be found at http://localhost:8080/fhir/metadata.

### Using Spring Boot
```bash
mvn spring-boot:run
```

The Server will then be accessible at http://localhost:8080/fhir and the CapabilityStatement will be found at http://localhost:8080/fhir/metadata.

If you want to run this server on a different port, you can change the port in the `src/main/resources/application.yaml` file as follows:

```yaml
server:
#  servlet:
#    context-path: /example/path
  port: 8888
```

The Server will then be accessible at http://localhost:8888/fhir and the CapabilityStatement will be found at http://localhost:8888/fhir/metadata. Remember to adjust your overlay configuration in the `application.yaml` file to the following:

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8888/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

### Using Spring Boot with :run
```bash
mvn clean spring-boot:run -Pboot
```
Server will then be accessible at http://localhost:8080/ and e.g. http://localhost:8080/fhir/metadata. Remember to adjust you overlay configuration in the application.yaml to the following:

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

### Using Spring Boot
```bash
mvn clean package spring-boot:repackage -DskipTests=true -Pboot && java -jar target/ROOT.war
```
Server will then be accessible at http://localhost:8080/ and e.g. http://localhost:8080/fhir/metadata. Remember to adjust your overlay configuration in the application.yaml to the following:

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```
### Using Spring Boot and Google distroless
```bash
mvn clean package com.google.cloud.tools:jib-maven-plugin:dockerBuild -Dimage=distroless-hapi && docker run -p 8080:8080 distroless-hapi
```
Server will then be accessible at http://localhost:8080/ and e.g. http://localhost:8080/fhir/metadata. Remember to adjust your overlay configuration in the application.yaml to the following:

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

### Using the Dockerfile and multistage build
```bash
./build-docker-image.sh && docker run -p 8080:8080 hapi-fhir/hapi-fhir-jpaserver-starter:latest
```
Server will then be accessible at http://localhost:8080/ and e.g. http://localhost:8080/fhir/metadata. Remember to adjust your overlay configuration in the application.yaml to the following:

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```

## Configurations

Much of this HAPI starter project can be configured using the yaml file in _src/main/resources/application.yaml_. By default, this starter project is configured to use H2 as the database.

### MySQL configuration

HAPI FHIR JPA Server does not support MySQL as it is deprecated.

See more at https://hapifhir.io/hapi-fhir/docs/server_jpa/database_support.html

### PostgreSQL configuration

To configure the starter app to use PostgreSQL, instead of the default H2, update the application.yaml file to have the following:

```yaml
spring:
  datasource:
    url: 'jdbc:postgresql://localhost:5432/hapi'
    username: admin
    password: admin
    driverClassName: org.postgresql.Driver
  jpa:
    properties:
      hibernate.dialect: ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect
      hibernate.search.enabled: false

      # Then comment all hibernate.search.backend.*
```

Because the integration tests within the project rely on the default H2 database configuration, it is important to either explicitly skip the integration tests during the build process, i.e., `mvn install -DskipTests`, or delete the tests altogether. Failure to skip or delete the tests once you've configured PostgreSQL for the datasource.driver, datasource.url, and hibernate.dialect as outlined above will result in build errors and compilation failure.

### Microsoft SQL Server configuration

To configure the starter app to use MS SQL Server, instead of the default H2, update the application.yaml file to have the following:

```yaml
spring:
  datasource:
    url: 'jdbc:sqlserver://<server>:<port>;databaseName=<databasename>'
    username: admin
    password: admin
    driverClassName: com.microsoft.sqlserver.jdbc.SQLServerDriver
```

Also, make sure you are not setting the Hibernate dialect explicitly, in other words, remove any lines similar to:

```
hibernate.dialect: {some none Microsoft SQL dialect}
```


Because the integration tests within the project rely on the default H2 database configuration, it is important to either explicitly skip the integration tests during the build process, i.e., `mvn install -DskipTests`, or delete the tests altogether. Failure to skip or delete the tests once you've configured PostgreSQL for the datasource.driver, datasource.url, and hibernate.dialect as outlined above will result in build errors and compilation failure.


NOTE: MS SQL Server by default uses a case-insensitive codepage. This will cause errors with some operations - such as when expanding case-sensitive valuesets (UCUM) as there are unique indexes defined on the terminology tables for codes.
It is recommended to deploy a case-sensitive database prior to running HAPI FHIR when using MS SQL Server to avoid these and potentially other issues.

## Adding custom interceptors
Custom interceptors can be registered with the server by including the property `hapi.fhir.custom-interceptor-classes`. This will take a comma separated list of fully-qualified class names which will be registered with the server.
Interceptors will be discovered in one of two ways:

1) From the Spring application context as existing Beans (can be used in conjunction with `hapi.fhir.custom-bean-packages`) or registered with Spring via other methods
2) Classes will be instantiated via reflection if no matching Bean is found

Interceptors can also be registered manually through `RestfulServer.registerInterceptor`. Take note that any interceptor registered in this way _will not fire_ for non-REST operations, e.g. creation through a DAO. To trigger in this case, you need to register your interceptors on the `IInterceptorService` bean.

## Adding custom operations(providers)
Custom operations(providers) can be registered with the server by including the property `hapi.fhir.custom-provider-classes`. This will take a comma separated list of fully-qualified class names which will be registered with the server.
Providers will be discovered in one of two ways:

1) discovered from the Spring application context as existing Beans (can be used in conjunction with `hapi.fhir.custom-bean-packages`) or registered with Spring via other methods

or

2) classes will be instantiated via reflection if no matching Bean is found

## Customizing The Web Testpage UI

The UI that comes with this server is an exact clone of the server available at [http://hapi.fhir.org](http://hapi.fhir.org). You may skin this UI if you'd like. For example, you might change the introductory text or replace the logo with your own.

The UI is customized using [Thymeleaf](https://www.thymeleaf.org/) template files. You might want to learn more about Thymeleaf, but you don't necessarily need to: they are quite easy to figure out.

Several template files that can be customized are found in the following directory: [https://github.com/hapifhir/hapi-fhir-jpaserver-starter/tree/master/src/main/webapp/WEB-INF/templates](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/tree/master/src/main/webapp/WEB-INF/templates)

## Deploying to an Application Server

Using the Maven-Embedded Jetty method above is convenient, but it is not a good solution if you want to leave the server running in the background.

Most people who are using HAPI FHIR JPA as a server that is accessible to other people (whether internally on your network or publicly hosted) will do so using an Application Server, such as [Apache Tomcat](http://tomcat.apache.org/) or [Jetty](https://www.eclipse.org/jetty/). Note that any Servlet 3.0+ compatible Web Container will work (e.g. Wildfly, Websphere, etc.).

Tomcat is very popular, so it is a good choice simply because you will be able to find many tutorials online. Jetty is a great alternative due to its fast startup time and good overall performance.

To deploy to a container, you should first build the project:

```bash
mvn clean install
```

This will create a file called `ROOT.war` in your `target` directory. This should be installed in your Web Container according to the instructions for your particular container. For example, if you are using Tomcat, you will want to copy this file to the `webapps/` directory.

Again, browse to the following link to use the server (note that the port 8080 may not be correct depending on how your server is configured).

[http://localhost:8080/](http://localhost:8080/)

You will then be able to access the JPA server e.g. using http://localhost:8080/fhir/metadata.

If you would like it to be hosted at e.g. hapi-fhir-jpaserver, e.g. http://localhost:8080/hapi-fhir-jpaserver/ or http://localhost:8080/hapi-fhir-jpaserver/fhir/metadata - then rename the WAR file to ```hapi-fhir-jpaserver.war``` and adjust the overlay configuration accordingly e.g.

```yaml
    tester:
      -
          id: home
          name: Local Tester
          server_address: 'http://localhost:8080/hapi-fhir-jpaserver/fhir'
          refuse_to_fetch_third_party_urls: false
          fhir_version: R4
```


## Deploy with docker compose

Docker compose is a simple option to build and deploy containers. To deploy with docker compose, you should build the project
with `mvn clean install` and then bring up the containers with `docker-compose up -d --build`. The server can be
reached at http://localhost:8080/.

In order to use another port, change the `ports` parameter
inside `docker-compose.yml` to `8888:8080`, where 8888 is a port of your choice.

The docker compose set also includes PostgreSQL database, if you choose to use PostgreSQL instead of H2, change the following
properties in `src/main/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: 'jdbc:postgresql://hapi-fhir-postgres:5432/hapi'
    username: admin
    password: admin
    driverClassName: org.postgresql.Driver
jpa:
  properties:
    hibernate.dialect: ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect
    hibernate.search.enabled: false

    # Then comment all hibernate.search.backend.*
```

## Running hapi-fhir-jpaserver directly from IntelliJ as Spring Boot
Make sure you run with the maven profile called ```boot``` and NOT also ```jetty```. Then you are ready to press debug the project directly without any extra Application Servers.

## Running hapi-fhir-jpaserver-example in Tomcat from IntelliJ

Install Tomcat.

Make sure you have Tomcat set up in IntelliJ.

- File->Settings->Build, Execution, Deployment->Application Servers
- Click +
- Select "Tomcat Server"
- Enter the path to your tomcat deployment for both Tomcat Home (IntelliJ will fill in base directory for you)

Add a Run Configuration for running hapi-fhir-jpaserver-example under Tomcat

- Run->Edit Configurations
- Click the green +
- Select Tomcat Server, Local
- Change the name to whatever you wish
- Uncheck the "After launch" checkbox
- On the "Deployment" tab, click the green +
- Select "Artifact"
- Select "hapi-fhir-jpaserver-example:war"
- In "Application context" type /hapi

Run the configuration.

- You should now have an "Application Servers" in the list of windows at the bottom.
- Click it.
- Select your server, and click the green triangle (or the bug if you want to debug)
- Wait for the console output to stop

Point your browser (or fiddler, or what have you) to `http://localhost:8080/hapi/baseDstu3/Patient`

## Enabling Subscriptions

The server may be configured with subscription support by enabling properties in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file:

- `hapi.fhir.subscription.resthook_enabled` - Enables REST Hook subscriptions, where the server will make an outgoing connection to a remote REST server

- `hapi.fhir.subscription.email.*` - Enables email subscriptions. Note that you must also provide the connection details for a usable SMTP server.

- `hapi.fhir.subscription.websocket_enabled` - Enables websocket subscriptions. With this enabled, your server will accept incoming websocket connections on the following URL (this example uses the default context path and port, you may need to tweak depending on your deployment environment): [ws://localhost:8080/websocket](ws://localhost:8080/websocket)

## Enabling Clinical Reasoning

Set `hapi.fhir.cr.enabled=true` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file to enable [Clinical Quality Language](https://cql.hl7.org/) on this server.  An alternate settings file, [application-cds.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application-cds.yaml), exists with the Clinical Reasoning module enabled and default settings that have been found to work with most CDS and dQM test cases.

## Enabling CDS Hooks

Set `hapi.fhir.cdshooks.enabled=true` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file to enable [CDS Hooks](https://cds-hooks.org/) on this server.  The Clinical Reasoning module must also be enabled because this implementation of CDS Hooks includes [CDS on FHIR](https://build.fhir.org/clinicalreasoning-cds-on-fhir.html).  An example CDS Service using CDS on FHIR is available in the CdsHooksServletIT test class.

## Enabling MDM (EMPI)

Set `hapi.fhir.mdm_enabled=true` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file to enable MDM on this server.  The MDM matching rules are configured in [mdm-rules.json](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/mdm-rules.json).  The rules in this example file should be replaced with actual matching rules appropriate to your data. Note that MDM relies on subscriptions, so for MDM to work, subscriptions must be enabled.

## Using Elasticsearch

By default, the server will use embedded lucene indexes for terminology and fulltext indexing purposes. You can switch this to using lucene by editing the properties in [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml)

For example:

```properties
elasticsearch.enabled=true
elasticsearch.rest_url=localhost:9200
elasticsearch.username=SomeUsername
elasticsearch.password=SomePassword
elasticsearch.protocol=http
elasticsearch.required_index_status=YELLOW
elasticsearch.schema_management_strategy=CREATE
```

## Enabling LastN

Set `hapi.fhir.lastn_enabled=true` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file to enable the $lastn operation on this server.  Note that the $lastn operation relies on Elasticsearch, so for $lastn to work, indexing must be enabled using Elasticsearch.

## Enabling Resource to be stored in Lucene Index

Set `hapi.fhir.store_resource_in_lucene_index_enabled` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file to enable storing of resource json along with Lucene/Elasticsearch index mappings.

## Changing cached search results time

It is possible to change the cached search results time. The option `reuse_cached_search_results_millis` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) is 6000 milliseconds by default.
Set `reuse_cached_search_results_millis: -1` in the [application.yaml](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/src/main/resources/application.yaml) file to ignore the cache time every search.

## Build the distroless variant of the image (for lower footprint and improved security)

The default Dockerfile contains a `release-distroless` stage to build a variant of the image
using the `gcr.io/distroless/java-debian10:11` base image:

```sh
docker build --target=release-distroless -t hapi-fhir:distroless .
```

Note that distroless images are also automatically built and pushed to the container registry,
see the `-distroless` suffix in the image tags.

## Adding custom operations

To add a custom operation, refer to the documentation in the core hapi-fhir libraries [here](https://hapifhir.io/hapi-fhir/docs/server_plain/rest_operations_operations.html).

Within `hapi-fhir-jpaserver-starter`, create a generic class (that does not extend or implement any classes or interfaces), add the `@Operation` as a method within the generic class, and then register the class as a provider using `RestfulServer.registerProvider()`.

## Runtime package install

It's possible to install a FHIR Implementation Guide package (`package.tgz`) either from a published package or from a local package with the `$install` operation, without having to restart the server. This is available for R4 and R5.

This feature must be enabled in the application.yaml (or docker command line):

```yaml
hapi:
  fhir:
    ig_runtime_upload_enabled: true
```

The `$install` operation is triggered with a POST to `[server]/ImplementationGuide/$install`, with the payload below:

```json
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "npmContent",
      "valueBase64Binary": "[BASE64_ENCODED_NPM_PACKAGE_DATA]"
    }
  ]
}
```

## JWT Authentication Guide

This HAPI FHIR server includes an enhanced JWT-based authentication implementation with security best practices.

### Security Improvements (2026)

✅ **Access Token + Refresh Token Strategy**
- Access tokens expire in 15 minutes (short-lived)
- Refresh tokens expire in 7 days (long-lived)
- Reduces exposure window from 24h to 15 minutes

✅ **Token Revocation (JTI + Blacklist)**
- Each token has a unique JTI (JWT ID)
- Logout immediately revokes tokens
- Automatic blacklist cleanup every 5 minutes

✅ **OIDC Standard Claims**
- Claims: `iss`, `aud`, `iat`, `nbf`, `exp`, `jti`
- Roles as JSON array: `["ADMIN", "MEDICO"]`
- Compatible with OAuth2/OpenID Connect standards

### Architecture

The JWT authentication system consists of:

- **AuthController** - REST endpoints for login, refresh, logout
- **JwtTokenProvider** - Generates and validates JWT tokens with JTI
- **TokenBlacklistService** - Manages token revocation and blacklist
- **JwtAuthenticationFilter** - Servlet filter validating JWT on each request
- **SecurityConfig** - Spring configuration for beans and filter registration
- **TokenResponse** - DTO for login/refresh responses

### Test Credentials

| Username | Password | Role |
|----------|----------|------|
| admin | admin123 | ADMIN |
| doctor | doctor123 | MEDICO |

### Quick Start

#### 1. Build and Deploy

```bash
cd c:\Users\ruben\Desktop\fhirserver
docker-compose down
docker-compose up -d --build
sleep 45
```

Verify the server is running:
```bash
docker-compose logs hapi-fhir-jpaserver-start --tail=10
```

#### 2. Login and Get Token Pair

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

Expected response:
```json
{
  "access_token": "eyJhbGciOiJIUzUxMiJ9.eyJpc3MiOiJmaGlyc2VydmVyIiwic3ViIjoiYWRtaW4iLCJleHAiOjE3ODIzOTQzMDIsImp0aSI6ImY2Njc0N2JjLTdjNGYtNDRiNy05NjRjLTFjNDk3ZWJjMDk4MyIsInJvbGVzIjpbIkFETUkiXSwidG9rZW5fdHlwZSI6ImFjY2VzcyJ9...",
  "refresh_token": "eyJhbGciOiJIUzUxMiJ9.eyJpc3MiOiJmaGlyc2VydmVyIiwic3ViIjoiYWRtaW4iLCJleHAiOjE3ODI5OTgyMDIsImp0aSI6IjdjMTBjOWUyLTIwNGUtNGY1OS1hNTk0LTQwOGQxMjNhODhjMyIsInJvbGVzIjpbIkFETUkiXSwidG9rZW5fdHlwZSI6InJlZnJlc2gifX...",
  "token_type": "Bearer",
  "expires_in": 900,
  "username": "admin",
  "roles": ["ADMIN"]
}
```

#### 3. Use Access Token in FHIR Requests

```bash
ACCESS_TOKEN="eyJhbGciOiJIUzUxMiJ9..."

curl -X GET http://localhost:8080/fhir/Patient \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

#### 4. Refresh Access Token (Before Expiration)

```bash
REFRESH_TOKEN="eyJhbGciOiJIUzUxMiJ9..."

curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\":\"$REFRESH_TOKEN\"}"
```

#### 5. Logout (Revoke Token)

```bash
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer $ACCESS_TOKEN"

# Response:
# {"message": "Logged out successfully", "timestamp": 1782393420}
```

### Token Lifecycle

```
[LOGIN] → Access Token (15 min) + Refresh Token (7 days)
    ↓
[USE API] → Authorization: Bearer <access_token>
    ↓
[EXPIRING] → POST /auth/refresh with refresh_token
    ↓
[NEW ACCESS] → Get new access_token, use same refresh_token
    ↓
[LOGOUT] → POST /auth/logout revokes both tokens (JTI blacklist)
```

### Role-Based Access Control (RBAC)

| HTTP Method | Endpoint | ADMIN | MEDICO | No Auth |
|------------|----------|-------|--------|---------|
| GET | /fhir/* | ✅ | ✅ | ❌ 401 |
| POST | /fhir/* | ✅ | ✅ | ❌ 401 |
| PUT | /fhir/* | ✅ | ✅ | ❌ 401 |
| DELETE | /fhir/* | ✅ | ❌ 403 | ❌ 401 |

### Testing Examples

**Test 1: Request without JWT (401)**
```bash
curl -X GET http://localhost:8080/fhir/Patient
# Response: 401 Unauthorized
```

**Test 2: ADMIN can DELETE (200/204)**
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.access_token')

curl -X DELETE http://localhost:8080/fhir/Patient/PATIENT_ID \
  -H "Authorization: Bearer $TOKEN"
```

**Test 3: MEDICO cannot DELETE (403)**
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"doctor","password":"doctor123"}' | jq -r '.access_token')

curl -X DELETE http://localhost:8080/fhir/Patient/PATIENT_ID \
  -H "Authorization: Bearer $TOKEN"
# Response: 403 Forbidden
```

**Test 4: Logout revokes token immediately (401)**
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.access_token')

# Logout
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer $TOKEN"

# Try to use revoked token (should fail)
curl -X GET http://localhost:8080/fhir/Patient \
  -H "Authorization: Bearer $TOKEN"
# Response: 401 Unauthorized (token was revoked)
```

### JWT Configuration

Located in `src/main/resources/application.yaml`:

```yaml
jwt:
  secret: fhirserver_secret_key_change_in_production_environment_DO_NOT_USE_IN_PRODUCTION
  expiration: 86400000  # 24 hours (legacy, kept for compatibility)
  access-token-expiration: 900000  # 15 minutes
  refresh-token-expiration: 604800000  # 7 days
  issuer: fhirserver
  audience: fhirserver-api
```

- **Algorithm**: HS512 (HMAC SHA-512)
- **Access Token Duration**: 15 minutes (900 seconds)
- **Refresh Token Duration**: 7 days (604,800 seconds)
- **Header Format**: `Authorization: Bearer <token>`
- **Claims**: `iss`, `aud`, `sub`, `iat`, `nbf`, `exp`, `jti`, `username`, `roles`, `token_type`

### Testing Scripts

See the following scripts for automated testing:

- **Linux/WSL**: `bash test-jwt-run.sh` - Simple test flow
- **Linux/WSL (detailed)**: `bash test-jwt-linux.sh` - Full JWT decode and validation
- **Documentation**: `JWT_TESTING_LINUX.md` - Manual test instructions
- **Documentation**: `JWT_TESTING_MANUAL.md` - cURL examples for all endpoints
- **Documentation**: `JWT_IMPROVEMENTS.md` - Implementation details and architecture

### Decoding JWT Tokens

To inspect the token payload:

```bash
# Function to decode JWT (Bash)
decode_jwt() {
  local token=$1
  echo "$token" | cut -d'.' -f2 | base64 -d | jq .
}

# Usage
decode_jwt "$ACCESS_TOKEN"
```

Expected payload:
```json
{
  "iss": "fhirserver",
  "aud": "fhirserver-api",
  "sub": "admin",
  "iat": 1782393402,
  "nbf": 1782393403,
  "exp": 1782394302,
  "jti": "f66747bc-7c4f-44b7-964c-1c497ebc0983",
  "username": "admin",
  "roles": ["ADMIN"],
  "token_type": "access"
}
```

### Security Notes

⚠️ **Production Deployment Checklist**
- [ ] Change JWT secret to strong random value (use environment variable)
- [ ] Enable HTTPS/TLS (SSL certificates required)
- [ ] Implement rate limiting on `/auth/login` endpoint
- [ ] Use external Identity Provider (Keycloak, Auth0, Azure AD, etc.)
- [ ] Implement SMART on FHIR (OAuth2/OIDC) for healthcare compliance
- [ ] Hash passwords with BCrypt instead of plain text
- [ ] Add audit logging for authentication events
- [ ] Implement token rotation/refresh strategy
- [ ] Set appropriate CORS policies
- [ ] Monitor blacklist size and cleanup performance
- [ ] Regular security updates for dependencies

⚠️ **This is a simplified academic implementation. For production healthcare systems:**
- Use dedicated authentication/authorization service
- Implement SMART on FHIR for HIPAA/HL7 compliance
- Consider using Spring Security OAuth2 with external provider
- Enable encryption at rest and in transit
- Implement comprehensive audit trails
- Regular penetration testing and security audits



## Enable OpenTelemetry auto-instrumentation

The container image includes the [OpenTelemetry Java auto-instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
Java agent JAR which can be used to export telemetry data for the HAPI FHIR JPA Server. You can enable it by specifying the `-javaagent` flag,
for example by overriding the `JAVA_TOOL_OPTIONS` environment variable:

```sh
docker run --rm -it -p 8080:8080 \
  -e JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar" \
  -e OTEL_TRACES_EXPORTER="jaeger" \
  -e OTEL_SERVICE_NAME="hapi-fhir-server" \
  -e OTEL_EXPORTER_JAEGER_ENDPOINT="http://jaeger:14250" \
  docker.io/hapiproject/hapi:latest
```

You can configure the agent using environment variables or Java system properties, see <https://opentelemetry.io/docs/instrumentation/java/automatic/agent-config/> for details.

## Enable MCP

MCP capabilities can be enabled by setting the `spring.ai.mcp.server.enabled` to `true`. This will enable the MCP server and expose the MCP endpoints. The MCP endpoint is currently hardcoded to `/mcp/message` and can be tried out by running e.g. `npx @modelcontextprotocol/inspector` and connect to http://localhost:8080/mcp/message using Streamable HTTP. Spring AI MCP Server Auto Configuration is currently not supported.
#   f h i r s e r v e r 
 
 #   f h i r s e r v e r 
 
 #   f h i r s e r v e r 
 
 
