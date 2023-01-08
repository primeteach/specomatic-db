(ns ^:no-doc specomatic-db.db.firebird.conversion
  "Provides conversion specialisation for firebird."
  (:require
   [clojure.string                 :as str]
   [specomatic-db.db.conversion    :as cnv]
   [specomatic-db.db.firebird.util :refer [firebirdsql]]))

(defmethod cnv/quotable-sql-identifier firebirdsql
  [_db identifier]
  (str/upper-case identifier))
