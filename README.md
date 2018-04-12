# ring-boost
[![Clojars Project](https://img.shields.io/clojars/v/com.brunobonacci/ring-boost.svg)](https://clojars.org/com.brunobonacci/ring-boost) ![CircleCi](https://img.shields.io/circleci/project/BrunoBonacci/ring-boost.svg) ![last-commit](https://img.shields.io/github/last-commit/BrunoBonacci/ring-boost.svg) [![Dependencies Status](https://jarkeeper.com/BrunoBonacci/safely/status.svg)](https://jarkeeper.com/BrunoBonacci/ring-boost)

I library to boost performances of Clojure web applications with off-heap serverside caching.

## Usage

In order to use the library add the dependency to your `project.clj`

``` clojure
[com.brunobonacci/ring-boost "0.1.0-SNAPSHOT"]
```

Current version: [![Clojars Project](https://img.shields.io/clojars/v/com.brunobonacci/ring-boost.svg)](https://clojars.org/com.brunobonacci/ring-boost)


Then require the namespace:

``` clojure
(ns foo.bar
  (:require [com.brunobonacci.ring-boost :as boost]))


(def boost-config
  {:enabled true
   :profiles
   [{:profile :slow-changing-resources
     :match [:and
             [:uri :starts-with? "/cacheable/request/path"]
             [:request-method = :get]]
     ;; cache for 30 sec
     :cache-for 30}]})


;; add ring-boost as last middleware
(def app
  (-> app-routes
      (wrap-json-response)
      (wrap-json-body {:keywords? true})
      ;; add ring-boost
      (boost/ring-boost boost-config)))

```

Here is a description of the configurable options:


``` clojure
{;; Whether the ring-boost cache is enabled or not.
   ;; when not enabled the handler will behave as if
   ;; the ring-boost wasn't there at all.
   :enabled true

   ;; cache storage configuration
   :storage {:sophia.path "/tmp/ring-boost-cache" :dbs ["cache" "stats"]}

   ;; caching profiles. see `default-profile-config`
   :profiles [
     {;; whether or not this profile is enabled
      ;; when disabled it won't be considered at all
      :enabled true

      ;; profile name is (REQUIRED)
      ;;:profile :this-profile

      ;; match specification (REQUIRED)
      ;; :match [:and
      ;;         [:uri :starts-with? "/something/cacheable/"]
      ;;         [:request-method = :get]]

      ;; the duration in seconds for how long ring-boost should
      ;; serve the item from the cache if present
      ;; use `:forever` to cache immutable responses
      ;; default 0
      :cache-for 0}
   ]

   ;; For ADVANCED use only.
   ;; sequence of processing function for this boost configuration
   ;; unless specified differently in a caching profile
   ;; this one will be used.
   :processor-seq
   [{:name :lift-request            }
   {:name :cacheable-profilie       :call cacheable-profilie}
   {:name :request-body-fingerprint :call request-body-fingerprint}
   {:name :cache-lookup             :call cache-lookup      }
   {:name :is-cache-expired?        :call is-cache-expired? }
   {:name :fetch-response           :call fetch-response    }
   {:name :response-cacheable?      :call response-cacheable?}
   {:name :response-body-normalize  :call response-body-normalize}
   {:name :add-cache-headers        :call add-cache-headers }
   {:name :cache-store!             :call cache-store!      }
   {:name :update-cache-stats       :call update-cache-stats}
   {:name :debug-headers            :call debug-headers     }
   {:name :return-response          :call return-response   }]
   }
```

## License

Copyright © 2018 Bruno Bonacci - Distributed under the Apache License v 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
