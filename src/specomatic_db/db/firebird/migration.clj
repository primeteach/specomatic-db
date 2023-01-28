(ns ^:no-doc specomatic-db.db.firebird.migration
  "Implements migration multimethods for firebird sql storage."
  (:require
   [clojure.set                    :as set]
   [clojure.string                 :as str]
   [specomatic-db.db.conversion    :as cnv]
   [specomatic-db.db.firebird.sql  :as db-firebird]
   [specomatic-db.db.firebird.util :refer [firebirdsql]]
   [specomatic-db.db.generic       :as db-generic]
   [specomatic-db.db.migration     :as migration]
   [specomatic.core                :as sc]
   [specomatic.field-def           :as sf]
   [specomatic.spec                :as sp]))

(defmethod migration/column-def firebirdsql
  [db schema table-name field-keyword
   {:keys [cascade?]
    :as   field-def}
   {:keys [historical?]}]
  (if (sf/reference? field-def)
    (let [column-name (cnv/field->column-name db field-keyword field-def)
          target      (sf/target field-def)]
      (merge
        {:main (first (db-firebird/ref-column-def {:name column-name}))}
        (when-not historical?
          {:constraint {:table    table-name
                        :cascade? cascade?
                        :column   column-name
                        :target   (cnv/etype->table-name db target (sc/etype-def schema target))}})))
    {:main (first (db-firebird/column-def {:name (cnv/field->column-name db field-keyword field-def)
                                           :type (migration/sql-type firebirdsql (sf/dispatch field-def))}))}))

(defn- constraints-ddl
  [table-name existing-constraints constraints-params]
  (let [n (count constraints-params)]
    (loop [i                 0
           constraint-name-i 0
           all-constraints   existing-constraints
           constraint-ddl    []]
      (if (= i n)
        constraint-ddl
        (let [constraint-name (str "FK_" table-name constraint-name-i)
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
                         (db-firebird/alter-table-add-foreign-key-sqlvec
                           (assoc (nth constraints-params i) :constraint-name constraint-name))))))))))

(defmethod migration/create-table firebirdsql
  [db schema table-name id-field {:keys [existing-constraints field-defs join-table-unique-constraint]}]
  (let [column-ddl             (for [[field field-def] field-defs]
                                 (migration/column-def db schema table-name field field-def {}))
        historical-column-ddl  (for [[field field-def] field-defs]
                                 (migration/column-def db schema table-name field field-def {:historical? true}))
        column-defs            (map :main column-ddl)
        historical-column-defs (map :main historical-column-ddl)]
    (when (seq column-defs)
      (let [params {:id-field                     (cnv/field->column-name db id-field nil)
                    :table                        table-name
                    :column-defs                  (str/join "," column-defs)
                    :column-names                 (mapv (fn [[field field-def]]
                                                          (cnv/field->column-name db field field-def))
                                                        field-defs)
                    :join-table-unique-constraint join-table-unique-constraint
                    :historical-column-defs       historical-column-defs}]
        {:main        (concat
                       ((juxt db-firebird/create-generic-sqlvec
                              db-firebird/create-pk-sequence-sqlvec
                              db-firebird/create-pk-sequence-trigger-sqlvec)
                        params)
                       ((juxt
                         db-firebird/create-generic-history-sqlvec
                         db-firebird/create-or-alter-history-trigger-sqlvec)
                        (assoc params :column-defs (str/join "," historical-column-defs))))
         :constraints (constraints-ddl table-name existing-constraints (filter some? (map :constraint column-ddl)))}))))

(defmethod migration/diff-table firebirdsql
  [db schema table-name id-field {:keys [existing-constraints field-defs]}]
  (let [fields-to-db-columns (into {}
                                   (for [[field field-def] field-defs]
                                     [field
                                      (cnv/field->column-name db field field-def)]))
        db-columns-to-fields (set/map-invert fields-to-db-columns)
        existing-columns     (->> (db-firebird/select-fields db {:table-name table-name})
                                  (map (comp str/trim :rdb$field_name))
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
        {:main        [(db-generic/alter-table-sqlvec
                        {:table       table-name
                         :column-defs column-defs})
                       (db-generic/alter-table-sqlvec
                        {:table       (str table-name "_H")
                         :column-defs historical-column-defs})
                       (db-firebird/create-or-alter-history-trigger-sqlvec
                        {:table        table-name
                         :id-field     (cnv/field->column-name db id-field nil)
                         :column-names (mapv (fn [[field field-def]]
                                               (cnv/field->column-name db field field-def))
                                             field-defs)})]
         :constraints (constraints-ddl table-name
                                       existing-constraints
                                       (filter some?
                                               (map #(-> %
                                                         first
                                                         :constraint)
                                                    missing-column-defs)))}))))

(defmethod migration/get-constraints firebirdsql
  [db]
  (map (comp str/trim :rdb$constraint_name) (db-firebird/select-all-constraints db)))

(defmethod migration/get-tables firebirdsql
  [db]
  (map (comp str/trim :rdb$relation_name) (db-firebird/select-all-tables db)))

(defmethod migration/ensure-transaction-infrastructure! firebirdsql
  [db]
  (db-firebird/ensure-table-transaction db)
  (db-firebird/ensure-sequence-transaction db)
  (db-firebird/ensure-procedure-get-transaction db))

(defmethod migration/clear-transaction-system-txid! firebirdsql
  [db]
  (db-firebird/clear-transaction-system-txid db))

(defmethod migration/sql-type [firebirdsql ::sp/integer]
  [_ _]
  "integer")

(defmethod migration/sql-type [firebirdsql 'integer?]
  [_ _]
  "integer")
