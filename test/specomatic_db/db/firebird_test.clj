(ns specomatic-db.db.firebird-test
  (:require
   [clojure.test               :refer [deftest is testing]]
   [next.jdbc                  :as jdbc]
   [next.jdbc.result-set       :as rs]
   [specomatic-db.db.migration :as migration]
   [specomatic-db.db.mutation  :as mut])
  (:import [org.firebirdsql.testcontainers FirebirdContainer]))

(defn- builder-fn
  "Builds a result set."
  [rs _opts]
  (let [rsmeta (.getMetaData rs)
        cols   (mapv (fn [^Integer i] (.getColumnLabel rsmeta i))
                     (range 1
                            (inc (if rsmeta
                                   (.getColumnCount rsmeta)
                                   0))))]
    (rs/->MapResultSetBuilder rs rsmeta cols)))

(deftest unit-tests
  (let [db-container (doto (FirebirdContainer.)
                      (.setDockerImageName "jacobalberty/firebird:2.5-sc")
                      (.withDatabaseName "test.fdb")
                      (.withUsername "sysdba")
                      (.withPassword "masterke")
                      (.start))
        db           {:connection-uri (.getJdbcUrl db-container)
                      :jdbcUrl        (.getJdbcUrl db-container)
                      :user           (.getUsername db-container)
                      :password       (.getPassword db-container)}
        ds           (jdbc/get-datasource db)]
    (try
      (testing "Setup schema for assertions"
        (migration/ensure-transaction-infrastructure! db)
        (jdbc/execute! ds ["create table foo (id integer primary key, bar integer, baz varchar(32))"]))
      (testing "Insert into database"
        (let [inserted (mut/insert! db
                                    {:foo {:field-defs {:id  {:kind :simple}
                                                        :bar {:kind :simple}
                                                        :baz {:kind :simple}}
                                           :id-field   :id}}
                                    :foo
                                    {:id  1
                                     :bar 1
                                     :baz "qux"})
              rows     (jdbc/execute!
                        ds
                        ["select id, bar, baz from foo"]
                        {:builder-fn builder-fn})]
          (is (= 1 (count rows)) "Table foo should contain only one record.")
          (is (= (select-keys inserted ["ID" "BAR" "BAZ"])
                 (first rows)))))
      (finally
       (.stop db-container)))))
