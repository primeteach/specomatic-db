(ns specomatic-db.db.conversion
  "Provides conversions between entity types and database records / columns."
  (:require
   [clojure.spec.alpha      :as s]
   [clojure.string          :as str]
   [nedap.speced.def        :as sd]
   [specomatic-db.db.type   :refer [get-dbtype]]
   [specomatic-db.etype-def :as sde]
   [specomatic-db.field-def :as sdf]
   [specomatic-db.spec      :as sp]
   [specomatic.core         :as sc]
   [specomatic.field-def    :as sf]))

(sd/defn quotable-identifier-dispatch
  "Dispatch function for `keyword->sql-identifier`"
  ^string?
  [db ^string? _identifier]
  (get-dbtype db))

(defmulti quotable-sql-identifier
  "Returns the quotable SQL identifier (with adjusted case) for the given keyword `k`."
  quotable-identifier-dispatch)

(defmethod quotable-sql-identifier :default [_db identifier] identifier)

(def ^:private valid-sql-identifier-re
  "A regular expression for validating strings representing an SQL identifier."
  #"^[A-Za-z][A-Za-z0-9\-_]+$")

(defn valid-sql-identifier-keyword?
  "Checks if keyword `k` can be converted into a valid SQL identifier."
  [k]
  (and
   (keyword? k)
   (re-matches valid-sql-identifier-re (name k))))

(s/def ::sql-keyword valid-sql-identifier-keyword?)

(sd/defn etype->table-name
  "Returns the SQL table name for the `db` and entity type `etype`. "
  ^string?
  [db
   ^::sql-keyword etype
   etype-def]
  (let [etype-table-name (when etype-def
                           (when-let [my-table-name (sde/table-name etype-def)]
                             (name my-table-name)))]
    (quotable-sql-identifier db (str/replace (or etype-table-name (name etype)) "-" "_"))))

(sd/defn field->column-name
  "Returns the SQL column name for the `db`, `field` and field definition `field-def`. "
  ^string?
  [db
   ^::sql-keyword field
   ^::sd/nilable ^::sp/field-def field-def]
  (let [column-name (when field-def
                      (when-let [my-column-name (sdf/column-name field-def)]
                        (name my-column-name)))]
    (quotable-sql-identifier db
                             (str/replace (or column-name (name field)) "-" "_"))))

(sd/defn etype->id-column
  "Returns table id column name for given entity type."
  ^string?
  [db
   ^map? schema
   ^::sql-keyword etype]
  (let [id-field (sc/id-field schema etype)]
    (field->column-name db id-field (sc/field-def schema id-field))))

(defn etype->query-table-keyword
  "Returns table / view name for querying given entity type `etype`."
  [etype etype-def]
  (let [etype-query-name (when etype-def
                           (when-let [my-table-name (sde/query-name etype-def)]
                             (name my-table-name)))]
    (keyword (str/replace (or etype-query-name (name etype)) "-" "_"))))

(defn etype->historic-query-table-keyword
  "Returns default table / view name for querying the history of given entity type `etype`."
  [etype etype-def]
  (keyword (str (name (etype->query-table-keyword etype etype-def)) "_h")))

(defmulti db-field-value->entity-field-value-impl
  "Coerces field `value` with spec `fspec` from database into entity representation."
  (fn [fspec _value] fspec))

(defmethod db-field-value->entity-field-value-impl :default
  [_fspec value]
  value)

(defn db-field-value->entity-field-value
  "Coerces field `value` with spec `fspec` from database into entity representation.
  Handles some common cases internally, delegates to db-field-value->entity-field-value-impl for everything else."
  [fspec value]
  (db-field-value->entity-field-value-impl fspec
                                           value))

(defmulti entity-field-value->db-field-value-impl
  "Coerces field `value` with spec `fspec` from entity into database representation."
  (fn [_schema fspec _value] fspec))

(defmethod entity-field-value->db-field-value-impl :default
  [_schema _fspec value]
  value)

(defn entity-field-value->db-field-value
  "Coerces field `value` with spec `fspec` from entity into database representation.
  Can handle some common cases internally, delegates to entity-field-value->db-field-value-impl for everything else."
  [schema fspec value]
  (entity-field-value->db-field-value-impl schema fspec value))

(defn entity->row
  "Converts an entity to a database table row."
  [db schema etype entity]
  (when-let [field-defs (sc/field-defs schema etype)]
    (let [entity-id (sc/id-field schema etype)
          fields    (into {}
                          (map (fn [[field value]]
                                 (when-let [field-def (field field-defs)]
                                   (when-not (or (sf/inverse? field-def)
                                                 (sf/reference-coll? field-def))
                                     [(field->column-name db field field-def)
                                      (entity-field-value->db-field-value schema
                                                                          (sf/dispatch field-def)
                                                                          value)]))))
                          entity)]
      (assoc fields
             (etype->id-column db schema etype)
             (get entity entity-id)))))
