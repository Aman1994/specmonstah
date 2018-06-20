(ns reifyhealth.specmonstah.spec-gen-test
  (:require #?(:clj [clojure.test :refer [deftest is are use-fixtures testing]]
               :cljs [cljs.test :include-macros true :refer [deftest is are use-fixtures testing]])
            [clojure.spec.alpha :as s]
            [clojure.data :as data]
            [clojure.test.check.generators :as gen :include-macros true]
            [reifyhealth.specmonstah.test-data :as td]
            [reifyhealth.specmonstah.core :as sm]
            [reifyhealth.specmonstah.spec-gen :as sg]
            [medley.core :as medley]
            [loom.graph :as lg]
            [loom.alg :as la]
            [loom.attr :as lat]))

(def gen-data-db (atom []))
(def gen-data-cycle-db (atom []))

(defn reset-dbs [f]
  (reset! gen-data-db [])
  (reset! gen-data-cycle-db [])
  (f))

(use-fixtures :each td/test-fixture reset-dbs)

(defn map-subset=
  "All vals in m2 are present in m1"
  [m1 m2]
  (nil? (second (data/diff m1 m2))))

(defn ids-present?
  [generated]
  (every? pos-int? (map :id (vals generated))))

(defn only-has-ents?
  [generated ent-names]
  (= (set (keys generated))
     (set ent-names)))

(defn ids-match?
  "Reference attr vals equal their referent"
  [generated matches]
  (every? (fn [[ent id-path-map]]
            (every? (fn [[attr id-path-or-paths]]
                      (if (vector? (first id-path-or-paths))
                        (= (set (map (fn [id-path] (get-in generated id-path)) id-path-or-paths))
                           (set (get-in generated [ent attr])))
                        (= (get-in generated id-path-or-paths)
                           (get-in generated [ent attr]))))
                    id-path-map))
          matches))

