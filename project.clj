(defn version []
  (-> (slurp "./resources/ring-boost.version") .trim))

(defproject com.brunobonacci/ring-boost #=(version)

  :description "A library to boost performances of Clojure web applications with off-heap serverside caching."

  :url "https://github.com/BrunoBonacci/ring-boost"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/ring-boost.git"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.brunobonacci/clj-sophia "0.4.2"]
                 [com.brunobonacci/where "0.5.0"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:dependencies [[midje "1.9.2-alpha3"]
                                  [org.clojure/test.check "0.9.0"]
                                  [criterium "0.4.4"]]
                   :plugins      [[lein-midje "3.2.1"]]}}
  )
