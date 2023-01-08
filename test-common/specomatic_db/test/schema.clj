(ns specomatic-db.test.schema
  (:require
   [clojure.spec.alpha             :as s]
   [specomatic-db.db.firebird.util :refer [firebirdsql]]
   [specomatic-db.db.migration     :as migration]
   [specomatic-db.db.postgres.util :refer [postgresql]]
   [specomatic.registry            :as sr]
   [specomatic.spec                :as sp]))

(s/def :spec/review-stars (s/int-in 1 6))

(defmethod migration/sql-type [firebirdsql :spec/review-stars] [_ _] "SMALLINT")

(defmethod migration/sql-type [postgresql :spec/review-stars] [_ _] "SMALLINT")

(s/def ::name string?)

(s/def ::title string?)

(s/def ::release-year integer?)

(s/def ::stars :spec/review-stars)

(sr/defent ::actor :req [:name])

(sr/defent ::director :req [:name])

(s/def :movie/actors (sp/references ::actor))

(s/def :movie/directors (sp/references ::director))

(sr/defent ::movie
           :req [:title :release-year]
           :opt [:actors :directors])

(s/def :review/movie (sp/reference ::movie))

(s/def :review/reviewer (sp/reference ::reviewer))

(sr/defent ::review :req [:movie :reviewer :stars :title])

(s/def :paragraph/review (sp/reference ::review))

(s/def :paragraph/content string?)

(sr/defent ::paragraph :req [:content :review])

(sr/defent ::reviewer :req [:name])

(s/def ::username string?)

(s/def :user/director (sp/reference ::director))

(s/def :user/reviewer (sp/reference ::reviewer))

(sr/defent ::user
           :req
           [:username]
           :opt
           [:director :reviewer])
