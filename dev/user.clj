(ns user
  (:require [com.brunobonacci.ring-boost.core :refer :all]
            [com.brunobonacci.ring-boost.test-utils :as u]))


(comment

  ;; sample profile
  (def profile
    (->
     {:profiles [{:profile :test
                  :match [:and
                          [:uri = "/sample"]
                          [:request-method = :get]]
                  :cache-for 10}]}
     ;; remove last call to see the full context
     (remove-call :return-response)))


  ;; sample handler
  (def handler
    (fn [r] {:status 200 :body "OK"}))


  ;; boosted handler
  (def boost (ring-boost handler profile))


  ;; sample request
  (def req
    {:request-method :get :uri "/sample"})

  ;; process request
  (boost req)

  (-> (boost req) (u/select [:resp :resp-cacheable? :stored :stats]))
  ;; => {:resp
  ;;     {:status 200,
  ;;      :body [79, 75],
  ;;      :headers {"etag" "e0aa021e21dddbd6d8cecec71e9cf564"}},
  ;;     :resp-cacheable? true,
  ;;     :stored true,
  ;;     :stats
  ;;     {:key
  ;;      {:profile :test,
  ;;       :key "/sample|:get||||",
  ;;       :hit 8,
  ;;       :miss 8,
  ;;       :not-cacheable 0},
  ;;      :profile {:profile :test, :hit 8, :miss 8, :not-cacheable 0}}}

  )
