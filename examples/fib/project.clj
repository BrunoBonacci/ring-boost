(defproject fib "0.1.0"
  :description "Sample project for ring-boost"
  :url "https://github.com/BrunoBonacci/ring-boost"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.5.1"]
                 [ring/ring-defaults "0.2.1"]
                 [ring/ring-json "0.4.0"]
                 [com.brunobonacci/ring-boost "0.1.0-SNAPSHOT"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]]
  :plugins [[lein-ring "0.9.7"]]
  :ring {:handler fib.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.0"]]}})
