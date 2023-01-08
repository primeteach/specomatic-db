(ns specomatic-db.core-test
  "Unit tests for db functions"
  (:require
   [clojure.test               :refer [deftest testing is use-fixtures]]
   [specomatic-db.core         :as core]
   [specomatic-db.db.migration :as migration]
   [specomatic-db.test.config  :as config]
   [specomatic-db.test.schema  :as-alias schema])
  (:import [java.sql SQLException]
           [org.firebirdsql.testcontainers FirebirdContainer]
           [org.testcontainers.containers PostgreSQLContainer]))

; an extremely simplified example of a movie catalog.

(defonce ^{:doc "Database test container."}
         ^FirebirdContainer firebird-container
         (doto (FirebirdContainer.)
          (.setDockerImageName "jacobalberty/firebird:2.5-sc")
          (.withDatabaseName "test.fdb")
          (.withUsername "sysdba")
          (.withPassword "masterke")))

(defonce ^{:doc "Database test container."}
         ^PostgreSQLContainer postgres-container
         (doto (PostgreSQLContainer.)
          (.setDockerImageName "postgres:14-alpine")
          (.withDatabaseName "test")
          (.withUsername "sysdba")
          (.withPassword "masterke")))

(def
  ^{:doc "The current database container"}
  current-container
  nil)

(defn db-spec
  "Database configuration for test container."
  []
  {:connection-uri (.getJdbcUrl current-container)
   :jdbcUrl        (.getJdbcUrl current-container)
   :user           (.getUsername current-container)
   :password       (.getPassword current-container)})

;; Actual testing

(def keanu-reeves
  "The actor Keanu Reeves."
  {:actor/name "Keanu Reeves"})

(def laurence-fishburne
  "The actor Laurence Fishburne."
  {:actor/name "Laurence Fishburne"})

(def the-wachowskis
  "The Wachowski director duo."
  {:director/name "The Wachowskis"})

(def chad-stahelski
  "The director Chad Stahelski."
  {:director/name "Chad Stahelski"})

(def david-leitch
  "The director David Leitch."
  {:director/name "David Leitch"})

(def the-matrix-cast
  "The cast (ensemble of actors) for The Matrix movies."
  [keanu-reeves laurence-fishburne])

(def john-wick-cast
  "The cast for the John Wick original movie."
  [keanu-reeves])

(def john-wick-chapter-two-cast
  "The cast for the John Wick movie sequel."
  [keanu-reeves laurence-fishburne])

(def the-matrix
  "The original Matrix movie."
  #:movie{:title        "The Matrix"
   :release-year 1999
   :actors       the-matrix-cast
   :directors    [the-wachowskis]})

(def the-matrix-reloaded
  "The sequel to The Matrix movie."
  #:movie{:title        "The Matrix Reloaded"
   :release-year 2003
   :actors       the-matrix-cast
   :directors    [the-wachowskis]})

(def the-matrix-revolutions
  "Third installment of The Matrix."
  #:movie{:title        "The Matrix Revolutions"
   :release-year 2003
   :actors       the-matrix-cast
   :directors    [the-wachowskis]})

(def john-wick
  "The original John Wick movie."
  #:movie{:title        "John Wick"
   :release-year 2014
   :actors       [keanu-reeves]
   :directors    [chad-stahelski david-leitch]})

(def john-wick-chapter-two
  "The sequel to the John Wick movie."
  #:movie{:title        "John Wick: Chapter 2"
   :release-year 2017
   :actors       [keanu-reeves laurence-fishburne]
   :directors    [chad-stahelski]})

(def john-wick-chapter-three
  "Third installment of the John Wick movie."
  #:movie{:title        "John Wick 3"
   :release-year 2019
   :actors       [keanu-reeves laurence-fishburne]
   :directors    [chad-stahelski]})

(def jane
  "Jane, who writes movie reviews"
  #:reviewer{:name "Jane"})

(def john
  "John, who writes movie reviews"
  #:reviewer{:name "John"})

(def friendly-review
  "A friendly movie review."
  #:review{:title      "Highly recommend"
   :stars      5
   :paragraphs [{:paragraph/content "Awesome."}
                        {:paragraph/content "Just awesome."}]})

(def grumpy-review
  "A grumpy movie review."
  #:review{:title      "Don't watch this movie"
   :stars      1
   :paragraphs [{:paragraph/content "Didn't like it."}
                        {:paragraph/content "Fell asleep while watching and had a bad dream."}]})

