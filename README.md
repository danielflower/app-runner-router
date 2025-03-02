App Runner Router
-----------------

This is an optional extension to [AppRunner](https://github.com/danielflower/app-runner) that
allows for horizontal scaling of AppRunner instances. The basic idea is that you can start
multiple, independent AppRunners on different machines, and then have the App Runner Router
act as a reverse proxy, sending requests to the various instances it knows about.

### Installation and configuration

Download the jar, create a log config, and start it. There is an example in the `local` directory
of this repo. (Alternatively, build your own package by using the `app-runner-router-lib` - see the `app-runner-router`
module in this repo for an example that builds it as an uber-jar.)

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

The router can also proxy direct to instances via the `/api/v1/runner-proxy/{runnerid}` endpoint.
For example, if you have a runner with the ID `myrunner` on `https://myrunner.example/` then a call
to `/api/v1/runner-proxy/myrunner/api/v1/system` will get proxied to `https://myrunner.example/api/v1/system`

### Running locally

Clone the repo and then run `mvn compile`, and then run the main method in `RunLocal.java`

This will start two App Runner instances (with different versions) and register all the sample apps
against it, and then open a browser to the API. You can then run something like
[App Runner Home](https://github.com/danielflower/app-runner-home)
locally against your local router.

### Change log

* **1.12.0** Java 17 or later now required. Jetty client replaced with the built in java client.
* **1.10.3** Fixed UDP publishing of responses, removed access logs (for now) and added more stats to the system API.
* **1.8.0** When creating an app with a `POST` to `/api/v1/apps` you can now ask the router to exclude a runner by putting its ID in the `X-Excluded-Runner` header (multiple headers allowed).
* **1.6.0** If some app runner instances are unavailable, the /apps and /system calls will still return and will include details about the errors.
* **1.5.2** Added optional config for handling proxy timeouts: `apprunner.proxy.idle.timeout` (default 30000ms) `apprunner.proxy.total.timeout` (default 20 mins).
* **1.5.1** The REST API is now gzipped. Also added a favicon.
* **1.5.0** Added optional publishing of request metrics over UDP.
* **1.4.0** Changed the `runners` API to allow `PUT`ing changes to runner instances (and `POST`ing to an existing one returns a `409`)
plus added `appCount` to the runners API.
* **1.3.0** Added `appCount` to apps api and fixed X-Forwarded-* headers.
* **1.2.0** Fixed streaming, missing CORS headers, added full runner info the systems API, and increased response timeouts. 
* **1.1.4** Fixed bugs where the router is HTTPS and an instance is HTTP.
