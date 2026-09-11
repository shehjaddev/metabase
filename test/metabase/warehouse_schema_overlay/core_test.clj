(ns metabase.warehouse-schema-overlay.core-test
  (:require
   [clojure.test :refer :all]
   [metabase.util.malli :as mu]
   [metabase.warehouse-schema-overlay.core :as warehouse-schema-overlay]
   [metabase.warehouse-schema.schema]
   [toucan2.core :as t2]))

(comment metabase.warehouse-schema.schema/keep-me)

(deftest ^:parallel table-columns-match-the-table-schema-test
  (testing "the spelled-out column list keeps up with the Table schema it cannot require"
    (is (= (set (mu/map-schema-keys :metabase.warehouse-schema.schema/table))
           warehouse-schema-overlay/table-columns))))

(deftest ^:parallel field-columns-match-the-field-schema-test
  (testing "the spelled-out column list keeps up with the Field schema it cannot require"
    (is (= (set (mu/map-schema-keys :metabase.warehouse-schema.schema/field))
           warehouse-schema-overlay/field-columns))))

(deftest ^:parallel table-query-with-neither-overlay-test
  (testing "with the user settings opted out of and no enterprise workspace overlay in play, a Table read is
           `metabase_table` itself -- no subquery to flatten"
    (is (= [(t2/table-name :model/Table) (t2/table-name :model/Table)]
           (warehouse-schema-overlay/table-query {:user-settings? false}))))
  (testing "the alias is the caller's, so a query that joins something else can qualify its columns"
    (is (= [(t2/table-name :model/Table) :t]
           (warehouse-schema-overlay/table-query {:alias :t, :user-settings? false}))))
  (testing "asking for the canonical location too changes nothing while workspaces are off"
    (is (= [(t2/table-name :model/Table) :t]
           (warehouse-schema-overlay/table-query {:alias                :t
                                                  :user-settings?       false
                                                  :workspace-remapping? false})))))

(deftest ^:parallel table-query-without-the-workspace-overlay-test
  (testing "with no enterprise workspace overlay in play, a Table read joins the user settings and nothing else"
    (let [[source] (warehouse-schema-overlay/table-query)]
      (is (= [[(t2/table-name :model/TableUserSettings) :u] [:= :u.table_id :t.id]]
             (:left-join source)))
      (testing "and so has no row to hide"
        (is (nil? (:where source)))))))

(deftest ^:parallel table-query-projects-every-table-column-test
  (testing "whatever the source, a Table read gets the same columns back, so callers can select any of them"
    (doseq [opts [nil
                  {:user-settings? false}
                  {:workspace-remapping? false}
                  {:user-settings? false, :workspace-remapping? false}]]
      (testing (pr-str opts)
        (let [[source] (warehouse-schema-overlay/table-query opts)]
          (when (map? source)
            (is (= warehouse-schema-overlay/table-columns
                   (into #{}
                         (map (fn [column] (if (vector? column) (second column) (keyword (name column)))))
                         (:select source))))))))))
