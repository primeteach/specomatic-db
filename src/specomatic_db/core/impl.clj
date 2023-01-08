(ns ^:no-doc specomatic-db.core.impl
  (:require
   [clojure.set                  :as set]
   [specomatic-db.access-control :as ac]
   [specomatic-db.db.conversion  :as cnv]
   [specomatic-db.schema         :as db-schema]
   [specomatic-db.seql           :as seql]
   [specomatic.core              :as sc]))

(defn- historic-conditions
  "Returns extra conditions for historic queries"
  [config etype fields txid]
  (let [[root joined] (seql/query-tables config etype fields)]
    (into
     [[:and
       [:>= txid (keyword (str (name root)) "txid_from")]
       [:< txid (keyword (str (name root)) "txid_until")]
       [:!= "D" (keyword (str (name root)) "mut")]]]
     (mapv #(vec
             [:or
              [:= (keyword (str (name %)) "txid_from") nil]
              [:and
               [:>= txid (keyword (str (name %)) "txid_from")]
               [:< txid (keyword (str (name %)) "txid_until")]
               [:!= "D" (keyword (str (name %)) "mut")]]])
           joined))))

(defn- postprocess-entity
  [etype entity config allowed-or-preds-by-verb]
  (let [verb-map
        (into {}
              (for [[verb allowed-or-preds] allowed-or-preds-by-verb]
                [verb
                 (if (true? allowed-or-preds)
                   true
                   (some? (some #(-> entity
                                     ((keyword (name etype) (ac/view-name-seql % etype)))
                                     first
                                     ((keyword (ac/view-name-seql % etype)
                                               (name (db-schema/default-fk-column (:schema config) etype)))))
                                allowed-or-preds)))]))
        preds     (->> allowed-or-preds-by-verb
                       vals
                       (filter set?)
                       (apply set/union))
        my-entity (apply dissoc
                         entity
                         (map #(keyword (name etype)
                                        (ac/view-name-seql % etype))
                              preds))]
    (merge
     my-entity
     verb-map)))

(defn entity-history
  "Retrieves the full history of an entity."
  [env etype id fields]
  (let [config
        (:config env)

        schema
        (seql/schema-historic config)

        seql-env
        (-> env
            (dissoc :config)
            (assoc :schema schema))

        table
        (cnv/etype->query-table-keyword etype (sc/etype-def (:schema config) etype))

        [seql-etype my-fields my-conditions allowed-or-preds-by-verb]
        (seql/query env
                    etype
                    (into fields [(keyword (name table) "txid_from") (keyword (name table) "txid_until")])
                    [[:= (sc/id-field (get-in env [:config :schema]) etype) id]]
                    cnv/etype->historic-query-table-keyword)]
    (map #(postprocess-entity etype % config allowed-or-preds-by-verb)
         (seql/execute-query seql-env
                             seql-etype
                             my-fields
                             my-conditions))))

(defn execute-query
  "Given the environment `env`, retrieves a sequence of `etype` entities containing the seql-style `fields` and matching the HoneySQL `conditions`.
  Optionally, the root entity may contain verbs like :verb/read, :verb/create as fields in addition to seql fields.
  These contain a boolean indicating whether or not the user (given by [:user :id] in `env`) is allowed to do what the verb describes with the specific entity.
  Shape of `env`:
  {:jdbc    database specification suitable for use with next.jdbc
   :config  specomatic config}"
  [env etype fields conditions]
  (let [{config :config
         txid   :tx/id}
        env

        schema (if txid
                 (seql/schema-historic config)
                 (seql/schema config))

        conditions
        (if txid
          (into (historic-conditions config etype fields txid)
                conditions)
          conditions)

        [seql-etype my-fields my-conditions allowed-or-preds-by-verb]
        (seql/query env
                    etype
                    fields
                    conditions
                    (if txid
                      cnv/etype->historic-query-table-keyword
                      cnv/etype->query-table-keyword))

        seql-env
        (-> env
            (dissoc :config)
            (assoc :schema schema))]
    (map #(postprocess-entity etype % config allowed-or-preds-by-verb)
         (seql/execute-query seql-env
                             seql-etype
                             my-fields
                             my-conditions))))
