(ns specomatic-db.schema
  "Functions working with the specomatic-db schema, extending the specomatic schema."
  (:require
   [nedap.speced.def        :as sd]
   [specomatic-db.etype-def :as sde]
   [specomatic-db.spec      :as sp]
   [specomatic.core         :as sc]
   [specomatic.etype-def    :as se]
   [specomatic.field-def    :as sf]
   [specomatic.util         :as su]))

(sd/defn default-fk-column
  "Returns the default keyword for foreign key columns referencing entities of type `etype` in the `schema`."
  ^::sp/field [^::sp/schema schema ^::sp/etype etype]
  (or
   (get-in schema [etype :default-fk])
   (keyword (str (name etype) "id"))))

(sd/defn defaults-schema
  "Given a `schema`, returns a (partial) schema containing defaults."
  [^::sp/schema schema]
  (reduce-kv #(assoc % %2 (sde/defaults schema %2 %3)) {} schema))

(sd/defn etypes-by-simple-keywords*
  "Given a `schema`, returns a map of simple keywords by qualified keywords representing entity types."
  ^:private ^map? [^::sp/schema schema]
  (zipmap (map su/strip-ns (keys schema)) (keys schema)))

(def etypes-by-simple-keywords
  "Returns a map of fields to field definitions, with the entity type assoc'd to the :etype key, in `schema` (memoized)."
  (memoize etypes-by-simple-keywords*))

(sd/defn etype-from-simple-keyword
  "Given a `schema` and `simple-etype` returns a qualified entity type keyword."
  [^::sp/schema schema ^keyword? simple-etype]
  (simple-etype (etypes-by-simple-keywords schema)))

(sd/defn persistent-field-defs
  "Returns the persistent field defs for the `etype` in the `schema`."
  ^::sd/nilable ^::sp/field-defs [^::sp/schema schema ^::sp/etype etype]
  (let [field-defs (sc/field-defs schema etype)]
    (select-keys field-defs
                 (for [[k v] field-defs
                       :when (not (or (sf/inverse? v)
                                      (sf/reference-coll? v)
                                      (:not-persistent? v)))]
                   k))))

(sd/defn full-schema
  "Given a `schema` returned from `specomatic.registry/schema` spec and optionally `overrides` to override defaults,
  returns an schema enriched with database-specific defaults."
  ^::sp/schema [^::sp/schema schema ^::sd/nilable ^map? overrides]
  (merge-with
   se/merge-etype-defs
   schema
   (defaults-schema schema)
   overrides))
