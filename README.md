App Runner Router
-----------------

This is an optional extension to [AppRunner](https://github.com/danielflower/app-runner) that
allows for horizontal scaling of AppRunner instances. The basic idea is that you can start
multiple, independent AppRunners on different machines, and then have the App Runner Router
act as a reverse proxy, sending requests to the various instances it knows about.

### Installation and configuration

Download the jar, create a log config, and start it. There is an example in the `local` directory
of this repo.

To register App Runner instances with the router, first start them, then POST information to the router.

For example, if the router is running at `apprunner.example.org` and you have an app runner instance at
`some-host.example.org:8080`, then make the following request:

    POST http://apprunner.example.org/api/v1/runners
    Form parameters:
        id: a unique identifier for the app runner instance, for example some-host
        url: the URL of the instance, e.g. http://some-host.example.org:8080
        maxApps: the maximum number of applications that can be added to an instance

Example curl command: `curl --data 'id=some-name&maxApps=20&url=https://some-host.exaple.org' -X POST https://apprunner.example.org/api/v1/runners`

Aside from the extra operations in `/api/v1/runners`, the router has the same REST API as an
app-runner instance. In general, it will simply proxy requests to the correct instance, with a couple
of exemptions: `GET /api/v1/apps` returns an aggregation of all apps across all instances, and
`POST /api/v1/apps` will first pick an instance to create the app in, and send it there.

### Running locally

Clone the repo and then run `mvn compile`, and then run the main method in `RunLocal.java`

This will start two App Runner instances (with different versions) and register all the sample apps
against it, and then open a browser to the API. You can then run something like
[App Runner Home](https://github.com/danielflower/app-runner-home)
locally against your local router.

### Change log

* **1.5.0** Added optional publishing of request metrics over UDP.
* **1.4.0** Changed the `runners` API to allow `PUT`ing changes to runner instances (and `POST`ing to an existing one returns a `409`)
plus added `appCount` to the runners API.
* **1.3.0** Added `appCount` to apps api and fixed X-Forwarded-* headers.
* **1.2.0** Fixed streaming, missing CORS headers, added full runner info the systems API, and increased response timeouts. 
* **1.1.4** Fixed bugs where the router is HTTPS and an instance is HTTP.
