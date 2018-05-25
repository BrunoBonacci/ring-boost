(ns com.brunobonacci.ring-boost.core-test
  (:require [com.brunobonacci.ring-boost.core :refer :all]
            [com.brunobonacci.ring-boost.test-utils :refer :all]
            [midje.sweet :refer :all]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                      ---==| U T I L I T I E S |==----                      ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(fact "deep-merge: it returns a map merged recursively"
      (deep-merge {:x "xyz"})
      => {:x "xyz"}

      (deep-merge {:x "xyz"} nil)
      => {:x "xyz"}

      (deep-merge {:x 1}{:y 2})
      => {:x 1, :y 2}

      (deep-merge {:x {:k 1}}{:x {:z 2}})
      => {:x {:k 1, :z 2}}

      (deep-merge {:x {:k 1}, :y {:k 1}} {:x {:z 2}, :y {:z 2}})
      => {:x {:k 1, :z 2}, :y {:k 1, :z 2}}

      (deep-merge {:x 1} {:x 2} {:x 3})
      => {:x 3})



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                           ---==| C O R E |==----                           ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(fact

 "When a request matches a given profile, and the response is cacheable
  then it should be cached."

 (with-test-database
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "OK"})
    ;; extract from the context the following info
    [:resp-cacheable? {:cacheable-profile [:profile]} :stored]))

 => {:resp-cacheable? true, :cacheable-profile {:profile :test} :stored true}
 )



(fact

 "When a request DOESN'T matche a given profile, and the response is cacheable
  then it should NOT be cached."

 (with-test-database
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}]}
    ;; when it received this request
    {:request-method :get :uri "/another"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "OK"})
    ;; extract from the context the following info
    [:cacheable-profile]))

 => {:cacheable-profile nil}
 )



(fact

 "Profiles are processed in the given order, if multiple profiles
  match the request, the first one which appears in the configuration
  is the one used for the configuration"

 (with-test-database
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test1
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}
                {:profile :test2
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "OK"})
    ;; extract from the context the following info
    [:resp-cacheable? {:cacheable-profile [:profile]}]))

 => {:resp-cacheable? true, :cacheable-profile {:profile :test1}}
 )



(fact

 "Disabled profiles are excluded from the config."

 (with-test-database
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test1
                 :enabled false
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}
                {:profile :test2
                 :match [:and
                         [:uri = "/foo"]
                         [:request-method = :get]]
                 :cache-for 10}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "OK"})
    ;; extract from the context the following info
    [:cacheable-profile]))

 => {:cacheable-profile nil}
 )



(facts

 "The matching logic can be overwritten with the use of the `:matcher` key"

 (with-test-database
   (fact
    "when only :matcher is provided. positive case"
    (process-request
     ;; given this boost config
     {:profiles [{:profile :test
                  :matcher (constantly true)
                  :cache-for 10}]}
     ;; when it received this request
     {:request-method :get :uri "/sample"}
     ;; with this processing handler
     (fn [r] {:status 200 :body "OK"})
     ;; extract from the context the following info
     [{:cacheable-profile [:profile]}])
    => {:cacheable-profile {:profile :test}})


   (fact
    "when only :matcher is provided. negative case"
    (process-request
     ;; given this boost config
     {:profiles [{:profile :test
                  :matcher (constantly false)
                  :cache-for 10}]}
     ;; when it received this request
     {:request-method :get :uri "/sample"}
     ;; with this processing handler
     (fn [r] {:status 200 :body "OK"})
     ;; extract from the context the following info
     [:cacheable-profile])
    => {:cacheable-profile nil})


   (fact
    "when both :matcher and :match are provided"
    (process-request
     ;; given this boost config
     {:profiles [{:profile :test
                  ;; this is ignored, the :matcher function is used
                  :match [:and
                          [:uri = "/sample"]
                          [:request-method = :get]]
                  :matcher (constantly false)
                  :cache-for 10}]}
     ;; when it received this request
     {:request-method :get :uri "/sample"}
     ;; with this processing handler
     (fn [r] {:status 200 :body "OK"})
     ;; extract from the context the following info
     [:cacheable-profile])

    => {:cacheable-profile nil})

   ))



