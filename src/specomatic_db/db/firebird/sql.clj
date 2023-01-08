(ns ^:no-doc specomatic-db.db.firebird.sql
  "Implements multimethods with hugsql functions for firebird sql storage."
  (:require
   [hugsql.adapter.next-jdbc       :as adp]
   [hugsql.core                    :as hugsql]
   [specomatic-db.db.firebird.util :refer [firebirdsql]]
   [specomatic-db.db.sql           :as sql]))

(hugsql/def-db-fns "sql/firebird.sql"
                   {:quoting :ansi
                    :adapter (adp/hugsql-adapter-next-jdbc)})

(hugsql/def-sqlvec-fns "sql/firebird.sql"
                       {:quoting :ansi
                        :adapter (adp/hugsql-adapter-next-jdbc)})

(defmethod sql/create-or-replace-view firebirdsql
  [_dbtype params]
  (create-or-replace-view-sqlvec params))

(defmethod sql/upsert-reference-coll-element! firebirdsql
  [db params]
  (upsert-reference-coll-element! db params))
