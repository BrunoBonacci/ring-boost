(ns com.brunobonacci.ring-boost.core
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [com.brunobonacci.sophia :as sph]
            [pandect.algo.md5 :as digest]
            [safely.core :refer [safely]]
            [samsara.trackit :refer [track-rate]]
            [where.core :refer [where]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ---==| B U I L D I N G   F U N C T I O N S |==----             ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def ^:const default-keys
  [:uri :request-method :server-name :server-port :query-string :body-fingerprint])



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
   (filter :enabled profiles)))



(defn load-version
  [config]
  (assoc config :boost-version
         (safely
           (str/trim (slurp (io/resource "ring-boost.version")))
           :on-error
           :default "v???")))



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


;; defined later
(declare default-processor-seq)



(defn after-call
  [{:keys [processor-seq] :as  config} call-name new-call]
  (let [processor-seq (or processor-seq (default-processor-seq))
        pre  (take-while (where :name not= call-name) processor-seq)
        it   (filter (where :name = call-name) processor-seq)
        post (rest (drop-while (where :name not= call-name) processor-seq))]
    (when-not (seq it)
      (throw (ex-info (str call-name " call not found.")
                      {:processor-seq (or processor-seq (default-processor-seq))
                       :call-name call-name})))
    (assoc config :processor-seq
           (concat pre it [new-call] post))))



(defn before-call
  [{:keys [processor-seq] :as  config} call-name new-call]
  (let [processor-seq (or processor-seq (default-processor-seq))
        pre  (take-while (where :name not= call-name) processor-seq)
        it   (filter (where :name = call-name) processor-seq)
        post (rest (drop-while (where :name not= call-name) processor-seq))]
    (when-not (seq it)
      (throw (ex-info (str call-name " call not found.")
                      {:processor-seq (or processor-seq (default-processor-seq))
                       :call-name call-name})))
    (assoc config :processor-seq
           (concat pre [new-call] it post))))



(defn remove-call
  [{:keys [processor-seq] :as  config} call-name]
  (update config :processor-seq
          #(remove
            (where :name = call-name)
            (or % (default-processor-seq)))))



(defn replace-call
  [{:keys [processor-seq] :as  config} call-name  new-call]
  (let [processor-seq (or processor-seq (default-processor-seq))
        pre  (take-while (where :name not= call-name) processor-seq)
        it   (filter (where :name = call-name) processor-seq)
        post (rest (drop-while (where :name not= call-name) processor-seq))]
    (when-not (seq it)
      (throw (ex-info (str call-name " call not found.")
                      {:processor-seq (or processor-seq (default-processor-seq))
                       :call-name call-name})))
    (assoc config :processor-seq
           (concat pre [new-call] post))))



(defn fetch [body]
  (when body
    (cond
      (instance? java.io.InputStream body)
      (let [^java.io.ByteArrayOutputStream out (java.io.ByteArrayOutputStream.)]
        (io/copy body out)
        (.toByteArray out))

      (string? body)
      (let [^String sbody body]
        (.getBytes sbody))

      :else
      body)))



(def byte-array-type
  (type (byte-array 0)))



