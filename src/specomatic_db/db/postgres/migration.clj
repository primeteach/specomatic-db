(ns ^:no-doc specomatic-db.db.postgres.migration
  "Implements migration multimethods for postgresql storage."
  (:require
   [clojure.set                    :as set]
   [clojure.string                 :as str]
   [specomatic-db.db.conversion    :as cnv]
   [specomatic-db.db.generic       :as db-generic]
   [specomatic-db.db.migration     :as migration]
   [specomatic-db.db.postgres.sql  :as db-postgres]
   [specomatic-db.db.postgres.util :refer [postgresql]]
   [specomatic.core                :as sc]
   [specomatic.field-def           :as sf]))

(defmethod migration/column-def postgresql
  [db schema table-name field-keyword
   {:keys [cascade?]
    :as   field-def}
   {:keys [historical?]}]
  (if (sf/reference? field-def)
    (let [column-name (cnv/field->column-name db field-keyword field-def)
          target      (sf/target field-def)]
      (merge
        {:main (first (db-postgres/ref-column-def {:name column-name}))}
        (when-not historical?
          {:constraint {:table    table-name
                        :cascade? cascade?
                        :column   column-name
                        :target   (cnv/etype->table-name db target (sc/etype-def schema target))}})))
    {:main (first (db-postgres/column-def {:name (cnv/field->column-name db field-keyword field-def)
                                           :type (migration/column-type field-def)}))}))

(defn- constraints-ddl
  [table-name existing-constraints constraints-params]
  (let [n (count constraints-params)]
    (loop [i                 0
           constraint-name-i 0
           all-constraints   existing-constraints
           constraint-ddl    []]
      (if (= i n)
        constraint-ddl
        (let [constraint-name (str "fk_" table-name constraint-name-i)
              existing?       (some #{constraint-name} all-constraints)]
          (recur (if existing?
                   i
                   (inc i))
                 (inc constraint-name-i)
                 (if existing?
                   all-constraints
                   (conj all-constraints constraint-name))
                 (if existing?
                   constraint-ddl
                   (conj constraint-ddl
                         (db-postgres/alter-table-add-foreign-key-sqlvec
                           (assoc (nth constraints-params i) :constraint-name constraint-name))))))))))

(defmethod migration/create-table postgresql
  [db schema table-name id-field {:keys [existing-constraints field-defs join-table-unique-constraint]}]
  (let [column-ddl-without-id            (for [[field field-def] field-defs
                                               :when             (not= (name id-field)
                                                                       (name field))]
                                           (migration/column-def db schema table-name field field-def {}))
        historical-column-ddl-without-id (for [[field field-def] field-defs
                                               :when             (not= (name id-field)
                                                                       (name field))]
                                           (migration/column-def db
                                                                 schema
                                                                 table-name
                                                                 field
                                                                 field-def
                                                                 {:historical? true}))
        id-spec                          (migration/column-type (id-field field-defs))
        id-column                        (cnv/field->column-name db id-field nil)
        column-ddl                       (conj column-ddl-without-id
                                               {:main (first (db-postgres/id-column-def
                                                              {:name id-column
                                                               :type id-spec}))})
        historical-column-ddl            (conj historical-column-ddl-without-id
                                               {:main (first (db-postgres/column-def
                                                              {:name id-column
                                                               :type id-spec}))})
        column-defs                      (map :main column-ddl)
        historical-column-defs           (map :main historical-column-ddl)]
    (when (seq column-ddl)
      (let [params         {:id-field                     id-column
                            :table                        table-name
                            :column-defs                  (str/join "," column-defs)
                            :column-names                 (mapv (fn [[field field-def]]
                                                                  (cnv/field->column-name db field field-def))
                                                                field-defs)
                            :join-table-unique-constraint join-table-unique-constraint}
            history-params (assoc params :column-defs (str/join "," historical-column-defs))]
        {:main        (concat [(db-postgres/create-generic-sqlvec params)]
                              ((juxt
                                db-postgres/create-generic-history-sqlvec
                                db-postgres/create-or-replace-history-trigger-function-sqlvec
                                db-postgres/create-or-replace-history-trigger-sqlvec)
                               history-params))
         :constraints (constraints-ddl table-name existing-constraints (filter some? (map :constraint column-ddl)))}))))

(defmethod migration/diff-table postgresql
  [db schema table-name id-field {:keys [existing-constraints field-defs]}]
  (let [fields-to-db-columns (into {}
                                   (for [[field field-def] field-defs]
                                     [field
                                      (cnv/field->column-name db field field-def)]))
        db-columns-to-fields (set/map-invert fields-to-db-columns)
        existing-columns     (->> (db-postgres/select-fields db {:table-name table-name})
                                  (map :column_name)
                                  set)
        missing-column-defs  (for [missing-column-name (set/difference (-> fields-to-db-columns
                                                                           vals
                                                                           set)
                                                                       existing-columns)
                                   :let                [missing-field (get db-columns-to-fields missing-column-name)
                                                        field-def     (missing-field field-defs)]]
                               [(migration/column-def db schema table-name missing-field field-def {})
                                (migration/column-def db
                                                      schema
                                                      table-name
                                                      missing-field
                                                      field-def
                                                      {:historical? true})])]
    (when (seq missing-column-defs)
      (let [column-defs            (str/join ","
                                             (map #(->> %
                                                        first
                                                        :main
                                                        (str "add "))
                                                  missing-column-defs))
            historical-column-defs (str/join ","
                                             (map #(->> %
                                                        second
                                                        :main
                                                        (str "add "))
                                                  missing-column-defs))]
        {:main [(db-generic/alter-table-sqlvec
                 {:table       table-name
                  :column-defs column-defs})
                (db-generic/alter-table-sqlvec
                 {:table       (str table-name "_h")
                  :column-defs historical-column-defs})
                (db-postgres/create-or-replace-history-trigger-function-sqlvec
                 {:table        table-name
                  :id-field     (cnv/field->column-name db id-field nil)
                  :column-names (mapv (fn [[field field-def]]
                                        (cnv/field->column-name db field field-def))
                                      field-defs)
                  :constraints  (constraints-ddl table-name
                                                 existing-constraints
                                                 (filter some?
                                                         (map #(-> %
                                                                   first
                                                                   :constraint)
                                                              missing-column-defs)))})]}))))

(defmethod migration/get-constraints postgresql
  [db]
  (map :rdb$constraint_name (db-postgres/select-all-constraints db)))

(defmethod migration/get-tables postgresql
  [db]
  (map :table_name (db-postgres/select-all-tables db)))

(defmethod migration/ensure-transaction-infrastructure! postgresql
  [db]
  (db-postgres/ensure-table-transaction db)
  (db-postgres/ensure-procedure-get-transaction db))

(defmethod migration/clear-transaction-system-txid! postgresql
  [db]
  (db-postgres/clear-transaction-system-txid db))
