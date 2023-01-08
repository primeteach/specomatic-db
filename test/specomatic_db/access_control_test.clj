(ns specomatic-db.access-control-test
  (:require
   [clojure.test                 :refer [deftest is testing]]
   [specomatic-db.access-control :as ac]
   [specomatic-db.test.config    :as config]
   [specomatic-db.test.schema    :as-alias schema]))

(deftest view-name-test
  (is (= "ac_owner_potato" (ac/view-name :predicate/owner :vegetable/potato))
      "view-name should concatenate prefix, predicate and entity names")
  (is
   (= "ac_541ceaad3042b48c64f37d00b330" (ac/view-name :predicate/potato-variety-administrator :variety/potato-variety))
   "view-name should concatenate prefix and a truncated md5 hash of predicate and entity if it otherwise became longer than 31 characters (firebird limit)"))

(deftest view-name-seql-test
  (is (= "ac-owner-potato-variety" (ac/view-name-seql :predicate/owner :variety/potato-variety))
      "view-name-seql should replace _ with - to conform with seql conventions"))

(deftest view-sql-test
  (is
   (=
    ["create or replace view\nac_director_movie\nas\nSELECT movie.id AS movieid, user_.id AS userid FROM movie INNER JOIN moviedirector ON movie.id = moviedirector.movieid INNER JOIN director ON moviedirector.directorid = director.id INNER JOIN user_ ON director.id = user_.directorid"]
    (ac/view-sql
     {:dbtype "postgresql"}
     config/config
     :predicate/director
     ::schema/movie
     (get-in config/config [:ac-predicates :predicate/director ::schema/movie])))
   "view-sql should return SQL DDL for Postgres access control predicate view")
  (is
   (=
    ["create or alter view\nac_director_movie\nas\nSELECT movie.id AS movieid, user_.id AS userid FROM movie INNER JOIN moviedirector ON movie.id = moviedirector.movieid INNER JOIN director ON moviedirector.directorid = director.id INNER JOIN user_ ON director.id = user_.directorid"]
    (ac/view-sql
     {:dbtype "firebirdsql"}
     config/config
     :predicate/director
     ::schema/movie
     (get-in config/config [:ac-predicates :predicate/director ::schema/movie])))
   "view-sql should return SQL DDL for Firebird access control predicate view"))

(deftest views-sql-test
  (is
   (=
    '(["create or replace view\nac_reviewer_review\nas\nSELECT review.id AS reviewid, user_.id AS userid FROM review INNER JOIN reviewer ON review.reviewerid = reviewer.id INNER JOIN user_ ON reviewer.id = user_.reviewerid"]
      ["create or replace view\nac_reviewer_paragraph\nas\nSELECT paragraph.id AS paragraphid, user_.id AS userid FROM paragraph INNER JOIN review ON paragraph.reviewid = review.id INNER JOIN reviewer ON review.reviewerid = reviewer.id INNER JOIN user_ ON reviewer.id = user_.reviewerid"]
      ["create or replace view\nac_director_movie\nas\nSELECT movie.id AS movieid, user_.id AS userid FROM movie INNER JOIN moviedirector ON movie.id = moviedirector.movieid INNER JOIN director ON moviedirector.directorid = director.id INNER JOIN user_ ON director.id = user_.directorid"])
    (ac/views-sql
     {:dbtype "postgresql"}
     config/config))
   "views-sql should return SQL DDL for all access control predicate views"))

(deftest root?-test
  (is (true? (ac/root? {:root? true}))
      "root? should return true if :root? equals true")
  (is (true? (ac/root? true))
      "root? should return true if user equals true")
  (is (false? (ac/root? {:id 1}))
      "root? should return false if user does not equal true and :root? does not equal true"))

