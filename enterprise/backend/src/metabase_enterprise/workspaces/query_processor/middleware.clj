(ns metabase-enterprise.workspaces.query-processor.middleware
  "QP middleware rewriting the SQL of native stages so they read the workspace tables, joins included. Runs at the
  end of preprocessing, once native SQL has its template tags, snippets and card references resolved, so every
  consumer of `qp.compile/compile` -- transforms included -- gets the rewritten SQL.

  MBQL stages need nothing here: they compile from Table metadata, and `metabase.warehouse-schema-overlay.core`
  already reads a remapped Table as the table its data is in."
  (:require
   [metabase-enterprise.workspaces.impl :as ws.impl]
   [metabase.driver :as driver]
   [metabase.driver.sql.normalize :as sql.normalize]
   [metabase.lib.schema :as lib.schema]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.premium-features.core :refer [defenterprise-schema]]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.sql-tools.core :as sql-tools]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu]
   [metabase.workspaces.core :as workspaces]
   [metabase.workspaces.schema :as ws.schema]))

(set! *warn-on-reflection* true)

;;; Native SQL

(mu/defn- sql-table-spec :- ::sql-tools/table-spec
  [schema :- [:maybe :string]
   table  :- ::lib.schema.common/non-blank-string]
  (cond-> {:table table}
    (some? schema) (assoc :schema schema)))

(mu/defn- default-schema :- [:maybe :string]
  [driver :- :keyword]
  (when (get-method sql.normalize/default-schema driver)
    (sql.normalize/default-schema driver)))

(mu/defn- sql-table-replacements :- [:map-of ::sql-tools/table-spec ::sql-tools/table-spec]
  "The `:tables` replacements for `sql-tools/replace-names`. A table in the driver's default schema also matches
  unqualified."
  [driver     :- :keyword
   remappings :- [:sequential ::ws.schema/table-remapping]]
  (let [default-schema (default-schema driver)]
    (into {}
          (mapcat (fn [{:keys [from_schema from_table to_schema to_table]}]
                    (let [to (sql-table-spec to_schema to_table)]
                      (cond-> [[(sql-table-spec from_schema from_table) to]]
                        (and (some? from_schema) (= from_schema default-schema))
                        (conj [(sql-table-spec nil from_table) to])))))
          remappings)))

(mu/defn- rewrite-sql :- :string
  "Rewrite references to canonical tables of `remappings` in `sql` to their workspace tables. A parse failure throws
  a QP error rather than running the query against the canonical tables."
  [driver     :- :keyword
   sql        :- :string
   remappings :- [:sequential ::ws.schema/table-remapping]]
  (try
    (sql-tools/replace-names driver sql {:tables (sql-table-replacements driver remappings)} {:allow-unused? true})
    (catch Exception e
      (throw (ex-info (tru "Workspace table remapping failed: cannot parse the SQL query.")
                      {:type qp.error-type/qp, :driver driver}
                      e)))))

(mu/defn- rewrite-stages :- [:sequential :map]
  [driver     :- :keyword
   remappings :- [:sequential ::ws.schema/table-remapping]
   stages     :- [:sequential :map]]
  (mapv (fn [stage]
          (cond-> stage
            (and (= :mbql.stage/native (:lib/type stage))
                 (string? (:native stage)))
            (update :native #(rewrite-sql driver % remappings))

            (seq (:joins stage))
            (update :joins (fn [joins]
                             (mapv (fn [join]
                                     (cond-> join
                                       (seq (:stages join)) (update :stages #(rewrite-stages driver remappings %))))
                                   joins)))))
        stages))

;;; Middleware

(defenterprise-schema apply-workspace-remapping :- ::lib.schema/query
  "Pre-processing middleware. Rewrites the canonical table references in native stages to the workspace tables."
  :feature :workspaces
  [{db-id :database, :as query} :- ::lib.schema/query]
  ;; checked before the remappings, and against the setting rather than them: a routed query still names the router
  ;; database here, and the router holds no transform output of its own, so waiting for its remappings would let
  ;; every routed query through -- to read the destination's remapped tables under their canonical names
  (when (and (:destination-database/id query)
             (workspaces/enabled?))
    (throw (ex-info (tru "Database routing is not supported together with workspaces.")
                    {:type qp.error-type/qp, :database-id db-id})))
  (if-let [remappings (when (workspaces/allow-table-remapping?)
                        (ws.impl/remappings-for-db db-id))]
    (update query :stages #(rewrite-stages driver/*driver* remappings %))
    query))
