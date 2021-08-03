(ns user
  {:clj-kondo/config
   '{:linters {:unused-namespace {:level :off}
               :unused-referred-var {:level :off}
               :refer-all {:level :off}}}}
  (:require [clojure.repl :refer :all]
            [clojure.term.colors :as colors]
            [clojure.tools.namespace.repl :as ns-tools :refer [refresh]]
            [kaocha.repl :as k]))

(ns-tools/set-refresh-dirs "src" "dev" "test")

(comment
  (clojure.tools.namespace.repl/refresh)

  (k/run :unit)

  nil)
