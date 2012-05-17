==========
proxylight
==========

ProxyLight is a lightweight HTTP proxy forked from Kelvin Tan's improvements [1]_ to Proxoid [2]_. 

Plans?
======

The plan is to extend ProxyLight to enhance Selenium2 WebDriver testing. [3]_

* REST API to:

  * GET Request headers in JSON format
  * GET Response headers in JSON format
  * GET Response status code (integer)
  * POST (inject) custom Request header into the next Request
  * POST (inject) custom Request header into all future Requests

* do we save request/response pairs per URL, or only save the most recent request/response when the request is not for a static resource (e.g., images, flash, css, javascript) or AJAX request (e.g., X-Requested-With)

* php-webdriver: to expose the above functionality

* Mink to use our php-webdriver fork

* Document How-To use php-webdriver, Selenium2 and ProxyLight?

References
==========
.. [1] http://www.supermind.org/blog/968/howto-collect-webdriver-http-request-and-response-headers
.. [2] http://code.google.com/p/proxoid/ An HTTP proxy server for Android
.. [3] http://code.google.com/p/selenium/issues/detail?id=141
