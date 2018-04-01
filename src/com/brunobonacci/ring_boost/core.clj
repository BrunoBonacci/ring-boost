(ns com.brunobonacci.ring-boost.core
  (:require [where.core :refer [where]]
            [clojure.string :as str]
            [com.brunobonacci.sophia :as sph]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ---==| B U I L D I N G   F U N C T I O N S |==----             ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-matcher
  [{:keys [match matcher] :as profile}]
  (if matcher
    profile
    (assoc profile :matcher (where match))))



(defn matching-profile
  [profiles request]
  (some (fn [{:keys [matcher] :as p}]
          (when (and matcher (matcher request))
            p)) profiles))



(def default-keys
  [:request-method :server-name :server-port :uri :query-string])



(defn make-key
  [segments]
  (->> segments
       (map (fn [k]
            (if (vector? k)
              #(get-in % k)
              #(get % k))))
       (apply juxt)
       (comp (partial str/join "|"))))



(defn build-key-maker
  [{:keys [keys key-maker] :as profile}]
  (if key-maker
    profile
    (assoc profile :key-maker (make-key (or keys default-keys)))))



(defn build-profiles
  [profiles]
  (mapv
   (comp build-key-maker
      build-matcher)
   profiles))



(defn compile-configuration
  [boost-config]
  (as-> boost-config $
    (update $ :profiles build-profiles)
    (assoc $ :cache
           (sph/sophia (:storage boost-config)))))



(defn ring-boost
  [handler {:keys [enabled] :as boost-config}]

  (if-not enabled
    ;; if boost is not enabled, then just return the handler
    handler
    (let [conf     (compile-configuration boost-config)
          profiles (:profiles conf)
          cache    (:cache conf)]
      (fn [req]
        (let [cacheable-profilie? (matching-profile profiles req)]
          (if-not cacheable-profilie?
            (handler req)
            ;;if cacheable, then let's check in the cache
            (let [key*      (:key-maker cacheable-profilie?)
                  key       (key* req)
                  cached    (sph/get-value cache "cache" key)
                  cache-for (:cache-for cacheable-profilie?)
                  elapsed   (- (System/currentTimeMillis)
                               (or (:timestamp cached) 0))
                  expired?  (> elapsed (* (if (= :forever cache-for)
                                           Long/MAX_VALUE cache-for) 1000))]
              (if (and cached (not expired?))
                ;; cache hit
                ;; 1 - update stats
                (assoc-in (:payload cached)
                          [:headers "X-RING-BOOST"] "CACHE-HIT" )
                ;; cache miss
                (let [{:keys [status] :as resp} (handler req)]
                  (if (< status 300)
                    (let [data {:payload resp
                                :timestamp (System/currentTimeMillis)}]
                      (sph/set-value! cache "cache" key data)
                      (assoc-in resp [:headers "X-RING-BOOST"] "CACHE-MISS"))
                    resp))))))))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                        ---==| E X A M P L E |==----                        ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment



  (def boost-config
    {:enabled true
     :storage {:sophia.path "/tmp/ring-boost-cache" :dbs ["cache" "stats"]}
     :profiles
     [{:profile :forever
       :match [:and
               [:uri :starts-with? "/pictures/"]
               [:request-method = :get]]
       :cache-for 5}


      {:profile :private-pages
       :match [:and
               [:uri :starts-with? "/profiles/"]
               [:method = :get]
               [(comp :user-id :params) not= nil]]
       :cache-for :forever}
      ]})


  (def handler
    (fn [{:keys [uri] :as req}]
      (if (str/starts-with? uri "/pictures/big")
        (do
          (Thread/sleep 2000)
          {:status 200 :body "slow"})
        {:status 200 :body "fast"})))


  (def boosted-site
    (-> handler
        (ring-boost boost-config)))

  ;; non cacheable
  (time
   (boosted-site {:uri "/something-else" :request-method :get}))

  ;; cacheable slow query (first time slow) 2000 ms
  (time
   (boosted-site {:uri "/pictures/big" :request-method :get}))

  (time
   (boosted-site {:uri "/pictures/big1" :request-method :get}))

  ;; cacheable slow query (NOW REALLY FAST as in CACHE) 0.3 ms
  (time
   (boosted-site {:uri "/pictures/big" :request-method :get}))

  )
