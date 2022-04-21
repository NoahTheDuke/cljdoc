(ns cljdoc.render.api-searchset-test
  (:require [cljdoc.render.api-searchset :as api-searchset]
            [cljdoc.spec.cache-bundle :as cbs]
            [cljdoc.spec.searchset :as ss]
            [clojure.edn :as edn]
            [clojure.test :as t]))

(def cache-bundle (-> "resources/test_data/cache_bundle.edn"
                      slurp
                      edn/read-string))

(comment
  ;; run regen-results to regenerate expected results.
  ;; test project docs will need to have been built and server started from cljdoc.server.system
  (require '[clojure.pprint]
           '[cljdoc.spec.util :as util]
           '[clojure.walk :as walk])

  (defn pp-str [f]
    (with-out-str (clojure.pprint/pprint f)))

  (defn sort-k-set [k by-keys m]
    (assoc m k
           (into (sorted-set-by (fn [x y]
                                  (reduce (fn [c k]
                                            (if (not (zero? c))
                                              (reduced c)
                                              (compare (k x) (k y))))
                                          0
                                          by-keys)))
                 (k m))))

  (defn sort-results-form [form]
    (cond->> form
      (set? (:namespaces form)) (sort-k-set :namespaces [:name :platform])
      (set? (:defs form)) (sort-k-set :defs [:namespace :name :platform])
      :always (walk/postwalk (fn [n] (if (map? n)
                                       (into (sorted-map) n)
                                       n)))))

  (defn regen-results []
    (let [cache-bundle (util/load-cache-bundle "rewrite-clj/rewrite-clj/1.0.767-alpha")
          searchset (api-searchset/cache-bundle->searchset cache-bundle)]
      (spit "resources/test_data/cache_bundle.edn" (pp-str (sort-results-form cache-bundle)))
      (spit "resources/test_data/searchset.edn" (pp-str (sort-results-form searchset)))
      ;; make sure you check these to confirm that namespaces + defs + docs are all generating correctly
      ;;
      ;; don't worry about the namespace id, the id comes from an auto-increment column
      ;; in the database and will be different for each system
      ))

  (regen-results)

  nil)

(def doc (get-in cache-bundle [:version :doc 0]))

(def version-entity (:version-entity cache-bundle))

(def searchset (-> "resources/test_data/searchset.edn"
                   slurp
                   edn/read-string))

(t/deftest path-for-doc
  (t/testing "gets the route for a given doc"
    (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/doc/readme"
             (api-searchset/path-for-doc doc version-entity))))
  (t/testing "gets the route if the doc has a slug path instead of a slug"
    (let [attrs (:attrs doc)
          slug-path-attrs (-> attrs (dissoc :slug) (assoc :slug-path ["read" "me"]))
          slug-path-doc (assoc doc :attrs slug-path-attrs)]
      (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/doc/read/me"
               (api-searchset/path-for-doc slug-path-doc version-entity))))))

(t/deftest path-for-namespace
  (t/testing "gets a route for a namespace"
    (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/api/rewrite-clj.node"
             (api-searchset/path-for-namespace version-entity "rewrite-clj.node")))))

(t/deftest path-for-def
  (t/testing "gets a route for a def"
    (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/api/rewrite-clj.node#coerce"
             (api-searchset/path-for-def version-entity "rewrite-clj.node" "coerce")))))

(t/deftest cache-bundle->searchset
  (let [generated-searchset (api-searchset/cache-bundle->searchset cache-bundle)]
    (t/testing "input cache bundle is valid"
      (let [explanation (cbs/explain-humanized cache-bundle)]
        (t/is (nil? explanation) {:explanation explanation
                                  :data cache-bundle})))
    (t/testing "converts a cache-bundle into a searchset"
      (t/is (= searchset generated-searchset)))
    (t/testing "produces a valid searchset"
      (let [explanation (ss/explain-humanized generated-searchset)]
        (t/is (nil? explanation) {:explanation explanation
                                  :data generated-searchset})))))