(def chad-stahelski-user
  "Chad Stahelski's user."
  {:user/username "chad-stahelski"})

(def the-wachowskis-user
  "The Wachowskis' user."
  {:user/username "the-wachowskis"})

(def jane-user
  "Jane's user."
  {:user/username "jane"})

(def john-user
  "John's user."
  {:user/username "john"})

(defn get-env
  "Gets the environment including the connection to the current database container."
  []
  {:jdbc   (db-spec)
   :config config/config
   :user   {:root? true}})

(defn- init!
  []
  (core/init! (get-env)))

(defn- start!
  [container]
  (.start container)
  (alter-var-root #'current-container (constantly container)))

(defn- stop!
  [container]
  (.stop container))

(defn- index-single
  [maps k]
  (->> maps
       (group-by k)
       (reduce-kv #(assoc % %2 (first %3)) {})))

(defn- save-many!
  [env etype xs]
  (mapv #(core/save! env etype %) xs))

(defn- insert-all!
  [env]
  (let [reviewers-by-name   (index-single
                             (save-many! env
                                         ::schema/reviewer
                                         [jane john])
                             :reviewer/name)
        saved-actors        (save-many! env
                                        ::schema/actor
                                        [keanu-reeves
                                         laurence-fishburne])
        actors-by-name      (index-single saved-actors :actor/name)
        saved-directors     (save-many! env
                                        ::schema/director
                                        [chad-stahelski
                                         david-leitch
                                         the-wachowskis])
        directors-by-name   (index-single saved-directors :director/name)
        movies
        (map
         (fn [movie]
           (-> movie
               (update
                :movie/actors
                (fn [actors]
                  (for [actor actors]
                    (->> actor
                         :actor/name
                         (get actors-by-name)))))
               (update
                :movie/directors
                (fn [directors]
                  (for [director directors]
                    (->> director
                         :director/name
                         (get directors-by-name)))))))
         [the-matrix the-matrix-reloaded the-matrix-revolutions john-wick john-wick-chapter-two
          john-wick-chapter-three])
        saved-movies        (doall (save-many! env ::schema/movie movies))
        saved-users-by-name
        (index-single
         (save-many!
          env
          ::schema/user
          [(assoc chad-stahelski-user :user/director (get-in directors-by-name ["Chad Stahelski" :director/id]))
           (assoc the-wachowskis-user :user/director (get-in directors-by-name ["The Wachowskis" :director/id]))
           (assoc jane-user :user/reviewer (get-in reviewers-by-name ["Jane" :reviewer/id]))
           (assoc john-user :user/reviewer (get-in reviewers-by-name ["John" :reviewer/id]))])
         :user/username)]
    {::schema/actors    saved-actors
     :actors-by-name    actors-by-name
     ::schema/directors saved-directors
     :directors-by-name directors-by-name
     ::schema/movies    saved-movies
     :reviewers-by-name reviewers-by-name
     :users-by-name     saved-users-by-name}))

(defn- submap?
  "Check for (shallow) partial equality between maps."
  [sub-m m]
  (reduce-kv
   (fn [r k v]
     (and r
          (cond
            (map? v)  (submap? v (get m k))
            (coll? v) (every? true?
                              (keep-indexed (fn [i x]
                                              (let [y (nth (get m k) i)]
                                                (cond
                                                  (map? x) (submap? x y)
                                                  :else    (= x y))))
                                            v))
            :else     (is (= v (get m k))))))
   true
   sub-m))

(defn- submaps?
  "Apply submap to collections. `a` contains maps which are a submap of `b`."
  [b a]
  (and (= (count a) (count b))
       (every? true?
               (keep-indexed
                (fn [i x] (submap? x (nth b i)))
                a))))

(deftest schema-tests
  (let [db  (db-spec)
        env {:jdbc   db
             :config config/config
             :user   {:id    1
                      :root? true}}]
    (testing "Compare against empty database"
      (is (= (set (keys (migration/diff-schema db config/schema)))
             #{::schema/actor ::schema/director ::schema/movie :movie/actors :movie/directors ::schema/paragraph
               ::schema/review ::schema/reviewer ::schema/user})
          "all entities and reference collections should require updating"))
    (testing "Compare only selected entity"
      (is (= (set (keys (migration/diff-schema db config/schema [::schema/actor])))
             #{::schema/actor})
          "only specified entities should require updating"))
    (testing "Migrate the actor table without the name column into the database"
      (migration/update-schema! db
                                (update-in config/schema [::schema/actor :field-defs] #(dissoc % :actor/name))
                                [::schema/actor]))
    (testing "Migrate the name column of the actor into the database"
      (migration/update-schema! db config/schema [::schema/actor]))
    (testing
      "Insert actor records"
      (let [saved-actors (map #(core/save! env ::schema/actor %)
                              [keanu-reeves laurence-fishburne])]
        (is (every? (comp pos? :actor/id) saved-actors)
            "Every entity should get an id")
        (testing "Inserted records should equal retrieved records"
          (let [retrieved-actors (mapv #(core/by-id env ::schema/actor % [:actor/id :actor/name])
                                       (map :actor/id saved-actors))]
            (is (submaps? saved-actors retrieved-actors)
                "Retrieved actors should equal inserted actors")))))))

(deftest save-and-query-tests
  (init!)
  (let [env                           (get-env)
        saved-entities                (insert-all! env)
        the-wachowskis-user-id        (get-in saved-entities [:users-by-name "the-wachowskis" :user/id])
        actor-id                      (get-in saved-entities [:actors-by-name "Laurence Fishburne" :actor/id])
        restricted-env                (assoc env
                                             :user
                                             {:id          the-wachowskis-user-id
                                              :permissions config/director-permissions})
        more-restricted-env           (assoc env
                                             :user
                                             {:id          the-wachowskis-user-id
                                              :permissions config/restrictive-director-permissions})
        saved-the-matrix
        (first (::schema/movies saved-entities))
        saved-the-matrix-reloaded
        (second (::schema/movies saved-entities))
        saved-the-matrix-revolutions
        (nth (::schema/movies saved-entities) 2)
        saved-john-wick
        (nth (::schema/movies saved-entities) 3)
        saved-john-wick-chapter-two
        (nth (::schema/movies saved-entities) 4)
        saved-john-wick-chapter-three
        (nth (::schema/movies saved-entities) 5)]
    (testing "Insert all"
      (is (map? saved-entities)))
    (testing "Query all as root, default fields"
      (is (submaps? (::schema/movies saved-entities)
                    (sort-by :movie/id (core/query env ::schema/movie)))))
    (testing "Query all as regular user, default fields"
      (is (submaps? (::schema/movies saved-entities)
                    (sort-by :movie/id (core/query restricted-env ::schema/movie)))))
    (testing "Query all as more restricted regular user, default fields"
      (is (submaps? (filter #(re-find #"Matrix" (:movie/title %))
                            (::schema/movies saved-entities))
                    (sort-by :movie/id
                             (core/query more-restricted-env ::schema/movie)))))

    (testing "Update as root"
      (is (map?
           (core/save! env ::schema/movie (assoc saved-the-matrix :movie/title "The Matrix Renamed")))))
    (testing "Update as regular user"
      (is (map?
           (core/save! restricted-env ::schema/movie (assoc saved-the-matrix :movie/title "The Matrix Renamed Twice"))))
      (is (thrown?
           Exception
           (core/save! restricted-env ::schema/movie (assoc saved-john-wick :movie/title "John Wick Renamed")))))

    (testing "Saving nested entities"
      (let [jane-reviewer-id (get-in saved-entities [:reviewers-by-name "Jane" :reviewer/id])
            john-reviewer-id (get-in saved-entities [:reviewers-by-name "John" :reviewer/id])
            jane-user-id     (get-in saved-entities [:users-by-name "jane" :user/id])
            john-user-id     (get-in saved-entities [:users-by-name "john" :user/id])
            jane-env         (assoc env
                                    :user
                                    {:id          jane-user-id
                                     :permissions config/reviewer-permissions})
            john-env         (assoc env
                                    :user
                                    {:id          john-user-id
                                     :permissions config/reviewer-permissions})]
        (testing "Create reviews with paragraphs"
          (is (core/save! jane-env
                          ::schema/review
                          (assoc friendly-review
                                 :review/reviewer jane-reviewer-id
                                 :review/movie    (:movie/id saved-john-wick))))
          (is (core/save! john-env
                          ::schema/review
                          (assoc grumpy-review
                                 :review/reviewer john-reviewer-id
                                 :review/movie    saved-john-wick))))
        (testing "Revise reviews with paragraphs"
          (let [janes-review         (first
                                      (core/query env
                                                  ::schema/review
                                                  [:review/id {:review/paragraphs [:paragraph/id :paragraph/content]}]
                                                  [[:= :review/reviewer jane-reviewer-id]]))
                janes-revised-review (-> janes-review
                                         (assoc-in [:review/paragraphs 0 ::core/change] :delete))
                johns-review         (first
                                      (core/query env
                                                  ::schema/review
                                                  [:review/id {:review/paragraphs [:paragraph/id :paragraph/content]}]
                                                  [[:= :review/reviewer john-reviewer-id]]))
                johns-revised-review (-> johns-review
                                         (assoc :review/title "OK"
                                                :review/stars 3)
                                         (dissoc :review/movie)
                                         (assoc-in [:review/paragraphs 0 :paragraph/content] "So-so.")
                                         (assoc-in [:review/paragraphs 1 :paragraph/content] "Not too bad."))]
            (is (core/save! jane-env ::schema/review janes-revised-review))
            (is (core/save! john-env ::schema/review johns-revised-review))))

        (testing "Update reviewer including user"
          (let [reviewer-including-user         (first (core/query
                                                        env
                                                        ::schema/reviewer
                                                        [:reviewer/id :reviewer/name
                                                         {:reviewer/user [:user/id
                                                                          :user/username]}]
                                                        [[:= :reviewer/name
                                                          "John"]]))
                reviewer-id                     (:reviewer/id
                                                 reviewer-including-user)
                renamed-reviewer-including-user (-> john
                                                    (assoc :reviewer/name "Jim")
                                                    (assoc-in [:reviewer/user
                                                               :user/username]
                                                              "jim"))]
            (is (pos? (get-in (core/save! env
                                          ::schema/reviewer
                                          (-> reviewer-including-user
                                              (assoc :reviewer/name "Jim")
                                              (assoc-in [:reviewer/user :user/username] "jim")))
                              [:reviewer/user :tx/id]))
                "save! should return transaction id for nested entity")
            (is (submap?
                 renamed-reviewer-including-user
                 (core/by-id env
                             ::schema/reviewer
                             reviewer-id
                             [:reviewer/id :reviewer/name
                              {:reviewer/user [:user/id :user/username]}]))
                "Both reviewer name and username should be updated")))))

    (testing "Query inverse relations"
      (let [result       (core/query restricted-env
                                     ::schema/actor
                                     [:actor/name {:actor/movies [:movie/id]}]
                                     [[:= :actor/id actor-id]])
            actor-movies (-> result
                             first
                             :actor/movies)]
        (is (= (map :movie/id
                    [saved-the-matrix saved-the-matrix-reloaded saved-the-matrix-revolutions saved-john-wick-chapter-two
                     saved-john-wick-chapter-three])
               (map :movie/id actor-movies))
            "Inverse reference collection should include all the actor's movies but no others")
        (is (= '(#:director{:name "Chad Stahelski"
                  :user #:user{:username "chad-stahelski"}})
               (core/query env
                           ::schema/director
                           [:director/name {:director/user [:user/username]}]
                           [[:= :user/username "chad-stahelski"]]))
            "Inverse reference should return a single entity")
        (is (= '(#:movie{:title   "John Wick"
                  :reviews [#:review{:title "Highly recommend"}
                                   #:review{:title "OK"}]})
               (core/query env
                           ::schema/movie
                           [:movie/title {:movie/reviews [:review/title]}]
                           [[:= :movie/title "John Wick"]]))
            "Inverse reference collection should return a collection")))

    (testing "Query historic data"
      (let [version-1 (core/by-id env
                                  ::schema/movie
                                  (:movie/id saved-john-wick-chapter-three))
            {movieid :movie/id
             title-1 :movie/title
             tx-id   :tx/id}
            saved-john-wick-chapter-three
            title-2 "John Wick: Chapter three"
            title-3 "John Wick: Chapter 3"]
        (is (> tx-id 0) "Should include a transaction id")
        (testing "Update transactions"
          (let [version-2 (core/save! env ::schema/movie (assoc version-1 :movie/title title-2))
                version-3 (core/save! env
                                      ::schema/movie
                                      (-> version-1
                                          (assoc :movie/title title-3)
                                          (assoc-in [:movie/actors 0 ::core/delete] true)))]
            (is (< tx-id (:tx/id version-2) (:tx/id version-3)) "Updates should have different transaction ids")
            (testing "Retrieve historic data"
              (testing "Query for latest version"
                (is (submap? (core/by-id env
                                         ::schema/movie
                                         movieid)
                             version-3)
                    "Query without transaction id should return latest version."))
              (testing "Query for older versions"
                (is (submap? (core/by-id (assoc env :tx/id tx-id)
                                         ::schema/movie
                                         movieid)
                             version-1)
                    "Query for transaction id of version-1 should return version-1.")
                (is (empty? (core/by-id (assoc env :tx/id (dec tx-id))
                                        ::schema/movie
                                        movieid))
                    "Record should not exist in earlier transactions.")
                (is (submap? (core/by-id (assoc env :tx/id (:tx/id version-2))
                                         ::schema/movie
                                         movieid)
                             version-2)
                    "Query for transaction id of version-2 should return version-2.")
                (is (submap? (core/by-id (assoc env :tx/id (inc (:tx/id version-3)))
                                         ::schema/movie
                                         movieid)
                             version-3)
                    "Query for later transaction id should return most recent record.")))
            (testing "Retrieve full entity history"
              (is (submaps? (core/entity-history env ::schema/movie movieid [:movie/title])
                            [#:movie{:title title-1} #:movie{:title title-2} #:movie{:title title-3}])))))))

    (testing "Query fields not present in the schema"
      (is (thrown-with-msg?
           Exception
           #"Access to fields denied"
           (core/query restricted-env ::schema/movie [:movie/id :movie/spoiler {:movie/directors [:director/fee]}]))
          "Querying fields not in the schema should throw 'Access to fields denied' for a regular user")
      (is (thrown?
           SQLException
           (core/query env ::schema/movie [:movie/id :movie/spoiler {:movie/directors [:director/fee]}]))
          "Querying fields not in the schema and not in the database should throw SQL exception for root"))

    (testing "Delete"
      (testing "Delete as root"
        (is (core/delete! env ::schema/movie (:movie/id saved-the-matrix-reloaded)))
        (is (nil?
             (core/delete! restricted-env ::schema/movie 8789))
            "Trying to delete a nonexistent movie should return nil"))
      (testing "Delete as regular user"
        (is (core/delete! restricted-env ::schema/movie (:movie/id saved-the-matrix))
            "With less restrictive permissions, the Wachowskis should be allowed to delete The Matrix")
        (is (thrown?
             Exception
             (core/delete! restricted-env ::schema/movie (:movie/id saved-john-wick)))
            "But they should still not be allowed to delete John Wick")
        (is (nil? (core/delete! restricted-env ::schema/movie (:movie/id saved-the-matrix)))
            "Trying to delete an already deleted movie should return nil"))
      (testing "Delete as more restricted regular user"
        (is
         (thrown?
          Exception
          (core/delete! more-restricted-env ::schema/movie (:movie/id saved-the-matrix)))
         "With permissions not allowing deletion of any movies, trying to delete a movie should throw Permission
         denied even if it does not exist")))))

(use-fixtures :each
              #(do
                 (start! postgres-container)
                 (try (%)
                      (finally
                       (stop! postgres-container)))
                 (start! firebird-container)
                 (try (%)
                      (finally
                       (stop! firebird-container)))))
