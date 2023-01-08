(ns specomatic-db.spec
  "Specs and spec predicate functions for specomatic-db, extending the specs for specomatic."
  (:require
   [clojure.spec.alpha :as s]
   [seql.query         :as sq]
   [specomatic.spec    :as core-spec]))

(s/def ::conditions (s/nilable (s/coll-of vector?)))

(s/def ::ac-table-predicate map?)

(s/def ::ac-predicate (s/map-of keyword? ::ac-table-predicate))

(s/def ::ac-predicates (s/nilable (s/map-of keyword? ::ac-predicate)))

(s/def ::user
 (s/keys :opt-un [::id
                  ::permissions
                  ::root?]))

(s/def ::user-etype keyword?)

(defn unique-etype-names?
  "Checks if the entity types for `schema` have unique names."
  [schema]
  (->>
   schema
   keys
   (apply distinct?)))

(s/def ::schema
 (s/and ::core-spec/schema
        unique-etype-names?))

(s/def ::etype ::core-spec/etype)

(s/def ::etype-def ::core-spec/etype-def)

(s/def ::field ::core-spec/field)

(s/def ::fields ::core-spec/fields)

(s/def ::column-name keyword?)

(s/def ::db-via
 (s/or :kw  keyword?
       :vec (s/coll-of keyword?)))

(s/def ::join-table keyword?)

(s/def ::join-table-id-field keyword?)

(s/def ::not-persistent? boolean?)

(s/def ::owns-relation? boolean?)

(s/def ::save-related? boolean?)

(s/def ::table-name keyword?)

(s/def ::field-def
 (s/merge ::core-spec/field-def
          (s/keys :opt-un [::column-name
                           ::db-via
                           ::join-table
                           ::join-table-id-field
                           ::not-persistent?
                           ::owns-relation?])))

(s/def ::field-defs (s/map-of ::field ::field-def))

(s/def ::config
 (s/keys :req-un [::schema]
         :opt-un [::user-etype]))

(s/def ::env
 (s/keys :req-un [::config
                  ::jdbc
                  ::user]
         :opt-un [:tx/id]))

(s/def ::query-result
 (s/coll-of map?))

(s/def ::nilable-query (s/nilable ::sq/seql-query))

(s/def ::change #{:create :update :delete})

(s/def ::namespaces ::core-spec/namespaces)
