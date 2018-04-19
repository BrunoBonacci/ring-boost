(ns com.brunobonacci.ring-boost-test
  (:require [com.brunobonacci.ring-boost.core :refer :all]
            [midje.sweet :refer :all]))


(facts "about configuration"
       (fact "it returns a map merged recursively"
             (deep-merge {:x "xyz"}) => {:x "xyz"}
             (deep-merge {:x "xyz"} nil) => {:x "xyz"}
             (deep-merge {:x 1}{:y 2}) => {:x 1, :y 2}
             (deep-merge {:x {:k 1}}{:x {:z 2}}) => {:x {:k 1, :z 2}}
             (deep-merge {:x {:k 1}, :y {:k 1}} {:x {:z 2}, :y {:z 2}}) => {:x {:k 1, :z 2}, :y {:k 1, :z 2}}
             (deep-merge {:x 1} {:x 2} {:x 3}) => {:x 3}))
