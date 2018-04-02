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



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                       ---==| R E F A C T O R |==----                       ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  {:req
   :resp
   :cached
   :boost {:profiles, :cache}
   :handler

   :cacheable-profile
   :cache-key
   })


(defn lift-request
  [req boost handler]
  {:req req :boost boost :handler handler})


(defn cacheable-profilie
  [{:keys [boost req] :as ctx}]
  (assoc ctx :cacheable-profile
         (matching-profile (:profiles boost) req)))

(defn cache-lookup
  [{:keys [boost cacheable-profile req] :as ctx}]
  (if-not cacheable-profile
    ctx
    (let [key* (:key-maker cacheable-profile)
          key  (key* req)]
      (assoc ctx
             :cached (sph/get-value (:cache boost) "cache" key)
             :cache-key key))))

(defn is-cache-expired?
  [{:keys [cacheable-profile cached] :as ctx}]
  (if-not cached
    ctx
    (let [cache-for (:cache-for cacheable-profile)
          elapsed   (- (System/currentTimeMillis)
                       (or (:timestamp cached) 0))
          expired?  (> elapsed (* (if (= :forever cache-for)
                                    Long/MAX_VALUE cache-for) 1000))]
      (if expired?
        (dissoc ctx :cached)
        ctx))))


(defn update-cache-stats
  [{:keys [boost cached] :as ctx}]
  ctx)

(defn fetch-response
  [{:keys [req cached handler] :as ctx}]
  (assoc ctx :resp (if cached (:payload cached) (handler req))))

(defn cache-store!
  [{:keys [boost cacheable-profile cache-key resp cached] :as ctx}]
  (when (and cacheable-profile (not cached) resp)
    (let [data {:timestamp (System/currentTimeMillis) :payload resp}]
      (sph/set-value! (:cache boost) "cache" cache-key data)))
  ctx)

(defn return-response
  [{:keys [resp]}]
  resp)

(defn debug-headers
  [{:keys [req cached] :as ctx}]
  (if (get-in req [:headers "x-cache-debug"])
    (update ctx :resp
            (fn [resp]
              (when resp
                (update resp :headers conj
                        {"X-CACHE" "RING-BOOST/v0.1.0" ;;TODO:version
                         "X-RING-BOOST-CACHE" (if (:cached ctx) "CACHE-HIT" "CACHE-MISS")
                         "X-RING-BOOST-CACHE-PROFILE" (or (str (-> ctx :cacheable-profile :profile)) "unknown")}))))
    ctx)
  )



(comment

  (def boost (compile-configuration boost-config))

  (-> {:uri "/pictures/big1" :request-method :get
       :headers {"x-cache-debug" "1"}}
      (lift-request boost handler)
      cacheable-profilie
      cache-lookup
      is-cache-expired?
      update-cache-stats
      fetch-response
      cache-store!
      debug-headers
      return-response)

  (sph/get-value (:cache boost) "cache" ":get|||/pictures/big1|")
  (sph/set-value! (:cache boost) "cache" ":get|||/pictures/big1|"
                 {:timestamp (System/currentTimeMillis), :payload {:status 200, :body "slow"}})
  )
