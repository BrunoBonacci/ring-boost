(ns com.brunobonacci.ring-boost.core
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [com.brunobonacci.sophia :as sph]
            [where.core :refer [where]]
            [clojure.java.io :as io]
            [pandect.algo.md5 :as digest])
  (:import [java.io InputStream ByteArrayOutputStream
            ByteArrayInputStream]))


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
         (try
           (str/trim (slurp (io/resource "ring-boost.version")))
           (catch Exception _ "v???"))))



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
    (concat pre it [new-call] post)))



(defn before-call
  [{:keys [processor-seq] :as  config} call-name new-call]
  (let [processor-seq (or processor-seq (default-processor-seq))
        pre  (take-while (where :name not= call-name) processor-seq)
        it   (filter (where :name = call-name) processor-seq)
        post (rest (drop-while (where :name not= call-name) processor-seq))]
    (concat pre [new-call] it post)))



(defn remove-call
  [{:keys [processor-seq] :as  config} call-name]
  (update config :processor-seq
          #(remove
            (where :name = call-name)
            (or % (default-processor-seq)))))



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
             :cached (sph/get-value (:cache boost) "cache" key)
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



(defn update-cache-stats
  [{:keys [boost cacheable-profile cache-key resp cached resp-cacheable?] :as ctx}]
  (if cacheable-profile
    (try
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
                 {:key stats :profile pstats})))
      ;; if transaction fail, ignore stats update
      ;; faster times are more important than accurate
      ;; stats. Maybe consider to update stats async.
      (catch Exception _
        ctx))
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
  (let [store? (and cacheable-profile (not cached) resp-cacheable?)]
    (when store?
      (let [data {:timestamp (System/currentTimeMillis) :payload resp}]
        (sph/set-value! (:cache boost) "cache" cache-key data)))
    (assoc ctx :stored store?)))



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
   {:name :update-cache-stats       :call update-cache-stats}
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



(def ^:const default-boost-config
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
   :processor-seq (default-processor-seq)})



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
