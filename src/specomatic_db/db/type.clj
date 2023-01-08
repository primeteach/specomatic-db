(ns specomatic-db.db.type
  "Functions for determining the database type (SQL backend) of a jdbc connectable."
  (:require
   [clojure.test :refer [with-test is]]))

(with-test
 (defn get-jdbc-url-dbtype
   "Gets the dbtype from a jdbc formatted url string."
   [s]
   (some->> s
            (re-find #"^jdbc:([^:]+):")
            second))
 (is (nil? (get-jdbc-url-dbtype nil)))
 (is (= "firebirdsql" (get-jdbc-url-dbtype "jdbc:firebirdsql://localhost:12345//firebird/data/test.fdb"))
     "db type not found.")
 (is (nil? (get-jdbc-url-dbtype "jdbc://localhost//firebird/data/test.fdb")) "Unexpected value returned."))

(defn- get-jdbc-connection-dbtype
  "Gets the dbtype from a jdbc connection if it is one."
  [conn]
  (case (str (class conn))
    "class org.firebirdsql.jdbc.FBConnection" "firebirdsql"
    "class org.postgresql.jdbc.PgConnection"  "postgresql"
    nil))

(with-test
 (defn get-dbtype
   "Gets the dbtype from a jdbc `connectable`."
   [connectable & _]
   (or (get-jdbc-connection-dbtype connectable)
       (let [{:keys [dbtype jdbcUrl connection-uri]} connectable]
         (or dbtype
             (get-jdbc-url-dbtype jdbcUrl)
             (get-jdbc-url-dbtype connection-uri)))))
 (is (= "firebirdsql" (get-dbtype {:dbtype "firebirdsql"})))
 (is (= "firebirdsql" (get-dbtype {:jdbcUrl "jdbc:firebirdsql://localhost:12345//firebird/data/test.fdb"})))
 (is (= "firebirdsql" (get-dbtype {:connection-uri "jdbc:firebirdsql://localhost:12345//firebird/data/test.fdb"})))
 (is (= "prioritized"
        (get-dbtype {:dbtype  "prioritized"
                     :jdbcUrl "jdbc:second://localhost:12345//firebird/data/test.fdb"}))))
