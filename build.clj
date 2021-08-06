(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as d]))

(def lib 'com.fbeyer/identify)
(def base-version "0.0")

(def class-dir ".build/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(def pom-file (format "%s/META-INF/maven/%s/%s/pom.xml"
                      class-dir (namespace lib) (name lib)))

(defn- git [& args]
  (let [{:keys [exit out]}
        (b/process {:command-args (into ["git"] args)
                    :dir "."
                    :out :capture
                    :err :ignore})]
    (when (zero? exit)
      (str/trim-newline out))))

(defn- git-sha []
  (git "rev-parse" "HEAD"))

(defn- git-tag []
  (git "describe" "--tags" "--exact-match"))

(defn- expand-pom []
  (-> (slurp pom-file)
      (str/replace-first "<tag>HEAD</tag>" (str "<tag>" (git-sha) "</tag>"))
      (->> (spit pom-file))))

(def version (if-let [tag (git-tag)]
               (str/replace tag #"^v" "")
               (format "%s.%s-%s" base-version (b/git-count-revs nil)
                       (if (System/getenv "CI") "ci" "dev"))))

(def jar-file (format ".build/%s-%s.jar" (name lib) version))

(defn info [_]
  (pr {:lib lib
       :version version
       :jar-file jar-file})
  (newline))

(defn clean [_]
  (b/delete {:path ".build"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (expand-pom)
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
             :artifact jar-file
             :pom-file (format "%s/META-INF/maven/%s/%s/pom.xml"
                               class-dir (namespace lib) (name lib))}))
