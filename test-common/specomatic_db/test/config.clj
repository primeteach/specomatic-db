(ns specomatic-db.test.config
  (:require
   [specomatic-db.registry    :as registry]
   [specomatic-db.test.schema :as schema]
   [specomatic.field-def      :as sf]))

(def base-config
  "The base config for specomatic-db."
  {;; Define :predicate/director: Describe the relation of a director to other entities for access control purposes
   :ac-predicates #:predicate{:director {::schema/movie {:select [[:movie.id :movieid] [:user_.id :userid]]
                                                         :from   [:movie]
                                                         :join   [:moviedirector [:= :movie.id :moviedirector.movieid]
                                                                  :director [:= :moviedirector.directorid :director.id]
                                                                  :user_ [:= :director.id :user_.directorid]]}}
                   :reviewer {::schema/paragraph {:select [[:paragraph.id :paragraphid] [:user_.id :userid]]
                                                             :from   [:paragraph]
                                                             :join   [:review [:= :paragraph.reviewid :review.id]
                                                                      :reviewer [:= :review.reviewerid :reviewer.id]
                                                                      :user_ [:= :reviewer.id :user_.reviewerid]]}
                                         ::schema/review    {:select [[:review.id :reviewid] [:user_.id :userid]]
                                                             :from   [:review]
                                                             :join   [:reviewer [:= :review.reviewerid :reviewer.id]
                                                                      :user_ [:= :reviewer.id :user_.reviewerid]]}}}
   :schema        {::schema/director {:field-defs {:director/user {:kind       ::sf/reference
                                                                   :inverse-of :user/director}}}
                   ::schema/reviewer {:field-defs {:reviewer/user {:kind       ::sf/reference
                                                                   :inverse-of :user/reviewer}}}
                   ::schema/user     {:table-name :user_}}
   :user-etype    ::schema/user})

(def config
  "The specomatic-db config for this namespace, derived from the base config and definitions read from the clojure.spec registry."
  (registry/config ['specomatic-db.test.schema] base-config))

(def schema
  "The specomatic-db schema for this namespace, read from the clojure.spec registry and overridden by the :schema part of the base config."
  (:schema config))

(def director-permissions
  "A director is allowed to see all movies, actors, reviews, and other directors,
   but can only create / update / delete their own movies."
  [{:permission/verb :verb/read
    :permission/obj  ::schema/movie
    :permission/pred :predicate/none}
   {:permission/verb :verb/*
    :permission/obj  ::schema/movie
    :permission/pred :predicate/director}
   {:permission/verb :verb/read
    :permission/obj  ::schema/actor
    :permission/pred :predicate/none}
   {:permission/verb :verb/read
    :permission/obj  ::schema/director
    :permission/pred :predicate/none}
   {:permission/verb :verb/read
    :permission/obj  ::schema/review
    :permission/pred :predicate/none}])

(def restrictive-director-permissions
  "A director is allowed to see all actors, reviews, and other directors, but can only see their own movies.
   They can create or update their own movies, but never delete them."
  [{:permission/verb :verb/read
    :permission/obj  ::schema/movie
    :permission/pred :predicate/director}
   {:permission/verb :verb/create
    :permission/obj  ::schema/movie
    :permission/pred :predicate/director}
   {:permission/verb :verb/update
    :permission/obj  ::schema/movie
    :permission/pred :predicate/director}
   {:permission/verb :verb/read
    :permission/obj  ::schema/actor
    :permission/pred :predicate/none}
   {:permission/verb :verb/read
    :permission/obj  ::schema/director
    :permission/pred :predicate/none}
   {:permission/verb :verb/read
    :permission/obj  ::schema/review
    :permission/pred :predicate/none}])

(def reviewer-permissions
  "A reviewer is allowed to see all reviews including their paragraphs,
   but can only create / update / delete their own reviews."
  [{:permission/verb :verb/read
    :permission/obj  ::schema/review
    :permission/pred :predicate/none}
   {:permission/verb :verb/read
    :permission/obj  ::schema/paragraph
    :permission/pred :predicate/none}
   {:permission/verb :verb/*
    :permission/obj  ::schema/review
    :permission/pred :predicate/reviewer}
   {:permission/verb :verb/*
    :permission/obj  ::schema/paragraph
    :permission/pred :predicate/reviewer}])
