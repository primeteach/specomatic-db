(ns specomatic-db.registry
  "Functions that work with the clojure.spec registry (via `specomatic.registry`) to generate the specomatic-db schema and config."
  (:require
   [nedap.speced.def     :as sd]
   [specomatic-db.schema :as schema]
   [specomatic.registry  :as sr]
   [specomatic.spec      :as sp]))

(sd/defn full-schema
  "Returns the full schema for the `namespaces` as a map of entity types to their definitions.
  Optionally restricted to a sequence of entities `only`.
  Specific entity and field definitions may by overridden by an `overrides` map in the same shape as the schema.
  Shape of result:
  {::my.namespace.entity  {:field-defs       map of fields to fields' specs
                           :id-field         id field for the entity
                           :required-fields  set of required fields}
  ::my.namespace.entity2 ... }"
  ([^::sp/namespaces namespaces ^::sd/nilable ^map? overrides]
   (full-schema namespaces overrides nil))
  ([^::sp/namespaces namespaces ^::sd/nilable ^map? overrides only]
   (schema/full-schema (sr/full-schema namespaces overrides only) overrides)))

(sd/defn config
  "Returns the full schema for the `namespaces` as a map of entity types to their definitions.
  Optionally restricted to a sequence of entities `only`.
  The `base-config` has the same shape as the result. `:ac-predicates` and `:user-entity` are for access control,
  the `:schema` part contains overrides for the schema.
  Shape of result:
  {:ac-predicates {:predicate/name {::my-namespace.entity   honeysql-query}}
   :schema        {::my.namespace.entity {:field-defs             map of fields to fields' specs
                                          :id-field               id field for the entity
                                          :required-fields        set of required fields}
            ::my.namespace.entity2 ... }
   :user-entity   ::my.namespace.user}"
  ([^::sp/namespaces namespaces]
   (config namespaces nil nil))
  ([^::sp/namespaces namespaces ^::sd/nilable ^map? base-config]
   (config namespaces base-config nil))
  ([^::sp/namespaces namespaces ^::sd/nilable ^map? base-config only]
   (merge base-config {:schema (full-schema namespaces (:schema base-config) only)})))