(def jane
  "Represents a *Jane* persona that is allowed to:
   - eat all tomatoes
   - throw, fry and do CRUD operations with all potatoes"
  {:permissions [{:permission/verb :verb/eat
                  :permission/obj  :vegetable/tomato
                  :permission/pred :predicate/none}
                 {:permission/verb :verb/throw
                  :permission/obj  :vegetable/potato
                  :permission/pred :predicate/none}
                 {:permission/verb :verb/fry
                  :permission/obj  :vegetable/potato
                  :permission/pred :predicate/none}
                 {:permission/verb :verb/*
                  :permission/obj  :vegetable/potato
                  :permission/pred :predicate/none}]})

(def john
  "Represents a *John* persona that is allowed to:
   - eat all tomatoes
   - mash all potatoes
   - do CRUD on own potatoes"
  {:permissions [{:permission/verb :verb/eat
                  :permission/obj  :vegetable/tomato
                  :permission/pred :predicate/none}
                 {:permission/verb :verb/mash
                  :permission/obj  :vegetable/potato
                  :permission/pred :predicate/none}
                 {:permission/verb :verb/*
                  :permission/obj  :vegetable/potato
                  :permission/pred :predicate/owner}]})

(deftest test-allowed-all?
  (testing "Check access on all entities of a type"
    (is (true? (ac/allowed-all? jane :verb/delete :vegetable/potato))
        "Jane should be allowed to delete all potatoes")
    (is (false? (ac/allowed-all? john :verb/delete :vegetable/tomato))
        "John should not be allowed to delete all tomatoes")
    (is (false? (ac/allowed-all? jane :verb/mash :vegetable/tomato))
        "Jane should not be allowed to mash all tomatoes")
    (is (false? (ac/allowed-all? john :verb/eat :vegetable/potato))
        "John should not be allowed to eat all potatoes")
    (is (false? (ac/allowed-all? jane :verb/eat :vegetable/potato))
        "Jane should not be allowed to eat all potatoes")
    (is (true? (ac/allowed-all? john :verb/mash :vegetable/potato))
        "John should be allowed to mash all potatoes")))

(deftest test-allowed-some?
  (testing "Check access on some entities of a type"
    (is (false? (ac/allowed-some? jane :verb/delete :vegetable/tomato))
        "Jane should not be allowed to delete any tomatoes")
    (is (true? (ac/allowed-some? john :verb/delete :vegetable/potato))
        "John should be allowed delete some potatoes")
    (is (true? (ac/allowed-some? jane :verb/fry :vegetable/potato))
        "Jane should be allowed to fry some potatoes")
    (is (false? (ac/allowed-some? john :verb/eat :vegetable/potato))
        "John should be not allowed to eat any potatoes")
    (is (true? (ac/allowed-some? jane :verb/eat :vegetable/tomato))
        "Jane should be allowed to eat some tomatoes")
    (is (true? (ac/allowed-some? john :verb/eat :vegetable/tomato))
        "John should be allowed to eat some tomatoes")))

(deftest test-allowed?
  (testing "Check access on specific entities"
    (is (false? (ac/allowed? jane :verb/delete :vegetable/tomato {:tomato/serial-number "203098120983"}))
        "Jane should not be allowed to delete this tomato")
    (is (false? (ac/allowed? john :verb/delete :vegetable/potato {:potato/color :color/green}))
        "John should not be allowed to delete this potato")
    (is (false? (ac/allowed? jane :verb/mash :vegetable/potato {:potato/color :color/brown}))
        "John should not be allowed to mash this potato")
    (is (false? (ac/allowed? john :verb/throw :vegetable/potato {:potato/color :color/blue}))
        "John should not be allowed to throw this potato")
    (is (true? (ac/allowed? jane :verb/eat :vegetable/tomato {:tomato/serial-number "0000048192382"}))
        "Jane should be allowed to eat this tomato")
    (is (true? (ac/allowed? john
                            :verb/eat
                            :vegetable/potato
                            {:verb/eat     true
                             :verb/throw   false
                             :potato/color :color/yellow}))
        "John should be allowed to eat this potato")))

(deftest test-may-read-some?
  (testing
    "Check read access on some entities of a type"
    (is (true? (ac/may-read-some? :vegetable/potato (:permissions jane)))
        "Jane should be allowed to read some potatoes")
    (is (true? (ac/may-read-some? :vegetable/potato (:permissions john)))
        "John should be allowed to read some potatoes")))

(deftest sufficient-predicates-test
  (is
   (= #{:predicate/director}
      (ac/sufficient-predicates ::schema/movie :verb/read config/restrictive-director-permissions))
   "If a director has restrictive permissions, only the :predicate/director predicate is in the set of sufficient predicates for the :verb/read verb, it has to be true for them being allowed to read a movie")
  (is
   (= #{:predicate/none :predicate/director}
      (ac/sufficient-predicates ::schema/movie :verb/read config/director-permissions))
   "If a director has normal permissions, the special :predicate/none predicate is in the set of sufficient predicates for the :verb/read verb, meaning they are allowed to read all movies")
  (is
   (= #{}
      (ac/sufficient-predicates ::schema/movie :verb/delete config/restrictive-director-permissions))
   "If a director has restrictive permissions, the set of sufficient predicates for the :verb/delete verb is empty, meaning the are not allowed to delete any movies")
  (is
   (= #{:predicate/director}
      (ac/sufficient-predicates ::schema/movie :verb/delete config/director-permissions))
   "If a director has normal permissions, only the :predicate/director predicate is in the set of sufficient predicates for the :verb/read verb, it has to be true for them being allowed to delete a movie"))

(deftest etypes-extra-conditions-test
  (is (= []
         (ac/etypes-extra-conditions config/schema
                                     #{::schema/movie ::schema/actor}
                                     config/director-permissions
                                     :verb/read
                                     {:user-id    1
                                      :user-etype ::schema/user}))
      "If a director has normal permissions, no extra conditions apply for reading movies or actors")
  (is
   (=
    [[:and
      [:exists
       {:select [:userid]
        :from   [:ac_director_movie]
        :where  [:and
                 [:= :userid 1]
                 [:or [:= :movie.id nil] [:= :movieid :movie.id]]]}]]]
    (ac/etypes-extra-conditions config/schema
                                #{::schema/movie ::schema/actor}
                                config/restrictive-director-permissions
                                :verb/read
                                {:user-id    1
                                 :user-etype ::schema/user}))
   "If a director has restrictive permissions, entities-extra-conditions should return a vector of extra conditions containing the ac_director_movie predicate check"))

(deftest conditions-snippet-test
  (is (nil?
       (ac/conditions-snippet config/schema
                              #{::schema/movie ::schema/actor}
                              config/director-permissions
                              :verb/read
                              {:user-id    1
                               :user-etype ::schema/user}))
      "If a director has normal permissions, conditions-snippet should return nil")
  (is
   (=
    ["EXISTS (SELECT userid FROM ac_director_movie WHERE (userid = ?) AND ((movie.id IS NULL) OR (movieid = movie.id)))"
     1]
    (ac/conditions-snippet config/schema
                           #{::schema/movie ::schema/actor}
                           config/restrictive-director-permissions
                           :verb/read
                           {:user-id    1
                            :user-etype ::schema/user}))
   "If a director has restrictive permissions, conditions-snippet should return a sqlvec containing the ac_director_movie predicate check"))

(deftest fields-extra-read-conditions-test
  (is
   (=
    [[:and
      [:exists
       {:select [:userid]
        :from   [:ac_director_movie]
        :where  [:and
                 [:= :userid 1]
                 [:or [:= :movie.id nil] [:= :movieid :movie.id]]]}]]]
    (ac/fields-extra-read-conditions config/schema
                                     [:movie/title {:movie/actors [:actor/name]}]
                                     config/restrictive-director-permissions
                                     1
                                     :schema/user))
   "If a director has restrictive permissions, fields-extra-read-conditions should return a vector of extra conditions containing the ac_director_movie predicate check"))

(deftest fields-forbidden-entities
  (is
   (= '(::schema/paragraph)
      (ac/fields-forbidden-entities config/schema
                                    [{:movie/reviews [:review/title {:review/paragraphs [:paragraph/content]}]}]
                                    config/director-permissions))
   "For a query including paragraph fields and director permissions, fields-forbidden-entities should return a sequence containing the paragraph entity (unqualified keyword)")
  (is
   (nil?
    (ac/fields-forbidden-entities config/schema
                                  [:review/title {:review/paragraphs [:paragraph/content]}]
                                  config/reviewer-permissions))
   "For a query including only review and paragraph fields and reviewer permissions, fields-forbidden-entities should return nil"))

(deftest fields-allowed?
  (is
   (false?
    (ac/fields-allowed? config/schema
                        [{:movie/reviews [:review/title {:review/paragraphs [:paragraph/content]}]}]
                        config/director-permissions))
   "For a query including paragraph fields and director permissions, fields-allowed? should return false")
  (is
   (true?
    (ac/fields-allowed? config/schema
                        [:review/title {:review/paragraphs [:paragraph/content]}]
                        config/reviewer-permissions))
   "For a query including only review and paragraph fields and reviewer permissions, fields-allowed? should return true"))
