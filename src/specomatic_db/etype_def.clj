(ns specomatic-db.etype-def
  "Functions working with specomatic-db entity type definitions, extending specomatic entity type definitions."
  (:require
   [nedap.speced.def        :as sd]
   [specomatic-db.field-def :as sdf]
   [specomatic-db.spec      :as sp]
   [specomatic.etype-def    :as se]))

(sd/defn defaults
  "Given `schema`, entity type `etype` and entity type definition `etype-def`, returns a (partial) entity type definition containing defaults."
  [^::sp/schema schema ^::sp/etype etype ^::sp/etype-def etype-def]
  (merge
   {:query-name (or (:table-name etype-def) etype)
    :table-name etype}
   (when-let [default-field-defs (reduce-kv #(when-let [my-defaults (not-empty (sdf/defaults schema etype %2 %3))]
                                               (assoc % %2 my-defaults))
                                            {}
                                            (se/field-defs etype-def))]
     {:field-defs default-field-defs})))

(sd/defn table-name
  "Given the entity type definition `etype-def`, returns the table name as a keyword."
  ^::sd/nilable ^::sp/table-name [^::sp/etype-def etype-def]
  (:table-name etype-def))

(sd/defn query-name
  "Given the entity type definition `etype-def`, returns the table / view for querying as a keyword."
  ^::sd/nilable ^::sp/table-name [^::sp/etype-def etype-def]
  (:query-name etype-def))
