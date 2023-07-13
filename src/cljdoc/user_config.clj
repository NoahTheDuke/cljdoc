(ns cljdoc.user-config
  "Users can provide configuration via a `doc/cljdoc.edn` file in their git
  repository.

  This namespace defines functions to query the contents of this config.

  You'll notice cljdoc looks up sub-projects here.
  This is in support of a single git repository that generates multiple
  artifacts. See the 'Distinctly Configuring' under
  /doc/userguide/for-library-authors.adoc#modules for more details."
  (:require [cljdoc-shared.proj :as proj]))

(defn get-project-specific
  [config-edn project]
  (some->> config-edn
           (filter (fn [[k _]]
                     (and
                      (symbol? k)
                      (or (= k (symbol (proj/group-id project)))
                          (= k (symbol (proj/group-id project) (proj/artifact-id project)))))))
           (first)
           (val)))

(defn get-project
  [config-edn project]
  (or (get-project-specific config-edn project)
      (->> config-edn
           (remove (fn [[k _v]] (symbol? k)))
           (into {}))))

(defn doc-tree [config-edn project]
  (:cljdoc.doc/tree (get-project config-edn project)))

(defn include-namespaces-from-deps [config-edn project]
  (:cljdoc/include-namespaces-from-dependencies (get-project config-edn project)))

(defn languages [config-edn project]
  (:cljdoc/languages (get-project config-edn project)))

(comment
  (def d
    '{metosin/reitit {:cljdoc.doc/tree [["Introduction" {:file "intro.md"}]]}
      :cljdoc.doc/tree [["Overview" {:file "modules/README.md"}]]})

  (get-project d "metosin/reitit"))
