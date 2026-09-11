(ns metabase.warehouse-schema-overlay.core-test
  (:require
   [clojure.test :refer :all]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.warehouse-schema-overlay.core :as warehouse-schema-overlay]
   [toucan2.core :as t2]))

(use-fixtures :once (fixtures/initialize :db))

(defn- columns-of
  "The columns a `:select :*` over `source` comes back with, read off a real row."
  [source]
  (let [row (first (t2/query {:select [:*], :from [source], :limit 1}))]
    (is (some? row) "nothing to read the columns off")
    (set (keys row))))

(defn- do-with-a-field!
  "Calls `f` with a Table and a Field of its own, so that a `:select :*` has a row to come back with."
  [f]
  (mt/with-temp [:model/Database db    {}
                 :model/Table    table {:db_id (:id db), :schema "s", :name "t"}
                 :model/Field    field {:table_id (:id table), :name "f", :base_type :type/Text}]
    (f db table field)))

(deftest column-lists-match-the-database-test
  (do-with-a-field!
   (fn [_db _table _field]
     (testing "the spelled-out Field column list keeps up with `metabase_field` itself"
       (is (= (columns-of (t2/table-name :model/Field))
              @#'warehouse-schema-overlay/field-columns)))
     (testing "and the Table one with `metabase_table`"
       (is (= (columns-of (t2/table-name :model/Table))
              @#'warehouse-schema-overlay/table-columns))))))

(deftest reads-come-back-with-every-column-test
  (do-with-a-field!
   (fn [_db _table _field]
     (testing "whichever values a Field read asks for, it comes back with every column, so callers can select any"
       (doseq [opts [nil {:user-settings? false}]]
         (testing (pr-str opts)
           (is (= (columns-of (t2/table-name :model/Field))
                  (columns-of (warehouse-schema-overlay/field-query opts)))))))
     (testing "and so does a Table read"
       (doseq [opts [nil
                     {:user-settings? false}
                     {:workspace-remapping? false}
                     {:user-settings? false, :workspace-remapping? false}]]
         (testing (pr-str opts)
           (is (= (columns-of (t2/table-name :model/Table))
                  (columns-of (warehouse-schema-overlay/table-query opts))))))))))

(deftest reads-agree-with-the-plain-table-without-an-overlay-test
  (testing "with no user settings written and no enterprise workspace overlay in play, every way of asking reads the
           same rows as the table itself"
    (do-with-a-field!
     (fn [db table field]
       (doseq [opts [nil
                     {:user-settings? false}
                     {:workspace-remapping? false}
                     {:user-settings? false, :workspace-remapping? false}]]
         (testing (pr-str opts)
           (is (= [(select-keys table [:id :schema :name])]
                  (t2/select [:model/Table :id :schema :name]
                             :db_id (:id db)
                             {:from [(warehouse-schema-overlay/table-query opts)]})))))
       (doseq [opts [nil {:user-settings? false}]]
         (testing (pr-str opts)
           (is (= [(select-keys field [:id :name :display_name])]
                  (t2/select [:model/Field :id :name :display_name]
                             :table_id (:id table)
                             {:from [(warehouse-schema-overlay/field-query opts)]})))))))))
