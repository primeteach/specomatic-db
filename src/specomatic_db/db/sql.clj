(ns specomatic-db.db.sql
  "Defines multimethods to be implemented by hugsql functions for specific sql backends."
  (:require
   [specomatic-db.db.type :refer [get-dbtype]]))

(defmulti create-or-replace-view
  "Returns the SQL DDL required for creating or replacing a view"
  get-dbtype)

(defmulti upsert-reference-coll-element!
  "Inserts an element into a reference collection"
  get-dbtype)
