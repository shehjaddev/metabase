(ns metabase-enterprise.workspaces.overlay-test
  "The Table overlay, exercised with the enterprise `enable-workspace-overlay?` implementation in place."
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.workspaces.impl :as ws.impl]
   [metabase.test :as mt]
   [metabase.warehouse-schema-overlay.core :as warehouse-schema-overlay]
   [metabase.workspaces.core :as workspaces]
   [toucan2.core :as t2]))

(def ^:private workspace-schema "ws_overlay")
(def ^:private workspace-table "ws_orders")

(defn- orders-tables
  "The `(schema, name)` pairs a Table read returns for the canonical orders table and its workspace table, through
  the overlay. Reads by name, which is one of the columns the overlay replaces."
  [canonical-name]
  (into #{}
        (map (juxt :schema :name))
        (t2/select [:model/Table :schema :name]
                   :db_id (mt/id)
                   :name [:in [canonical-name workspace-table]]
                   {:from [(warehouse-schema-overlay/table-query)]})))

(defn- do-with-remapped-orders!
  "Calls `f` with the canonical `[schema name]` of the orders table, while it has a remapping to a workspace table
  that -- as sync would leave it -- has a Table row of its own."
  [f]
  (let [{:keys [schema name]} (t2/select-one [:model/Table :schema :name] :id (mt/id :orders))]
    (mt/with-temp [:model/WorkspaceTableRemapping _ {:db_id       (mt/id)
                                                     :from_schema schema
                                                     :from_table  name
                                                     :to_schema   workspace-schema
                                                     :to_table    workspace-table}
                   :model/Table                   _ {:db_id  (mt/id)
                                                     :schema workspace-schema
                                                     :name   workspace-table}]
      (#'ws.impl/clear-remappings-cache!)
      (f [schema name]))))

(deftest reads-name-the-workspace-table-test
  (testing "with workspaces on, the canonical Table reads as the workspace table backing it, and only once --
            the Table row sync gave the workspace table is not a second table"
    (mt/with-premium-features #{:workspaces}
      (mt/with-temporary-setting-values [workspaces-enabled true]
        (do-with-remapped-orders!
         (fn [[_ canonical-name]]
           (is (= #{[workspace-schema workspace-table]}
                  (orders-tables canonical-name)))))))))

(deftest reads-name-the-canonical-table-when-off-test
  (testing "with workspaces off, both rows read as sync wrote them"
    (mt/with-premium-features #{:workspaces}
      (mt/with-temporary-setting-values [workspaces-enabled false]
        (do-with-remapped-orders!
         (fn [canonical]
           (is (= #{canonical [workspace-schema workspace-table]}
                  (orders-tables (second canonical))))))))))

(deftest reads-name-the-canonical-table-without-the-token-test
  (testing "without the :workspaces token feature the setting cannot turn the overlay on"
    (mt/with-premium-features #{}
      (mt/with-temporary-setting-values [workspaces-enabled true]
        (do-with-remapped-orders!
         (fn [canonical]
           (is (= #{canonical [workspace-schema workspace-table]}
                  (orders-tables (second canonical))))))))))

(deftest remapping-can-be-suppressed-test
  (testing "`with-table-remapping-disabled` reads the canonical table even while workspaces are on"
    (mt/with-premium-features #{:workspaces}
      (mt/with-temporary-setting-values [workspaces-enabled true]
        (do-with-remapped-orders!
         (fn [canonical]
           (is (= #{canonical [workspace-schema workspace-table]}
                  (workspaces/with-table-remapping-disabled
                    (orders-tables (second canonical)))))))))))
