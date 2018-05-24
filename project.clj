(defn version []
  (-> (slurp "./resources/ring-boost.version") .trim))

(defproject com.brunobonacci/ring-boost #=(version)

  :description "A library to boost performances of Clojure web applications with off-heap serverside caching."

  :url "https://github.com/BrunoBonacci/ring-boost"

  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :scm {:name "git" :url "https://github.com/BrunoBonacci/ring-boost.git"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.brunobonacci/clj-sophia "0.4.4"]
                 [com.brunobonacci/where "0.5.0"]
                 [pandect "0.6.1"]]

  :global-vars {*warn-on-reflection* true}

  :jvm-opts ["-server"]

  :profiles {:dev {:dependencies [[midje "1.9.2-alpha3"]
                                  [org.clojure/test.check "0.9.0"]
                                  [criterium "0.4.4"]
                                  [org.slf4j/slf4j-log4j12 "1.7.25"]]
                   :resource-paths ["dev-resources"]
                   :plugins      [[lein-midje "3.2.1"]]}}
  )
