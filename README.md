AEM Communities Sample OAuth Provider
========
This sample code demonstrates a basic implementation of a [Provider](https://docs.adobe.com/docs/en/aem/6-1/ref/javadoc/com/adobe/granite/auth/oauth/Provider.html "Provider Interface Javadoc") to enable one to implement a custom OAuth Provider for AEM Communities.

Building
--------

This project uses Maven for building. 

First, be sure that your Maven **settings.xml** file contains the **Adobe Public Maven Repository profile** (see:  [repo.adobe.com](https://repo.adobe.com "Adobe Public Maven Repository"))


Common build commands:

From the root directory, run ``mvn -PautoInstallPackage clean install`` to build the bundle and content package and install to a CQ instance.

From the bundle directory, run ``mvn -PautoInstallBundle clean install`` to build *just* the bundle and install to a CQ instance.


Specifying CRX Host/Port
------------------------

The CRX host and port can be specified on the command line with:
mvn -Dcrx.host=otherhost -Dcrx.port=5502 <goals>


