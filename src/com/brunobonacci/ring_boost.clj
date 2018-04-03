(ns com.brunobonacci.ring-boost
  (:require [com.brunobonacci.ring-boost.core :as rb]))



(defn ring-boost
  [handler {:keys [enabled] :as boost-config}]
  (rb/ring-boost handler boost-config))
