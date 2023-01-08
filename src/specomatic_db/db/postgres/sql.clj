(ns ^:no-doc specomatic-db.db.postgres.sql
  "Implements multimethods with hugsql functions for postgresql storage."
  (:require
   [hugsql.adapter.next-jdbc       :as adp]
   [hugsql.core                    :as hugsql]
   [specomatic-db.db.postgres.util :refer [postgresql]]
   [specomatic-db.db.sql           :as sql]))

(hugsql/def-db-fns "sql/postgres.sql"
                   {:quoting :ansi
                    :adapter (adp/hugsql-adapter-next-jdbc)})

(hugsql/def-sqlvec-fns "sql/postgres.sql"
                       {:quoting :ansi
                        :adapter (adp/hugsql-adapter-next-jdbc)})

(defmethod sql/create-or-replace-view postgresql
  [_dbtype params]
  (create-or-replace-view-sqlvec params))

(defmethod sql/upsert-reference-coll-element! postgresql
  [db params]
  (upsert-reference-coll-element! db params))
