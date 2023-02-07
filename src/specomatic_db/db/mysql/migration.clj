(ns ^:no-doc specomatic-db.db.mysql.migration
  "Implements migration multimethods for mysql storage."
  (:require
   [clojure.set                 :as set]
   [clojure.string              :as str]
   [specomatic-db.db.conversion :as cnv]
   [specomatic-db.db.generic    :as db-generic]
   [specomatic-db.db.migration  :as migration]
   [specomatic-db.db.mysql.sql  :as db-mysql]
   [specomatic-db.db.mysql.util :refer [mysql]]
   [specomatic.core             :as sc]
   [specomatic.field-def        :as sf]
   [specomatic.spec             :as sp]))

(defmethod migration/column-def mysql
  [db schema table-name field-keyword
   {:keys [cascade?]
    :as   field-def}
   {:keys [historical?]}]
  (if (sf/reference? field-def)
    (let [column-name (cnv/field->column-name db field-keyword field-def)
          target      (sf/target field-def)]
      (merge
        {:main (first (db-mysql/ref-column-def {:name column-name}))}
        (when-not historical?
          {:constraint {:table    table-name
                        :cascade? cascade?
                        :column   column-name
                        :target   (cnv/etype->table-name db target (sc/etype-def schema target))}})))
    {:main (first (db-mysql/column-def {:name (cnv/field->column-name db field-keyword field-def)
                                        :type (migration/sql-type mysql (sf/dispatch field-def))}))}))

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
                         (db-mysql/alter-table-add-foreign-key-sqlvec
                           (assoc (nth constraints-params i) :constraint-name constraint-name))))))))))

(defmethod migration/create-table mysql
  [db schema table-name id-field {:keys [existing-constraints field-defs join-table-unique-constraint]}]
  (let [column-ddl-without-id (for [[field field-def] field-defs
                                    :when             (not= (name id-field)
                                                            (name field))]
                                (migration/column-def db schema table-name field field-def {}))
        id-dispatch           (sf/dispatch (id-field field-defs))
        id-column             (cnv/field->column-name db id-field nil)
        column-ddl            (conj column-ddl-without-id
                                    {:main (first (db-mysql/id-column-def
                                                   {:name id-column
                                                    :type "serial primary key"}))})
        column-defs           (map :main column-ddl)]
    (when (seq column-ddl)
      (let [params {:id-field                     id-column
                    :table                        table-name
                    :column-defs                  (str/join "," column-defs)
                    :column-names                 (mapv (fn [[field field-def]]
                                                          (cnv/field->column-name db field field-def))
                                                        field-defs)
                    :join-table-unique-constraint join-table-unique-constraint}]
        {:main        (concat [(db-mysql/create-generic-sqlvec params)])
         :constraints (constraints-ddl table-name existing-constraints (filter some? (map :constraint column-ddl)))}))))

(defmethod migration/diff-table mysql
  [db schema table-name id-field {:keys [existing-constraints field-defs]}]
  (let [fields-to-db-columns (into {}
                                   (for [[field field-def] field-defs]
                                     [field
                                      (cnv/field->column-name db field field-def)]))
        db-columns-to-fields (set/map-invert fields-to-db-columns)
        existing-columns     (->> (db-mysql/select-fields db {:table-name table-name})
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
      (let [column-defs (str/join ","
                                  (map #(->> %
                                             first
                                             :main
                                             (str "add "))
                                       missing-column-defs))]
        {:main [(db-generic/alter-table-sqlvec
                 {:table       table-name
                  :column-defs column-defs})
               ]}))))

(defmethod migration/get-constraints mysql
  [db]
  (map :rdb$constraint_name (db-mysql/select-all-constraints db)))

(defmethod migration/get-tables mysql
  [db]
  (map :table_name (db-mysql/select-all-tables db)))

(defmethod migration/ensure-transaction-infrastructure! mysql
  [db])

(defmethod migration/clear-transaction-system-txid! mysql
  [db])

(defmethod migration/sql-type [mysql ::sp/integer]
  [_ _]
  "integer")

(defmethod migration/sql-type [mysql 'integer?]
  [_ _]
  "integer")
