(ns ^:no-doc specomatic-db.db.generic
  "Generates hugsql functions from specomatic-generic.sql."
  (:require
   [hugsql.adapter.next-jdbc :as adp]
   [hugsql.core              :as hugsql]))

(hugsql/def-db-fns "sql/specomatic-generic.sql"
                   {:quoting :ansi
                    :adapter (adp/hugsql-adapter-next-jdbc)})

(hugsql/def-sqlvec-fns "sql/specomatic-generic.sql"
                       {:quoting :ansi
                        :adapter (adp/hugsql-adapter-next-jdbc)})