(fact

 "If the requests is not cacheable it won't be stored"

 (with-test-database
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test1
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 500 :body "BAD"})
    ;; extract from the context the following info
    [{:cacheable-profile [:profile]} :stored :resp-cacheable?]))

 => {:cacheable-profile {:profile :test1}
     :resp-cacheable? false :stored false}
 )



(fact

 "When a request is made for a item which is already cached,
  and not expired the cached item should be returned."

 (with-test-database

   ;; initial request - should be stored
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 10}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "first"})
    ;; extract from the context the following info
    [:resp-cacheable? {:cacheable-profile [:profile]} :stored])

   => {:resp-cacheable? true, :cacheable-profile {:profile :test} :stored true}


   ;; second request - should be returned
   (->
    (process-request
     ;; given this boost config
     {:profiles [{:profile :test
                  :match [:and
                          [:uri = "/sample"]
                          [:request-method = :get]]
                  :cache-for 10}]}
     ;; when it received this request
     {:request-method :get :uri "/sample"}
     ;; with this processing handler
     (fn [r] {:status 200 :body "second"})
     ;; extract from the context the following info
     [:resp-cacheable? {:cacheable-profile [:profile]}
      {:cached [:payload]} :resp])
    (update-in [:cached :payload :body] ->str)
    (update-in [:resp :body] ->str))

   => {:resp-cacheable? true,
       :cacheable-profile {:profile :test},
       :cached
       {:payload
        {:body "first"
         :headers {"etag" "8b04d5e3775d298e78455efc5ca404d5"}
         :status 200}}

       :resp
       {:body "first"
        :headers {"etag" "8b04d5e3775d298e78455efc5ca404d5"}
        :status 200}}
   ))



(fact

 "When a request is made for a item which is already cached,
  but expired the handler should be called."

 (with-test-database

   ;; initial request - should be stored
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 1}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "first"})
    ;; extract from the context the following info
    [:resp-cacheable? {:cacheable-profile [:profile]} :stored])

   => {:resp-cacheable? true, :cacheable-profile {:profile :test} :stored true}

   ;; expire the cache
   (Thread/sleep 1100)

   ;; second request - handler should be called
   (->
    (process-request
     ;; given this boost config
     {:profiles [{:profile :test
                  :match [:and
                          [:uri = "/sample"]
                          [:request-method = :get]]
                  :cache-for 1}]}
     ;; when it received this request
     {:request-method :get :uri "/sample"}
     ;; with this processing handler
     (fn [r] {:status 200 :body "second"})
     ;; extract from the context the following info
     [:resp-cacheable? {:cacheable-profile [:profile]}
      :cached :resp])
    (update-in [:resp :body] ->str))

   => {:resp-cacheable? true,
       :cacheable-profile {:profile :test},

       :resp
       {:status 200,
        :body "second",
        :headers {"etag" "a9f0e61a137d86aa9db53465e0801612"}}}
   ))




(fact

 "When a request is made for a item which is already cached,
  but the x-cache-skip header is present the handler should be called."

 (with-test-database

   ;; initial request - should be stored
   (process-request
    ;; given this boost config
    {:profiles [{:profile :test
                 :match [:and
                         [:uri = "/sample"]
                         [:request-method = :get]]
                 :cache-for 1}]}
    ;; when it received this request
    {:request-method :get :uri "/sample"}
    ;; with this processing handler
    (fn [r] {:status 200 :body "first"})
    ;; extract from the context the following info
    [:resp-cacheable? {:cacheable-profile [:profile]} :stored])

   => {:resp-cacheable? true, :cacheable-profile {:profile :test} :stored true}

   ;; second request - handler should be called
   (->
    (process-request
     ;; given this boost config
     {:profiles [{:profile :test
                  :match [:and
                          [:uri = "/sample"]
                          [:request-method = :get]]
                  :cache-for 1}]}
     ;; when it received this request
     {:request-method :get :uri "/sample" :headers {"x-cache-skip" "1"}}
     ;; with this processing handler
     (fn [r] {:status 200 :body "second"})
     ;; extract from the context the following info
     [:resp-cacheable? {:cacheable-profile [:profile]}
      :cached :resp])
    (update-in [:resp :body] ->str))

   => {:resp-cacheable? true,
       :cacheable-profile {:profile :test},

       :resp
       {:status 200,
        :body "second",
        :headers {"etag" "a9f0e61a137d86aa9db53465e0801612"}}}
   ))
