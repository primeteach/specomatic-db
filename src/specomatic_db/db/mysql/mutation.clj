(ns ^:no-doc specomatic-db.db.mysql.mutation
  "Implements mutation multimethods for mysql storage."
  (:require
   [clojure.walk                :refer [keywordize-keys]]
   [specomatic-db.db.conversion :as cnv]
   [specomatic-db.db.mutation   :refer [insert! update! delete!]]
   [specomatic-db.db.mysql.sql  :as sql]
   [specomatic-db.db.mysql.util :refer [mysql]]
   [specomatic.core             :as sc]))

(defmethod insert! mysql
  [db schema etype entity]
  (let [table-name (cnv/etype->table-name db etype (sc/etype-def schema etype))
        id-column (cnv/etype->id-column db schema etype)
        row (dissoc (cnv/entity->row db schema etype entity)
                    id-column)
        {:keys [id
                txid
                txts]}
        (sql/insert! db
                     {:table    table-name
                      :id-field id-column
                      :cols     (keys row)
                      :vals     (vals row)})]
    (merge row
           {:id    id
            :tx/id txid
            :tx/ts txts})))

(defmethod update! mysql
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
      (let [{:keys [txid
                    txts]}
            (sql/update! db
                         {:table         table-name
                          :updates       updates
                          :id-field      id-column
                          :id            id
                          :ac-conditions ac-conditions})]
        (merge row
               {:tx/id txid
                :tx/ts txts}))
      (assoc row
             :tx/id
             :noop))))

(defmethod delete! mysql
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
