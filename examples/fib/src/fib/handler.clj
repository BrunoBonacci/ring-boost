(ns fib.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [com.brunobonacci.ring-boost :refer [ring-boost]]))


;; Purposely not the most efficient implementation
(defn fib
  "generates a Fibonacci sequence and returns the nth position number
  starting from position 0."
  [n]
  (->> (iterate (fn [[a b]] [b (+' a b)]) [0 1])
       (map second)
       (#(nth % n))))



(defn parse-num
  "Converts a string into a number when possible, otherwise it returns nil"
  [num]
  (try
    (Long/parseLong num)
    (catch Exception _
      nil)))



(defroutes app-routes
  (GET "/fib/:pos" [pos]
       (let [position (parse-num pos)]
         (if (and position (>= position 0))
           {:status 200 :body {:status "OK" :pos position :fib (fib position)}}
           {:status 400 :body {:status "ERROR"
                               :message "Invalid position number. Please provide a positive integer."}})))

  (route/not-found "Not Found"))



(def boost-config
  {:profiles
   [{:profile :fib-numbers
     :match [:and
             [:uri :starts-with? "/fib/"]
             [:request-method = :get]]
     ;; cache for 30 sec
     :cache-for 30}]})



(def app
  (-> app-routes
      (wrap-json-response)
      (wrap-json-body {:keywords? true})
      ;; add ring-boost
      (ring-boost boost-config)))
