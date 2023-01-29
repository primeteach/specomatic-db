(ns specomatic-db.db.migration
  "Contains cross-platform functions, and multimethods to be implemented for specific SQL backends, for database migrations (DDL)."
  (:require
   [next.jdbc                   :as jdbc]
   [specomatic-db.db.conversion :as cnv]
   [specomatic-db.db.type       :refer [get-dbtype]]
   [specomatic-db.field-def     :as sdf]
   [specomatic-db.schema        :as schema]
   [specomatic.core             :as sc]
   [specomatic.field-def        :as sf]))

(defmulti column-def
  "Returns the column definition as SQL string."
  get-dbtype)

(defmulti sql-type
  "Returns the sql type for a database type and field spec dispatch value (keyword or description)."
  (fn [dbtype dispatch]
    [dbtype dispatch]))

(defmethod sql-type :default
  [_ _]
  "varchar(255)")

(defmulti create-table
  "Returns the SQL DDL required for creating the table as a map of
  {:constraints sqlvecs
   :main sqlvecs}"
  get-dbtype)

(defmulti diff-table
  "Generates a DDL diff, as a sqlvec, from inspection of the given `db` and comparison with entities of the given `ns`.
  Optionally restricted to `etypes` (sequence of keywords).
  Returns a map of
  {:constraints sqlvecs
   :main sqlvecs}
  if there are differences, nil if there are not."
  get-dbtype)

(defmulti get-constraints
  "Returns a sequence of all constraints in the database."
  get-dbtype)

(defmulti get-tables
  "Returns a sequence of all tables in the database."
  get-dbtype)

(defmulti ensure-transaction-infrastructure!
  "Ensures transaction infrastructure exists."
  get-dbtype)

(defmulti clear-transaction-system-txid!
  "Clears all system txids from the transaction table."
  get-dbtype)

(defn- diff-etypes
  [db tables existing-constraints schema etypes-and-reference-colls]
  (for [etype (filter #(or (not etypes-and-reference-colls)
                           (some #{%} etypes-and-reference-colls))
                      (keys schema))
        :let  [statements (let [db-fields  (schema/persistent-field-defs schema etype)
                                id-field   (->> etype
                                                (sc/id-field schema))
                                table-name (cnv/etype->table-name db etype (sc/etype-def schema etype))]
                            (if (some #{table-name} tables)
                              (diff-table db
                                          schema
                                          table-name
                                          id-field
                                          {:existing-constraints existing-constraints
                                           :field-defs           db-fields})
                              (create-table db
                                            schema
                                            table-name
                                            id-field
                                            {:existing-constraints existing-constraints
                                             :field-defs           db-fields})))]
        :when statements]
    [etype statements]))

(defn- diff-reference-colls
  [db tables existing-constraints schema etypes-and-reference-colls]
  (for [[etype ref-colls]    (sc/reference-colls-by-owning-etype schema)
        [ref-coll field-def] (filter #(or (not etypes-and-reference-colls) (some #{%} etypes-and-reference-colls))
                                     ref-colls)
        :let                 [join-table              (sdf/join-table field-def)
                              target                  (sf/target field-def)
                              table-name              (cnv/etype->table-name db join-table nil)
                              id-field                (sdf/join-table-id-field field-def)
                              [_ main-id target-id _] (sdf/db-via field-def)
                              db-fields               {id-field  {:kind     ::sf/simple
                                                                  :dispatch 'integer?}
                                                       main-id   {:kind     ::sf/reference
                                                                  :target   etype
                                                                  :cascade? true}
                                                       target-id {:kind     ::sf/reference
                                                                  :target   target
                                                                  :cascade? true}}
                              statements              (when-not (some #{table-name} tables)
                                                        (create-table db
                                                                      schema
                                                                      table-name
                                                                      id-field
                                                                      {:field-defs db-fields
                                                                       :join-table-unique-constraint
                                                                       {:main-id   (cnv/field->column-name
                                                                                    db
                                                                                    main-id
                                                                                    nil)
                                                                        :target-id (cnv/field->column-name
                                                                                    db
                                                                                    target-id
                                                                                    nil)}}))]
        :when                statements]
    [ref-coll statements]))

(defn diff-schema
  "Generates a DDL diff from inspection of the given `db` and comparison with entities of the given `schema`.
  Optionally restricted to `etypes-and-reference-colls` (sequence of keywords).
  Returns a map containing entity types as keys and maps of
  {:constraints sqlvecs
   :main sqlvecs}
  as values."
  ([db param-ns]
   (diff-schema db param-ns nil))
  ([db schema etypes-and-reference-colls]
   (into {}
         (let [tables      (get-tables db)
               constraints (get-constraints db)]
           (concat
            (diff-reference-colls db tables constraints schema etypes-and-reference-colls)
            (diff-etypes db tables constraints schema etypes-and-reference-colls))))))

(defn update-schema!
  "Updates database `db` for schema `schema`.
  Optionally restricted to `etypes` (sequence of keywords).
  Defaults to all detected changes."
  ([db schema]
   (update-schema! db schema nil))
  ([db schema etypes]
   (ensure-transaction-infrastructure! db)
   (let [updates (vals (diff-schema db schema etypes))]
     (doseq [main-statement (reduce concat (map :main updates))]
       (jdbc/execute! db main-statement))
     (doseq [constraint-statement (reduce concat (map :constraints updates))]
       (jdbc/execute! db constraint-statement)))))