(deftest test-spec-gen
  (let [gen (sg/ent-db-spec-gen-attr {:schema td/schema} {:todo-list [[1]]})]
    (is (map-subset= gen {:u0 {:user-name "Luigi"}}))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:tl0 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}}))
    (is (only-has-ents? gen #{:tl0 :u0}))))

(deftest test-spec-gen-nested
  (let [gen (sg/ent-db-spec-gen-attr {:schema td/schema} {:project [[:_ {:refs {:todo-list-ids 3}}]]})]
    (is (map-subset= gen {:u0  {:user-name "Luigi"}}))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:tl0 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :tl1 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :tl2 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :p0  {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]
                           :todo-list-ids [[:tl0 :id]
                                           [:tl1 :id]
                                           [:tl2 :id]]}}))
    (is (only-has-ents? gen #{:tl0 :tl1 :tl2 :u0 :p0}))))

(deftest test-spec-gen-manual-attr
  (let [gen (sg/ent-db-spec-gen-attr {:schema td/schema} {:todo [[:_ {:spec-gen {:todo-title "pet the dog"}}]]})]
    (is (map-subset= gen
                     {:u0 {:user-name "Luigi"}
                      :t0 {:todo-title "pet the dog"}}))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:tl0 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :t0 {:created-by-id [:u0 :id]
                          :updated-by-id [:u0 :id]
                          :todo-list-id [:tl0 :id]}}))
    (is (only-has-ents? gen #{:tl0 :t0 :u0}))))

(deftest test-idempotency
  (testing "Gen traversal won't replace already generated data with newly generated data"
    (let [gen-fn     #(sg/ent-db-spec-gen % {:todo [[:t0 {:spec-gen {:todo-title "pet the dog"}}]]})
          first-pass (gen-fn {:schema td/schema})]
      (is (= (:data first-pass)
             (:data (gen-fn first-pass)))))))

(defn insert
  [{:keys [data] :as db} ent-name ent-attr-key]
  (swap! gen-data-db conj [(lat/attr data ent-name :ent-type)
                           ent-name
                           (lat/attr data ent-name sg/spec-gen-ent-attr-key)]))



(deftest test-insert-gen-data
  (-> (sg/ent-db-spec-gen {:schema td/schema} {:todo [[1]]})
      (sm/visit-ents-once :inserted-data insert))

  ;; gen data is something like:
  ;; [[:user :u0 {:id 1 :user-name "Luigi"}]
  ;;  [:todo-list :tl0 {:id 2 :created-by-id 1 :updated-by-id 1}]
  ;;  [:todo :t0 {:id            5
  ;;              :todo-title    "write unit tests"
  ;;              :created-by-id 1
  ;;              :updated-by-id 1
  ;;              :todo-list-id  2}]]
  
  (let [gen-data @gen-data-db]
    (is (= (set (map #(take 2 %) gen-data))
           #{[:user :u0]
             [:todo-list :tl0]
             [:todo :t0]}))

    (let [ent-map (into {} (map #(vec (drop 1 %)) gen-data))]
      (is (map-subset= ent-map
                       {:u0 {:user-name "Luigi"}
                        :t0 {:todo-title "write unit tests"}}))
      (is (ids-present? ent-map))
      (is (ids-match? ent-map
                      {:tl0 {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]}
                       :t0 {:created-by-id [:u0 :id]
                            :updated-by-id [:u0 :id]
                            :todo-list-id [:tl0 :id]}})))))

(deftest inserts-novel-data
  (testing "Given a db with a todo already added, next call adds a new
  todo that references the same todo list and user"
    (let [db1 (-> (sg/ent-db-spec-gen {:schema td/schema} {:todo [[1]]})
                  (sm/visit-ents-once :inserted-data insert))]
      (-> (sg/ent-db-spec-gen db1 {:todo [[1]]})
          (sm/visit-ents-once :inserted-data insert))

      (let [gen-data @gen-data-db]
        (is (= (set (map #(take 2 %) gen-data))
               #{[:user :u0]
                 [:todo-list :tl0]
                 [:todo :t0]
                 [:todo :t1]}))

        (let [ent-map (into {} (map #(vec (drop 1 %)) gen-data))]
          (is (map-subset= ent-map
                           {:u0 {:user-name "Luigi"}
                            :t0 {:todo-title "write unit tests"}
                            :t1 {:todo-title "write unit tests"}}))
          (is (ids-present? ent-map))
          (is (ids-match? ent-map
                          {:tl0 {:created-by-id [:u0 :id]
                                 :updated-by-id [:u0 :id]}
                           :t0  {:created-by-id [:u0 :id]
                                 :updated-by-id [:u0 :id]
                                 :todo-list-id  [:tl0 :id]}
                           :t1  {:created-by-id [:u0 :id]
                                 :updated-by-id [:u0 :id]
                                 :todo-list-id  [:tl0 :id]}})))))))

(defn insert-cycle
  [{:keys [data] :as db} ent-name ent-attr-key]
  (do (swap! gen-data-cycle-db conj ent-name)
      (lat/attr data ent-name sg/spec-gen-ent-attr-key)))

(deftest handle-cycles-with-constraints-and-reordering
  (testing "todo-list is inserted before todo because todo requires todo-list"
    (-> (sg/ent-db-spec-gen {:schema td/cycle-schema} {:todo [[1]]})
        (sm/visit-ents :insert-cycle insert-cycle))
    (is (= @gen-data-cycle-db
           [:tl0 :t0]))))

(deftest throws-exception-on-2nd-map-ent-attr-try
  (testing "insert-cycle fails because the schema contains a :required cycle"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                             :cljs js/Object)
                          #"Can't order ents: check for a :required cycle"
                          (-> (sm/build-ent-db {:schema {:todo      {:spec        ::todo
                                                                     :relations   {:todo-list-id [:todo-list :id]}
                                                                     :constraints {:todo-list-id #{:required}}
                                                                     :prefix      :t}
                                                         :todo-list {:spec        ::todo-list
                                                                     :relations   {:first-todo-id [:todo :id]}
                                                                     :constraints {:first-todo-id #{:required}}
                                                                     :prefix      :tl}}}
                                               {:todo [[1]]})
                              (sm/visit-ents :insert-cycle insert-cycle))))))
