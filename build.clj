(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as d]))

(def lib 'com.fbeyer/oidc-client-ring)
(def version (format "0.0.%s" (b/git-count-revs nil)))
(def class-dir ".build/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format ".build/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path ".build"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  {:pre [(some? (System/getenv "CLOJARS_USERNAME"))
         (some? (System/getenv "CLOJARS_PASSWORD"))]}
  (d/deploy {:installer :remote
             :sign-releases? true
             :artifact jar-file}))
