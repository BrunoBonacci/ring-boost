(ns com.brunobonacci.ring-boost)

(defn boost
  [hander config]
  (fn [req]
    (hander req)))
