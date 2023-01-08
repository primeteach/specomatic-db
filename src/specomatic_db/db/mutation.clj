(ns specomatic-db.db.mutation
  "Defines mutation functions. Should be implemented for specific sql backends."
  (:require
   [specomatic-db.db.type :refer [get-dbtype]]))

(defmulti insert!
  "Inserts a new record for a given entity. Attaches a new primary key `id`."
  (fn [db _schema _etype _entity]
    (get-dbtype db)))

(defmethod insert! :default
  [db _ _ _]
  (throw (UnsupportedOperationException. (str "No implementation found for inserting entities into database: " db))))

(defmulti update!
  "Updates a record for a given entity."
  (fn [db _schema _etype _entity _ac-conditions]
    (get-dbtype db)))

(defmethod update! :default
  [db _ _ _]
  (throw (UnsupportedOperationException. (str "No implementation found for updating entities in database: " db))))

(defmulti delete!
  "Deletes a record for a given entity."
  (fn [db _schema _etype _param-id _ac-conditions]
    (get-dbtype db)))

(defmethod delete! :default
  [db _ _]
  (throw (UnsupportedOperationException. (str "No implementation found for deleting entities in database: " db))))
