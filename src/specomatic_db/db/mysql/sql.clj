(ns ^:no-doc specomatic-db.db.mysql.sql
  "Implements multimethods with hugsql functions for mysqlql storage."
  (:require
   [hugsql.adapter.next-jdbc    :as adp]
   [hugsql.core                 :as hugsql]
   [specomatic-db.db.mysql.util :refer [mysql]]
   [specomatic-db.db.sql        :as sql]))

(hugsql/def-db-fns "sql/mysql.sql"
                   {:quoting :mysql
                    :adapter (adp/hugsql-adapter-next-jdbc)})

(hugsql/def-sqlvec-fns "sql/mysql.sql"
                       {:quoting :mysql
                        :adapter (adp/hugsql-adapter-next-jdbc)})

(defmethod sql/create-or-replace-view mysql
  [_dbtype params]
  (create-or-replace-view-sqlvec params))

(defmethod sql/upsert-reference-coll-element! mysql
  [db params]
  (upsert-reference-coll-element! db params))
