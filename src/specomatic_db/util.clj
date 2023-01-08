(ns specomatic-db.util
  "Utility functions for specomatic-db."
  (:require
   [nedap.speced.def   :as sd]
   [seql.query         :as sq]
   [specomatic-db.spec :as sp]
   [specomatic.core    :as sc]))

(sd/defn flatten-fields
  "Flattens a seql `fields` sequence."
  ^::sp/fields [^::sq/seql-query fields]
  (filter keyword? (tree-seq coll? identity fields)))

(sd/defn etypes-from-fields
  "Given `schema`, returns all entity types from the `fields` sequence, as keywords."
  [^::sp/schema schema ^::sq/seql-query fields]
  (->> fields
       flatten-fields
       (map #(sc/etype-from-field schema %))
       (filter some?)
       distinct))

(defn fields-without-verbs
  "Returns the seql `fields` vector without the top-level :verb/ keywords."
  [fields]
  (vec (remove #(and (keyword? %)
                     (= "verb" (namespace %)))
               fields)))

(defn honeysql-field
  "Returns a field keyword suitable for honeysql conditions (separated by .)"
  [field]
  (keyword (str (namespace field) "." (name field))))
