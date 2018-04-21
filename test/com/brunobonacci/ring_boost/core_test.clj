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
