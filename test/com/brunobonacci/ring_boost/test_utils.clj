(ns com.brunobonacci.ring-boost.test-utils
  (:require [com.brunobonacci.ring-boost.core :as rb]
            [clojure.java.io :as io]))



(defn uuid []
  (str (java.util.UUID/randomUUID)))



(defn rand-db-name []
  (str "/tmp/test-ring-boost-" (uuid)))



(defn rand-db [conf db-name]
  (assoc-in conf [:storage :sophia.path] db-name))



(defn rm-fr
  [f & {:keys [force] :or {force true}}]
  (let [^java.io.File f (io/file f)]
    (if (.isDirectory f)
      (run! #(rm-fr % :force force) (.listFiles f)))
    (io/delete-file f force)))


(def ^:dynamic *db-name* nil)
(defmacro with-test-database
  [& body]
  `(binding [*db-name* (rand-db-name)]
     (try
       ~@body
       (finally
         (try
           (rm-fr *db-name*)
           (catch Exception _#))))))





(defprotocol Selector
  (-select [s m]))



(defn select [m selectors-coll]
  (reduce conj {} (map #(-select % m) selectors-coll)))



(extend-protocol Selector
  clojure.lang.Keyword
  (-select [k m]
    (find m k))
  clojure.lang.APersistentMap
  (-select [sm m]
    (into {}
          (for [[k s] sm]
            [k (select (get m k) s)]))))



(defn process-request
  ([config request handler sel-keys]
   (-> (process-request config request handler)
       (select sel-keys)))
  ([config request handler]
   (let [config' (as-> config $
                   (if *db-name* (rand-db $ *db-name*) $)
                   (rb/remove-call $ :return-response))]
     ((rb/ring-boost handler config') request))))
