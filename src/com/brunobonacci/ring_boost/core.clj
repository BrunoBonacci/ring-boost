(ns com.brunobonacci.ring-boost.core
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [com.brunobonacci.sophia :as sph]
            [where.core :refer [where]]
            [clojure.java.io :as io]
            [pandect.algo.md5 :as digest]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ---==| B U I L D I N G   F U N C T I O N S |==----             ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const default-keys
  [:uri :request-method :server-name :server-port :query-string])



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



(defn load-version
  [config]
  (assoc config :boost-version
         (str/trim (slurp (io/resource "ring-boost.version")))))



(defn compile-processor
  [{:keys [processor-seq] :as boost-config}]
  (->> (drop 1 processor-seq)
       reverse
       (map :call)
       (apply comp)))



(defn debug-compile-processor
  [{:keys [processor-seq] :as boost-config}]
  (->> processor-seq
       (map (fn [{:keys [name call]}]
              {:name name
               :call (fn [ctx]
                       (println "calling: " name)
                       (call ctx))}))
       (drop 1)
       reverse
       (map :call)
       (apply comp)))



(defn compile-configuration
  [boost-config]
  (as-> boost-config $
    (load-version $)
    (update $ :profiles build-profiles)
    (assoc $ :cache
           (sph/sophia (:storage $)))
    (assoc $ :processor (compile-processor $))))



(defn after-call
  [{:keys [processor-seq] :as  config} call-name new-call]
  (let [pre  (take-while (where :name not= call-name) processor-seq)
        it   (filter (where :name = call-name) processor-seq)
        post (rest (drop-while (where :name not= call-name) processor-seq))]
    (concat pre it [new-call] post)))



(defn before-call
  [{:keys [processor-seq] :as  config} call-name new-call]
  (let [pre  (take-while (where :name not= call-name) processor-seq)
        it   (filter (where :name = call-name) processor-seq)
        post (rest (drop-while (where :name not= call-name) processor-seq))]
    (concat pre [new-call] it post)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ---==| C O N T E X T   P R O C E S S I N G |==----             ;;
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
  [boost handler]
  (fn [req]
    {:boost boost :handler handler :req req}))



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
  [{:keys [boost cacheable-profile cache-key resp cached resp-cacheable?] :as ctx}]
  (if cacheable-profile
    (assoc ctx :stats
           (sph/with-transaction [tx (sph/begin-transaction (:cache boost))]
             (let [hit+      (if cached inc identity)
                   miss+     (if cached identity inc)
                   cachenot+ (if (and (not cached) (not resp-cacheable?)) inc identity)
                   profile (:profile cacheable-profile)
                   stats   (sph/get-value (:cache boost) "stats"
                                          (str profile ":" cache-key)
                                          {:profile profile
                                           :key cache-key
                                           :hit  0
                                           :miss 0
                                           :not-cacheable 0})
                   pstats  (sph/get-value (:cache boost) "stats"
                                          (str profile)
                                          {:profile profile
                                           :hit  0
                                           :miss 0
                                           :not-cacheable 0})
                   stats (sph/set-value! (:cache boost) "stats"
                                         (str profile ":" cache-key)
                                         (-> stats
                                             (update :hit  hit+)
                                             (update :miss miss+)
                                             (update :not-cacheable cachenot+)))
                   pstats (sph/set-value! (:cache boost) "stats"
                                          (str profile)
                                          (-> pstats
                                              (update :hit  hit+)
                                              (update :miss miss+)
                                              (update :not-cacheable cachenot+)))]
               {:key stats :profile stats})))
    ctx))



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
  [{:keys [req cached boost] :as ctx}]
  (if (get-in req [:headers "x-cache-debug"])
    (update ctx :resp
            (fn [resp]
              (when resp
                (update resp :headers (fnil conj {})
                        {"X-CACHE" (str "RING-BOOST/v" (:boost-version boost))
                         "X-RING-BOOST-CACHE" (if (:cached ctx) "CACHE-HIT" "CACHE-MISS")
                         "X-RING-BOOST-CACHE-PROFILE" (or (str (-> ctx :cacheable-profile :profile)) "unknown")}))))
    ctx))



(defn debug-print-context
  [ctx]
  (pprint ctx)
  ctx)



(defn response-body-normalize
  [{:keys [cacheable-profile resp cached] :as ctx}]
  (if-not (and cacheable-profile (not cached) (:body resp))
    ctx
    (cond
      (string? (:body resp))
      (update-in ctx [:resp :body]
                 (fn [^String body] (.getBytes body)))

      :else
      ctx)))



(def byte-array-type
  (type (byte-array 0)))



(defn byte-array?
  [v]
  (= byte-array-type (type v)))



(defn add-cache-headers
  [{:keys [resp] :as ctx}]
  (if (and (byte-array? (get resp :body))
         (not (or (get-in resp [:headers "etag"])
                 (get-in resp [:headers "ETag"]))))
    (assoc-in ctx [:resp :headers "etag"]
              (digest/md5 (get resp :body)))
    ctx))



(def ^:const default-processor-seq
  [{:name :lift-request         }
   {:name :cacheable-profilie      :call cacheable-profilie}
   {:name :cache-lookup            :call cache-lookup      }
   {:name :is-cache-expired?       :call is-cache-expired? }
   {:name :update-cache-stats      :call update-cache-stats}
   {:name :fetch-response          :call fetch-response    }
   {:name :response-body-normalize :call response-body-normalize}
   {:name :add-cache-headers       :call add-cache-headers }
   {:name :cache-store!            :call cache-store!      }
   {:name :debug-headers           :call debug-headers     }
   {:name :return-response         :call return-response   }])



(defn ring-boost
  [handler {:keys [enabled] :as boost-config}]

  (if-not enabled
    ;; if boost is not enabled, then just return the handler
    handler
    (let [conf      (compile-configuration boost-config)
          processor (comp (:processor conf) (lift-request conf handler))]
      (fn [req]
        (processor req)))))



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
     [{:profile :pictures
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
      ]
     :processor-seq
     [{:name :lift-request         }
      {:name :cacheable-profilie   :call cacheable-profilie}
      {:name :cache-lookup         :call cache-lookup      }
      {:name :is-cache-expired?    :call is-cache-expired? }
      {:name :update-cache-stats   :call update-cache-stats}
      {:name :fetch-response       :call fetch-response    }
      {:name :cache-store!         :call cache-store!      }
      {:name :debug-headers        :call debug-headers     }
      {:name :return-response      :call return-response   }]})


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
   (boosted-site {:uri "/pictures/big" :request-method :get
                  :headers {"x-cache-debug" "1"}}))

  (time
   (boosted-site {:uri "/pictures/big1" :request-method :get}))

  ;; cacheable slow query (NOW REALLY FAST as in CACHE) 0.3 ms
  (time
   (boosted-site {:uri "/pictures/big" :request-method :get}))

  )




(comment


  (def boost (compile-configuration boost-config))

  (-> {:uri "/pictures/big" :request-method :get
       :headers {"x-cache-debug" "1"}}
      ((lift-request boost handler))
      cacheable-profilie
      cache-lookup
      is-cache-expired?
      update-cache-stats
      fetch-response
      response-body-normalize
      add-cache-headers
      cache-store!
      debug-headers
      ((fn [ctx]
         (dissoc ctx :boost :handler :req)))
      #_return-response)

  )
