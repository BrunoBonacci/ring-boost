# fib

Fibonacci numbers as a service.
Simple service to show ring-boost functionality.


## Running

To start a web server for the application, run:

    lein ring server-headless


Then make a request:

``` bash
$ curl -is http://localhost:3000/fib/100

HTTP/1.1 200 OK
Date: Sat, 14 Apr 2018 10:36:46 GMT
ETag: 30647e5b994dc46db920c54bfebfe5f0
Content-Type: application/json; charset=utf-8
Content-Length: 55
Server: Jetty(7.6.13.v20130916)

{"status":"OK","pos":"100","fib":573147844013817084101}
```


If you want to see `ring-boost` debugging headers add the following
request header `x-cache-debug: 1`

``` bash
$ curl -is -H 'x-cache-debug: 1' http://localhost:3000/fib/100

HTTP/1.1 200 OK
Date: Sat, 14 Apr 2018 10:39:30 GMT
ETag: 30647e5b994dc46db920c54bfebfe5f0
X-CACHE: RING-BOOST/v0.1.0-SNAPSHOT
X-RING-BOOST-CACHE: CACHE-HIT
X-RING-BOOST-CACHE-PROFILE: :fib-numbers
X-RING-BOOST-CACHE-STATS1: 11/3/0
X-RING-BOOST-CACHE-STATS2: 45/20/5
Content-Type: application/json; charset=utf-8
Content-Length: 55
Server: Jetty(7.6.13.v20130916)

{"status":"OK","pos":"100","fib":573147844013817084101}
```

Now try with a larger number like:

     # the first time will take a while
     curl -is -H 'x-cache-debug: 1' http://localhost:3000/fib/1000000

Running again, now it will be fetched from the cache.

## License

Copyright © 2018 Bruno Bonacci - Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