(defn byte-array?
  [v]
  (= byte-array-type (type v)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;             ---==| C O N T E X T   P R O C E S S I N G |==----             ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  ;;
  ;; This is how the context map looks like
  ;;
  {;; contains the compiled boost configuration and the reference to the database
   :boost {;; whether of not the cache is enabled
           :enabled true/false
           ;; storage layer configuration
           :storage {}
           ;; profile configuration
           :profiles []
           ;; list of context processing function
           :processor-seq []
           ;; current ring-boost version
           :boost-version "x.y.z"
           ;; database reference
           :cache {}
           ;; compiled processor (from :processor-seq)
           :processor fn
           }

   ;; the handler to execute in case of a cache miss
   :handler handler-fn

   ;; the user request
   :req {:request-method :get, :uri "/sample", :body nil}

   ;; the matched boost profile if found, nil otherwise
   :cacheable-profile {:enabled true, :cache-for 10, :profile :test,
                       :match predicate-fn, :matcher fn, :key-maker fn}

   ;; this is the computed key which identifies this request in the cache
   :cache-key "/sample|:get||||"

   ;; the response returned by the handler in case of a cache miss
   ;; or a cached response in cache of cache hit.
   :resp {:status 200 :body [bytes] :headers {"etag" "foo"}}

   ;; whether or not to store the response into the cache
   :resp-cacheable? true/false

   ;; whether or not the response was stored
   :stored true/false

   ;; computed statistics cache hits/misses for the given cache-key
   ;; and the profile as a whole.
   :stats {:key {} :profile {}}
   }
  )



(defn lift-request
  [boost handler]
  (fn [req]
    {:boost boost :handler handler :req req}))



(defn cacheable-profilie
  [{:keys [boost req] :as ctx}]
  (assoc ctx :cacheable-profile
         (matching-profile (:profiles boost) req)))



(defn request-body-fingerprint
  [{:keys [boost cacheable-profile req] :as ctx}]
  (if-not cacheable-profile
    ctx
    (let [body        (fetch (:body req))
          fingerprint (when (byte-array? body) (digest/md5 body))
          normal-body (if (byte-array? body) (java.io.ByteArrayInputStream. body) body)]
      (-> ctx
          (assoc-in [:req :body] normal-body)
          (assoc-in [:req :body-fingerprint] fingerprint)))))



(defn cache-lookup
  [{:keys [boost cacheable-profile req] :as ctx}]
  (if-not cacheable-profile
    ctx
    (let [key* (:key-maker cacheable-profile)
          key  (key* req)]
      (assoc ctx
             :cached (safely
                      (sph/get-value (:cache boost) "cache" key)
                      :on-error
                      :message  "ring-boost cache lookup"
                      :track-as "ring_boost.cache.lookup"
                      :default  nil)
             :cache-key key))))



(defn is-cache-expired?
  [{:keys [cacheable-profile cached] :as ctx}]
  (if-not cached
    ctx
    (let [cache-for (:cache-for cacheable-profile)
          elapsed   (- (System/currentTimeMillis)
                       (or (:timestamp cached) 0))
          expired?  (> elapsed (if (= :forever cache-for)
                                 Long/MAX_VALUE (* 1000 cache-for)))]
      (if expired?
        (dissoc ctx :cached)
        ctx))))



(defn is-skip-cache?
  [{:keys [req cached] :as ctx}]
  (if (and cached (get-in req [:headers "x-cache-skip"] false))
    (dissoc ctx :cached)
    ctx))



(defn- safe-metric-name
  [name]
  (-> (str name)
     (str/replace #"[^a-zA-Z0-9_]+" "_")
     (str/replace #"^_+|_+$" "")))



(defn track-cache-metrics
  [{:keys [cacheable-profile cached resp-cacheable?] :as ctx}]
  (if cacheable-profile
    (let [profile (safe-metric-name (:profile cacheable-profile))]
      (if cached
        (track-rate (str "ring_boost.profile." profile ".hit_rate"))
        (track-rate (str "ring_boost.profile." profile ".miss_rate")))
      (when (and (not cached) (not resp-cacheable?))
        (track-rate (str "ring_boost.profile." profile ".not_cacheable_rate"))))
    (track-rate "ring_boost.no_profile.rate"))
  ctx)



(defn- increment-stats-by!
  "increments the stats for a given profile/key by the given amount"
  [boost {:keys [profile key hit miss not-cacheable]}]
  (safely
   (sph/transact! (:cache boost)
     (fn [tx]
       (sph/upsert-value! tx "stats" (str profile ":" key)
                          (fnil (partial merge-with +) {:profile profile
                                                  :key key
                                                  :hit  0
                                                  :miss 0
                                                  :not-cacheable 0})
                          {:hit hit :miss miss :not-cacheable not-cacheable})

       (sph/upsert-value! tx "stats" (str profile)
                          (fnil (partial merge-with +) {:profile profile
                                                  :hit  0
                                                  :miss 0
                                                  :not-cacheable 0})
                          {:hit hit :miss miss :not-cacheable not-cacheable})))
   :on-error
   :max-retry 3
   :message  "ring-boost update statistics"
   :track-as "ring_boost.stats.update"))



(defn- async-do!
  "It runs the given function presumably with side effect
   without affecting the state of the agent."
  [f]
  (fn [state & args]
    (apply f args)
    state))



(defn update-cache-stats
  [{:keys [boost cacheable-profile cache-key resp cached resp-cacheable?] :as ctx}]
  (when cacheable-profile
    ;; asynchronously update the stats
    (send-off (:async-agent boost)
              (async-do! increment-stats-by!) boost
              {:profile (:profile cacheable-profile)
               :key cache-key
               :hit  (if cached 1 0)
               :miss (if cached 0 1)
               :not-cacheable (if (and (not cached) (not resp-cacheable?)) 1 0)}))
  ctx)



(defn fetch-cache-stats
  [{:keys [boost cacheable-profile cache-key req cached resp-cacheable?] :as ctx}]
  (if (and cacheable-profile (get-in req [:headers "x-cache-debug"]))
    (safely
      (let [profile (:profile cacheable-profile)
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
                                    :not-cacheable 0})]
        (assoc ctx :stats {:key stats :profile pstats}))
      ;; if transaction fail, ignore stats update
      ;; faster times are more important than accurate
      ;; stats. Maybe consider to update stats async.
      :on-error
      :default ctx
      :message  "ring-boost fetch statistics"
      :track-as "ring_boost.stats.fecth")
    ctx))



(defn fetch-response
  [{:keys [req cached handler] :as ctx}]
  (assoc ctx :resp (if cached (:payload cached) (handler req))))



(defn response-cacheable?
  [{:keys [req resp cached handler] :as ctx}]
  (assoc ctx :resp-cacheable?
         (and resp (<= 200 (:status resp) 299))))



(defn cache-store!
  [{:keys [boost cacheable-profile cache-key resp cached resp-cacheable?] :as ctx}]
  ;; when the user asked to cache this request
  ;; and this response didn't come from the cache
  ;; but it was fetched and the response is cacheable
  ;; then save it into the cache
  (let [stored? (when (and cacheable-profile (not cached) resp-cacheable?)
                  (let [data {:timestamp (System/currentTimeMillis) :payload resp}]
                    (safely
                     (sph/set-value! (:cache boost) "cache" cache-key data)
                     :on-error
                     :message  "ring-boost cache store"
                     :track-as "ring_boost.cache.store"
                     :default  false)))]
    (assoc ctx :stored (boolean stored?))))



(defn return-response
  [{:keys [resp]}]
  (if (byte-array? (:body resp))
    (update resp :body #(java.io.ByteArrayInputStream. %))
    resp))



(defn debug-headers
  [{:keys [req cached boost] :as ctx}]
  (if (get-in req [:headers "x-cache-debug"])
    (update ctx :resp
            (fn [resp]
              (when resp
                (update resp :headers (fnil conj {})
                        {"X-CACHE" (str "RING-BOOST/v" (:boost-version boost))
                         "X-RING-BOOST-CACHE" (if (:cached ctx) "CACHE-HIT" "CACHE-MISS")
                         "X-RING-BOOST-CACHE-PROFILE" (or (str (-> ctx :cacheable-profile :profile)) "unknown")}
                        (when (:stats ctx)
                          {"X-RING-BOOST-CACHE-STATS1"
                           (str/join "/"
                                     [(get-in ctx [:stats :key :hit])
                                      (get-in ctx [:stats :key :miss])
                                      (get-in ctx [:stats :key :not-cacheable])])
                           "X-RING-BOOST-CACHE-STATS2"
                           (str/join "/"
                                     [(get-in ctx [:stats :profile :hit])
                                      (get-in ctx [:stats :profile :miss])
                                      (get-in ctx [:stats :profile :not-cacheable])])})))))
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
      (instance? java.io.InputStream (:body resp))
      (update-in ctx [:resp :body] fetch)

      (string? (:body resp))
      (update-in ctx [:resp :body]
                 (fn [^String body] (.getBytes body)))

      :else
      ctx)))



(defn add-cache-headers
  [{:keys [resp] :as ctx}]
  (if (and (byte-array? (get resp :body))
         (not (or (get-in resp [:headers "etag"])
                 (get-in resp [:headers "ETag"]))))
    (assoc-in ctx [:resp :headers "etag"]
              (digest/md5 (get resp :body)))
    ctx))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;         ----==| D E F A U L T   C O N F I G U R A T I O N |==----          ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defn default-processor-seq []
  [{:name :lift-request         }
   {:name :cacheable-profilie       :call cacheable-profilie}
   {:name :request-body-fingerprint :call request-body-fingerprint}
   {:name :cache-lookup             :call cache-lookup      }
   {:name :is-cache-expired?        :call is-cache-expired? }
   {:name :is-skip-cache?           :call is-skip-cache?    }
   {:name :fetch-response           :call fetch-response    }
   {:name :response-cacheable?      :call response-cacheable?}
   {:name :response-body-normalize  :call response-body-normalize}
   {:name :add-cache-headers        :call add-cache-headers }
   {:name :cache-store!             :call cache-store!      }
   {:name :track-cache-metrics      :call track-cache-metrics}
   {:name :update-cache-stats       :call update-cache-stats}
   {:name :fetch-cache-stats        :call fetch-cache-stats }
   {:name :debug-headers            :call debug-headers     }
   {:name :return-response          :call return-response   }])



(def ^:const default-profile-config
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
   :cache-for 0})



(def default-boost-config
  {;; Whether the ring-boost cache is enabled or not.
   ;; when not enabled the handler will behave as if
   ;; the ring-boost wasn't there at all.
   :enabled true

   ;; cache storage configuration
   :storage {:sophia.path "/tmp/ring-boost-cache" :dbs ["cache" "stats"]}

   ;; caching profiles. see `default-profile-config`
   :profiles []

   ;; sequence of processing function for this boost configuration
   ;; unless specified differently in a caching profile
   ;; this one will be used.
   :processor-seq (default-processor-seq)

   ;; agent for async operations
   ;; like updating the stats
   :async-agent (agent {})
   })



(defn deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (let [maps (filter (comp not nil?) maps)]
    (if (every? map? maps)
      (apply merge-with deep-merge maps)
      (last maps))))



(defn- apply-defaults
  [config]
  (-> (deep-merge default-boost-config config)
      (update :profiles (partial mapv (partial deep-merge default-profile-config)))))



(defn ring-boost
  [handler boost-config]

  (let [{:keys [enabled] :as conf}
        (-> boost-config apply-defaults compile-configuration)]
    (if-not enabled
      ;; if boost is not enabled, then just return the handler
      handler
      (let [processor (comp (:processor conf) (lift-request conf handler))]
        (fn [req]
          (processor req))))))
