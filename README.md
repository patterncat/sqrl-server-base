<!--- http://dillinger.io/ --->

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.sqrlserverjava/sqrl-server-base/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.sqrlserverjava/sqrl-server-base)
[![Build Status](https://travis-ci.org/sqrlserverjava/sqrl-server-base.svg?branch=master)](https://travis-ci.org/sqrlserverjava/sqrl-server-base)
[![CII Best Practices](https://bestpractices.coreinfrastructure.org/projects/204/badge)](https://bestpractices.coreinfrastructure.org/projects/204)
# sqrl-server-base

Java SQRL authentication library that implements the server side portions of the SQRL [SQRL](https://www.grc.com/sqrl/sqrl.htm) protocol.  It can be integrated into existing JEE apps in order to add SQRL as an authentication option to existing methods (username/password, etc).

The intent is that additional libraries will be built on top of this for popular authentication frameworks such as Spring, Apache Shiro, etc.  It can also be used directly by an application as is.

## General


#### Interoperability
 * This library is fully functional for basic authentication.  Advanced operations such as SQRL identity replacement are not yet implemented
 * As of June 2016, the SQRL protocol has been declared ["done with caveats"](https://www.grc.com/sn/sn-562.txt) by it's creator.  SQRL clients built prior to this may still be using an older version of the protocol and may not be compatible
* There is a example application using this library at the url below.  You <b>must</b> install a SQRL client (such as sqrl*.exe from [grc.com](https://www.grc.com/dev/sqrl/) before running the demo:
 https://sqrljava.com:20000/sqrlexample

#### Dependencies
 * Java 1.8
 * [Zxing](https://github.com/zxing/zxing) for QR code generation
 * [slf4j](http://www.slf4j.org//) for logging
 * [ed25519-java](https://github.com/str4d/ed25519-java) for fast   Ed25519 EC crypto
 * [JPA 2.1 provider](http://www.oracle.com/technetwork/java/javaee/tech/persistence-jsp-140049.html) for integration with SQL and NOSQL databases.  Example providers are [eclipselink](https://www.eclipse.org/eclipselink/) and [hibernate](https://docs.jboss.org/hibernate/orm/3.6/quickstart/en-US/html/hibernate-gsg-tutorial-jpa.html).  JPA is used in a manner in which the JEE container does __not__ need to have JPA support.  This library can be used in lightweight servlet containers such as Jetty and Tomcat.
 * Servlet container supporting [JEE6](https://en.wikipedia.org/wiki/Java_EE_version_history#Java_EE_6_.28December_10.2C_2009.29) or higher

#### Integration Requirements
The SQRL protocol requires 2 interaction points with the user: 1) the web browser and 2) the SQRL client.  Even if both of these are running on the same device, they are distinct processes which communicate to different server endpoints.

A persistence layer (typically a database) is required for the 2 endpoints to communicate state and to store various information about the SQRL users.  One of the SQRL database tables will include a soft foreign key reference to existing user data table.  Used SQRL nonces ("nuts") are stored in another table.  These nonces are be purged shortly after they expire.

#### Integration Overview

1. Add the following dependencies to your pom:
```
    <dependency>
        <groupId>com.github.sqrlserverjava</groupId>
        <artifactId>sqrl-server-base</artifactId>
        <version>0.9.2</version>
    </dependency>
    <dependency>
        <groupId>com.github.sqrlserverjava</groupId>
        <artifactId>sqrl-server-atmosphere</artifactId>
        <version>1.0.0</version>
    </dependency>
```

1. Select a JPA provider and add the required jars to your classpath.  For example, to use eclipse link you would add: https://mvnrepository.com/artifact/org.eclipse.persistence/eclipselink
2. Add META-INF/persistence.xml to your classpath.  You can start with an in memory database by using [derby](jpa-examples/derby/META-INF/persistence.xml).  When you ready to use a real DB, here is the [visual db design](datastore/sqrl-db-design.png), [ddl](datastore/sqrl.ddl), and [mysql](persistenceMysql.xml) persistence.xml.  Other databases can be supported simply by editing persistence.xml accordingly.  
1. Define `com.github.dbadia.sqrl.server.SQRLConfig` and set the the 2 required fields accordingly (see javadoc).  You can store your settings on the classpath in a file named sqrl.xml and call `com.github.dbadia.sqrl.server.SQRLConfigHelper#loadFromClasspath()`  Or inject the bean via Spring, etc.  
1. In your application code, you can now create a `com.github.dbadia.sqrl.server.SqrlServerOperations` object using the `SqrlConfig` object from the previous step.
1. Create a servlet (or equivalent endpoint in your framework of choice) to handle SQRL client requests.  The `doPost` method of this servlet should invoke `SqrlServerOperations.handleSqrlClientRequest(ServletRequest, ServletResponse)`
1. Authentication Page Impact
	* Decide on one of the two approaches explained in [Browser to SQRL Client Correlation](#browser-to-sqrl-client-correlation) 
	* When you are ready to display the SQRL QR code, invoke `SqrlServerOperations.buildQrCodeForAuthPage()`.  Use the result to display an anchor tag with the SQRL url that wraps the QR code image.  The expected result in a QR code that can be scanned, clicked, or tapped, as seen in  https://sqrljava.com:20000/sqrlexample
	* Once the SQRL QR code is displayed, the authentication page must periodically poll the server (using ajay long polling, etc) to see if SQRL authentication is in progress or has completed.  Completion can be detected by checking `SqrlJpaPersistenceProvider
.fetchAuthenticationStatusRequired(correlator) == SqrlAuthenticationStatus.AUTH_COMPLETE`  which means that `SqrlCorrelator.getAuthenticatedIdentity` can be used to fetch the `SqrlIdentity` object
   * If `SqrlIdentity.getNativeUserXref == null` then this is the first time this user has authenticated with SQRL, but the user may have previously authenticated to the site via some other mechanism.  The application should present a one-time account mapping page asking if the user already has an existing account (username/password, google auth, etc) and authenticate them.  The application should then call `SqrlJpaPersistenceProvider.updateNativeUserXref(String)` to store the mapping between the SQRL identity and the username (or whatever means the application uniquely identifies users).
   * If `SqrlIdentity.getNativeUserXref != null`, the server should load the users data using the xref value and redirect the user to whatever page is typically displayed after authetication takes place

#### License
http://www.apache.org/licenses/LICENSE-2.0.html

## Enterprise Considerations

###### Authentication page impact
Traditionally, displaying an authentication page is an inexpensive operation.  Display some static html, username/password fields and some images from a CDN.  

However to display the SQRL QR code, the authentication page must poll the server to understand when a SQRL authentication is in progress and complete.  This polling is required once the SQRL QR code is displayed, even it the user is ends up invoking a non-SQRL authentication method.  The polling is required to have the page auto-refresh when the SQRL authentication is complete.

Both of these requirements result in a more process/resource intensive authentication page.  Here are 2 approaches that will  minimize such impact:
* Do not display the SQRL QR code on the login page; instead, display the [SQRL logo](https://www.grc.com/sqrl/logo.htm)  or some other indicator of SQRL support.  When/if the user clicks/taps the logo, generate the SQRL QR code and display
* Host the SQRL server logic on it's own infrastructure and load the SQRL QR code in the background as the page is loaded.  This isolates the more intensive SQRL support logic. 

###### Browser to SQRL Client Correlation
SQRL has 2 interaction points with the user: 1) the web browser and 2) the SQRL client.  Even if both of these are running on the same device, they are distinct processes which communicate to different server endpoints.
 
The browser requests the authentication page, then polls for status updates.  The SQRL QR code is then be clicked or scanned by the user which opens the SQRL client.  The SQRL client then makes a SQRL HTTP request to the SQRL backchannel which maps to a different server URI endpoint.  

There is a correlation ID that is set in a cookie on that auth page and is also embedded in the SQRL URL.  This allows the server to map the backend SQRL client authentication to a frontend authentication page so the authentication page can be refreshed to allow the user into the application.  This correlation currently occurs via the persistence layer.  It is important to understand that the browser polling mechanism will result in additional load on the site and the persistence/database layer.


## Security Considerations
NOTE: This section applies to this library only, it is not a security assessment of the SQRL protocol in general


###### Nut Collisions
The SQRL protocol dethe size of SQRL token ("Nut").  Each authentication page should get a unique nut, but due to size limitations, this is not guaranteed in a mutli server environment.  To help avoid the likelihood of 2 clients receiving the same nut, additional entropy is added to the Secure Random instance with a UUID, which, by definition, should be unique to the JVM instance.

Assume that, by great chance, a SQRL Nut collision does occur with users foo and bar and assume that foo's SQRL client contacts the server first.  Foo will be authenticated and allowed in as usual.  When bar's SQRL client contacts the server, the SQRL library will reject the token as already used.  Bar should be shown a new authentication page with a new Nut.

#### Reporting Issues
See [CONTRIBUTING.md](https://github.com/sqrlserverjava/sqrl-server-base/blob/master/CONTRIBUTING.md)