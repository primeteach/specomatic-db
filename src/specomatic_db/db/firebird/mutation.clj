(ns ^:no-doc specomatic-db.db.firebird.mutation
  "Implements mutation multimethods for firebird sql storage."
  (:require
   [clojure.string                 :as str]
   [clojure.walk                   :refer [keywordize-keys]]
   [specomatic-db.db.conversion    :as cnv]
   [specomatic-db.db.firebird.sql  :as sql]
   [specomatic-db.db.firebird.util :refer [firebirdsql]]
   [specomatic-db.db.mutation      :refer [insert! update! delete!]]
   [specomatic.core                :as sc]))

(defmethod insert! firebirdsql
  [db schema etype entity]
  (let [table-name (cnv/etype->table-name db etype (sc/etype-def schema etype))
        id-column  (cnv/etype->id-column db schema etype)
        row        (cnv/entity->row db schema etype entity)
        result
        (sql/insert! db
                     {:table    table-name
                      :id-field (cnv/etype->id-column db schema etype)
                      :cols     (keys row)
                      :vals     (vals row)})]
    (merge row
           {:id    (get result (keyword (str/lower-case id-column)))
            :tx/id (:txid result)
            :tx/ts (:txts result)})))

(defmethod update! firebirdsql
  [db schema etype entity ac-conditions]
  (let [table-name (cnv/etype->table-name db etype (sc/etype-def schema etype))
        id-column  (cnv/etype->id-column db schema etype)
        row        (cnv/entity->row db schema etype entity)
        id         (get row id-column)
        updates    (-> row
                       (dissoc id-column)
                       keywordize-keys
                       not-empty)]
    (if updates
      (let [result
            (sql/update! db
                         {:table         table-name
                          :id-field      (cnv/etype->id-column db schema etype)
                          :updates       updates
                          :id            id
                          :ac-conditions ac-conditions})]
        (merge row
               {:id    id
                :tx/id (:txid result)
                :tx/ts (:txts result)}))
      (assoc row
             :tx/id
             :noop))))

(defmethod delete! firebirdsql
  [db schema etype param-id ac-conditions]
  (let [{:keys [id
                txid
                txts]}
        (sql/delete! db
                     {:table         (cnv/etype->table-name db etype (sc/etype-def schema etype))
                      :id-field      (cnv/etype->id-column db schema etype)
                      :id            param-id
                      :ac-conditions ac-conditions})]
    (when txid
      {:id    id
       :tx/id txid
       :tx/ts txts})))
