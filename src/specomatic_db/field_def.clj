(ns specomatic-db.field-def
  "Functions working with specomatic-db field definitions, extending specomatic field definitions."
  (:require
   [nedap.speced.def     :as sd]
   [specomatic-db.spec   :as sp]
   [specomatic.core      :as sc]
   [specomatic.field-def :as sf]
   [specomatic.util      :as su]))

(sd/defn column-name
  "Given the field definition `field-def`, returns the column name."
  ^::sd/nilable ^::sp/field [^::sp/field-def field-def]
  (:column-name field-def))

(sd/defn db-via
  "Given the field definition `field-def`, returns the db field on the opposite side of the relation, if available."
  ^::sd/nilable ^::sp/db-via [^::sp/field-def param-field-def]
  (:db-via param-field-def))

(sd/defn join-table
  "Given the field definition `field-def`, returns the join table, if available."
  ^::sd/nilable ^::sp/join-table [^::sp/field-def param-field-def]
  (:join-table param-field-def))

(sd/defn join-table-id-field
  "Given the field definition `field-def`, returns the join table id field, if available."
  ^::sd/nilable ^::sp/join-table-id-field [^::sp/field-def param-field-def]
  (:join-table-id-field param-field-def))

(sd/defn not-persistent?
  "Given the field definition `field-def`, checks if it is not persistent."
  ^boolean? [^::sp/field-def param-field-def]
  (true? (:not-persistent? param-field-def)))

(sd/defn owns-relation?
  "Given the field definition `field-def`, checks if it owns the relation."
  ^boolean? [^::sp/field-def param-field-def]
  (true? (:owns-relation? param-field-def)))

(sd/defn save-related?
  "Given the field definition `field-def`, checks if the contents of it should be saved with the entity."
  ^boolean? [^::sp/field-def param-field-def]
  (true? (:save-related? param-field-def)))

(defmulti defaults
  "Given `schema`, entity type `etype`, `field` and field definition `param-field-def`, returns a map of defaults
  for the field definition."
  (fn [_schema _etype _field param-field-def] (sf/kind param-field-def)))

(defmethod defaults :default
  [_schema _etype field _param-field-def]
  {:column-name field})

(defmethod defaults ::sf/reference
  [schema etype field param-field-def]
  (let [my-column-name      (column-name param-field-def)
        default-column-name (su/concat-keywords
                             (or (sf/via param-field-def)
                                 field)
                             :id)
        my-owns-relation?   (= (name etype) (namespace (or my-column-name default-column-name)))
        via                 (sf/via param-field-def)]
    (merge {:column-name    default-column-name
            :owns-relation? my-owns-relation?
            :save-related?  (not my-owns-relation?)}
           (when via
             {:db-via (or (column-name (sc/field-def schema via))
                          via)}))))

(defmethod defaults ::sf/reference-coll
  [schema etype _field param-field-def]
  (if (= :has-many (:reference-type param-field-def))
    (when (some? (:inverse-of param-field-def))
      (let [via (:via param-field-def)]
        {:db-via         (or (:column-name (sc/field-def schema via))
                             via)
         :owns-relation? false
         :save-related?  true}))
    (let [inverse?           (some? (:inverse-of param-field-def))
          param-join-table   (join-table param-field-def)
          default-join-table (su/concat-keywords (if inverse?
                                                   (sf/target param-field-def)
                                                   etype)
                                                 (if inverse?
                                                   etype
                                                   (sf/target param-field-def)))
          my-join-table      (or param-join-table default-join-table)
          target             (sf/target param-field-def)]
      (merge
        {:db-via              [(sc/id-field schema etype)
                               (keyword (name my-join-table) (str (name etype) "id"))
                               (keyword (name my-join-table) (str (name target) "id"))
                               (sc/id-field schema target)]
         :join-table-id-field (keyword (name my-join-table) (str (name my-join-table) "id"))
         :reference-type      :has-many-through
         :save-related?       (not inverse?)
         :owns-relation?      (not inverse?)}
        (when-not param-join-table
          {:join-table my-join-table})))))
