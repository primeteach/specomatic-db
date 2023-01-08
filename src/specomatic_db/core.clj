(ns specomatic-db.core
  "The main namespace for consumers of specomatic-db. Contains functions for initialisation, retrieving and persisting entities."
  (:require
   [clojure.spec.alpha           :as s]
   [clojure.tools.logging        :as log]
   [nedap.speced.def             :as sd]
   [next.jdbc                    :as jdbc]
   [seql.query                   :as sq]
   [specomatic-db.access-control :as ac]
   [specomatic-db.core.impl      :as impl]
   [specomatic-db.db.conversion  :as cnv]
   [specomatic-db.db.firebird.conversion]
   [specomatic-db.db.firebird.migration]
   [specomatic-db.db.firebird.mutation]
   [specomatic-db.db.generic     :as db-generic]
   [specomatic-db.db.migration   :as migration]
   [specomatic-db.db.mutation    :as mutation]
   [specomatic-db.db.postgres.migration]
   [specomatic-db.db.postgres.mutation]
   [specomatic-db.db.sql         :as sql]
   [specomatic-db.field-def      :as sdf]
   [specomatic-db.seql           :as seql]
   [specomatic-db.spec           :as sp]
   [specomatic.core              :as sc]
   [specomatic.field-def         :as sf]
   [specomatic.util              :as su]))

(sd/defn init!
  "Given the environment `env`, does all necessary initialization.
   To skip automatic database schema migration, pass `{:skip-migration? true}` as a second argument.
  Currently validates the schem, initializes transaction infrastructure, ensures access control views exist and registers coercion functions."
  ([^::sp/env env]
   (init! env {}))
  ([^::sp/env env {:keys [:skip-migration?]}]
   (let [{:keys [config jdbc]} env
         {:keys [schema]}      config]
     (log/info "Validating the schema...")
     (when-not (s/valid? ::sp/schema
                         schema)
       (throw (let [expl (s/explain-str ::sp/schema
                                        schema)]
                (ex-info (str "Invalid schema: " expl)
                         {:schema  schema
                          :explain expl}))))
     (log/info "Ensuring transaction infrastructure exists...")
     (migration/ensure-transaction-infrastructure! jdbc)
     (log/info "Clearing system transaction ids...")
     (migration/clear-transaction-system-txid! jdbc)
     (when-not skip-migration?
       (migration/update-schema! jdbc (:schema config)))
     (log/info "Initializing access control views...")
     (ac/ensure-views-exist! jdbc config)
     (log/info "Initializing transformation rules...")
     (seql/set-transform! schema)
     (log/info "Initialization complete."))))

(sd/defn default-fields
  "Given `schema` and entity type `etype`, returns a seql vector of default fields."
  ^::sq/seql-query [^::sp/schema schema ^::sp/etype etype]
  (vec
   (for [[field field-def] (sc/field-defs schema etype)]
     (if (sf/relational? field-def)
       (let [target (sf/target field-def)]
         {field (into [(sc/id-field schema target)]
                      (map (partial su/qualify target) (sc/display-name-fields schema target)))})
       field))))

(sd/defn entity-history
  "Retrieves the full history of an entity."
  ^::sp/query-result [^::sp/env env ^::sp/etype etype id ^::sq/seql-query fields]
  (impl/entity-history env etype id fields))

(sd/defn query
  "Given the environment `env`, retrieves the seql `fields` from the `etype` entities matching the HoneySQL `conditions`.
  Optionally, the root entity may contain verbs like :verb/read, :verb/update as fields in addition to seql fields.
  These contain a boolean indicating whether or not the user (given by [:user :id] in `env`) is allowed to do what the verb describes with the specific entity.
  Shape of `env`:
  {:jdbc    database specification suitable for use with next.jdbc
   :config  specomatic config
   :user    {:id           user id
             :permissions  sequence of permissions
             :root?        if user is root}}"
  ^::sp/query-result
  ([^::sp/env env ^::sp/etype etype]
   (query env etype nil nil))
  ([^::sp/env env ^::sp/etype etype ^::sp/nilable-query fields]
   (query env etype fields nil))
  ([^::sp/env env ^::sp/etype etype ^::sp/nilable-query fields ^::sp/conditions conditions]
   (let [schema    (get-in env [:config :schema])
         my-fields (or fields
                       (default-fields schema etype))]
     (ac/check-query-arguments env my-fields conditions)
     (impl/execute-query env
                         etype
                         my-fields
                         (ac/concatenate-extra-conditions env my-fields conditions)))))

(sd/defn by-id
  "Retrieves an entity by id. Returns nil if not found."
  (^::sd/nilable ^map? [^::sp/env env ^::sp/etype etype id ^::sq/seql-query fields]
   (-> (query env etype fields [[:= (sc/id-field (get-in env [:config :schema]) etype) id]])
       first))
  (^::sd/nilable ^map? [^::sp/env env ^::sp/etype etype id]
   (by-id env etype id (default-fields (get-in env [:config :schema]) etype))))

(defmulti save-related!
  "Saves the changeset `value` for related entities contained in a relational `field` of a entity to the database.
  `opts` is a map of outer-etype and outer-id.
  If `:specomatic.core/delete` is true in the related entity, it is deleted.
  Otherwise, if the id field of the related entity is not nil, it is updated, if the id field is not present or nil, it is created.
  In the case of a reference collection of a has-many-through type, these mutations are applied to the join table, not the actual related entity."
  (fn [_env _field field-def _value _opts] [(sf/kind field-def) (sf/reference-type field-def)]))

(defn- extract-reference-id
  [my-ref id-field]
  (if (map? my-ref)
    (id-field my-ref)
    my-ref))

(defn- extract-reference-ids
  [schema etype entity]
  (merge entity
         (into {}
               (for [[field field-def] (sc/field-defs schema etype)
                     :when             (and (sf/relational? field-def)
                                            (sdf/owns-relation? field-def)
                                            (nil? (sdf/join-table field-def))
                                            (field entity))]
                 [field
                  (let [target-id-field (sc/id-field schema (sf/target field-def))]
                    (-> entity
                        field
                        (extract-reference-id target-id-field)))]))))

(defn- create*
  [{:keys [config jdbc user]
    :as   env}
   etype
   entity]
  (let [{:keys [schema]}   config
        my-entity          (extract-reference-ids schema etype entity)
        result             (mutation/insert! jdbc schema etype my-entity)
        my-entity-id-value (:id result)
        transaction-id     (:tx/id result)
        entity-id          (sc/id-field schema etype)
        ret                (merge entity
                                  {entity-id my-entity-id-value
                                   :tx/id    transaction-id}
                                  (into {}
                                        (for [[field field-def] (sc/field-defs schema etype)
                                              :let              [v (field my-entity)]
                                              :when             (and v (sdf/save-related? field-def))]
                                          [field
                                           (save-related! env
                                                          field
                                                          field-def
                                                          v
                                                          {:outer-etype etype
                                                           :outer-id    my-entity-id-value})])))]
    (when-not (or
               (:root? user)
               (ac/allowed-all? user :verb/create etype)
               (:verb/create (by-id env etype my-entity-id-value [entity-id :verb/create])))
      (throw (ex-info "Permission denied"
                      {:etype  etype
                       :entity entity})))
    ret))

(sd/defn create!
  "Given the environment `env`, creates the `entity` of type `etype` in the database.
  Returns the given `entity` containing the new id and transaction id.
  Shape of `env`:
  {:jdbc    database specification suitable for use with next.jdbc
   :config  specomatic config
   :user    {:id           user id
             :permissions  sequence of permissions
             :root?        if user is root}}"
  ^map? [^::sp/env env ^::sp/etype etype ^map? entity]
  (let [{:keys [jdbc user]} env]
    (when-not (s/valid? etype
                        entity)
      (throw (let [expl (s/explain-str etype
                                       entity)]
               (ex-info (str "Invalid entity: " expl)
                        {:etype   etype
                         :entity  entity
                         :explain expl}))))
    (when-not (or (:root? user)
                  (ac/allowed-some? user :verb/create etype))
      ;; We are not allowed to create any of the `etype` entities
      (throw (ex-info "Permission denied"
                      {:etype  etype
                       :entity entity})))
    (jdbc/with-transaction
     [trans jdbc]
     (create* (assoc env :jdbc trans) etype entity))))

(defn- update*
  [{:keys [jdbc user]
    :as   env} etype entity id]
  (when-not (or (:root? user)
                (ac/allowed-some? user :verb/update etype))
    (throw (ex-info "Permission denied"
                    {:etype  etype
                     :entity entity})))
  (let [schema       (get-in env [:config :schema])
        my-entity    (extract-reference-ids schema etype entity)
        entity-id    (sc/id-field schema etype)
        cond-snippet (ac/conditions-snippet schema
                                            [etype]
                                            (get-in env [:user :permissions])
                                            :verb/update
                                            {:user-id    (:id user)
                                             :user-etype (get-in env [:config :user-etype])})
        result       (mutation/update! jdbc
                                       schema
                                       etype
                                       my-entity
                                       cond-snippet)]
    (if-let [tx-id (:tx/id result)]
      ;; Happy path
      (merge entity
             {entity-id id
              :tx/id    tx-id}
             (into {}
                   (for [[field field-def] (sc/field-defs schema etype)
                         :let              [v (field my-entity)]
                         :when             (and v (sdf/save-related? field-def))]
                     [field
                      (save-related! env
                                     field
                                     field-def
                                     v
                                     {:outer-etype etype
                                      :outer-id    id})])))
      ;; The statement didn't return anything, let's check why
      (when (and cond-snippet (by-id env etype id [entity-id]))
        ;; We are only allowed to update some of the `etype` entities
        ;; AND we are allowed to read the entity, so it must be due to permissions
        (throw (ex-info "Permission denied"
                        {:etype  etype
                         :entity entity}))))))

(sd/defn update!
  "Given the environment `env`, updates the `entity` of type `etype` in the database.
  Returns the given `entity` containing the transaction id.
  Shape of `env`:
  {:jdbc    database specification suitable for use with next.jdbc
   :config  specomatic config
   :user    {:id           user id
             :permissions  sequence of permissions
             :root?        if user is root}}"
  ^map? [^::sp/env env ^::sp/etype etype ^map? entity]
  (let [{:keys [jdbc user]} env
        entity-id           (sc/id-field (get-in env [:config :schema]) etype)]
    (when-not (s/valid? (s/keys)
                        entity)
      (throw (let [expl (s/explain-str etype
                                       entity)]
               (ex-info (str "Invalid changeset: " expl)
                        {:etype   etype
                         :entity  entity
                         :explain (s/explain-str (s/keys)
                                                 entity)}))))
    (when-not (or (:root? user)
                  (ac/allowed-some? user :verb/update etype))
      ;; We are not allowed to update any of the `etype` entities
      (throw (ex-info "Permission denied"
                      {:etype  etype
                       :entity entity})))
    (jdbc/with-transaction
     [trans jdbc]
     (update* (assoc env :jdbc trans) etype entity (entity-id entity)))))

(sd/defn save!
  "Given the environment `env`, saves the `entity` of type `etype` into the database.
  Tries to create the entity if its id field is nonexistent or nil.
  Tries to update the entity if it has an id.
  Returns the given `entity` containing the new id (if created) and transaction id.
  Shape of `env`:
  {:jdbc    database specification suitable for use with next.jdbc
   :config  specomatic config
   :user    {:id           user id
             :permissions  sequence of permissions
             :root?        if user is root}}"
  ^map? [^::sp/env env ^::sp/etype etype ^map? entity]
  (let [entity-id       (sc/id-field (get-in env [:config :schema]) etype)
        entity-id-value (entity-id entity)
        new?            (nil? entity-id-value)]
    (if new?
      (create! env etype entity)
      (update! env etype entity))))

(defn- delete*
  [{:keys [config jdbc user]
    :as   env} etype id]
  (let [{:keys [schema user-etype]} config
        cond-snippet                (ac/conditions-snippet schema
                                                           [etype]
                                                           (:permissions user)
                                                           :verb/delete
                                                           {:user-id    (:id user)
                                                            :user-etype user-etype})
        result                      (mutation/delete! jdbc
                                                      schema
                                                      etype
                                                      id
                                                      cond-snippet)]
    (if (:tx/id result)
      ;; Happy path
      (dissoc result :tx/ts)
      ;; The statement didn't return anything, let's check why
      (when (and cond-snippet (by-id env etype id [(sc/id-field (get-in env [:config :schema]) etype)]))
        ;; We are only allowed to delete some of the `etype` entities
        ;; AND we are allowed to read the entity, so it must be due to permissions
        ;; We are allowed to read the entity, so it must be due to permissions
        (throw (ex-info "Permission denied"
                        {:etype etype
                         :id    id}))))))

(sd/defn delete!
  "Given the environment `env`, delete the `entity` of type `etype`.
  Returns a map of id, :tx/id, nil if not found (might be due to permissions).
  Shape of `env`:
  {:jdbc    database specification suitable for use with next.jdbc
   :config  specomatic config
   :user    {:id           user id
             :permissions  sequence of permissions
             :root?        if user is root}}"
  [^::sp/env env ^::sp/etype etype id]
  (let [{:keys [jdbc user]} env]
    (when-not (or (:root? user)
                  (ac/allowed-some? user :verb/delete etype))
      ;; We are not allowed to delete any of the `etype` entities
      (throw (ex-info "Permission denied"
                      {:etype etype
                       :id    id})))
    (jdbc/with-transaction
     [trans jdbc]
     (delete* (assoc env :jdbc trans) etype id))))

(defmethod save-related! :default
  [_env _field _field-def _value _opts]
  nil)

(defmethod save-related! [::sf/reference :has-one]
  [env _field field-def value {:keys [outer-id]}]
  (let [target          (sf/target field-def)
        target-id       (sc/id-field (get-in env [:config :schema]) target)
        via             (sdf/db-via field-def)
        target-id-value (target-id value)]
    (if (::delete value)
      (when target-id-value
        (delete* env target target-id-value))
      (if target-id-value
        (merge value (update* env target value target-id-value))
        (merge value (create* env target (assoc value via outer-id)))))))

(defmethod save-related! [::sf/reference-coll :has-many]
  [env _field field-def value {:keys [outer-id]}]
  (let [target    (sf/target field-def)
        target-id (sc/id-field (get-in env [:config :schema]) target)
        via       (sdf/db-via field-def)]
    (->>
     (for [entity value
           :let   [target-id-value (target-id entity)]]
       (if (::delete entity)
         (when target-id-value
           (delete* env target target-id-value))
         (if target-id-value
           (merge entity (update* env target entity target-id-value))
           (merge entity (create* env target (assoc entity via outer-id))))))
     (filterv some?))))

(defmethod save-related! [::sf/reference-coll :has-many-through]
  [{:keys [jdbc]} _field field-def value {:keys [outer-id]}]
  (let [join-table                       (sdf/join-table field-def)
        [_ etype-fk target-fk target-id] (sdf/db-via field-def)]
    (->>
     (for [my-ref value
           :let   [target-id-value (if (map? my-ref)
                                     (target-id my-ref)
                                     my-ref)]
           :when  target-id-value]
       (let [table            (cnv/etype->table-name jdbc join-table nil)
             entity-fk-column (cnv/field->column-name jdbc etype-fk nil)
             target-fk-column (cnv/field->column-name jdbc target-fk nil)]
         (if (::delete my-ref)
           (do (db-generic/delete-reference-coll-element!
                 jdbc
                 {:table          table
                  :entity-id      outer-id
                  :entity-idfield entity-fk-column
                  :target-id      target-id-value
                  :target-idfield target-fk-column})
               nil)
           (do (sql/upsert-reference-coll-element! jdbc
                                                   {:table          table
                                                    :entity-id      outer-id
                                                    :entity-idfield entity-fk-column
                                                    :target-id      target-id-value
                                                    :target-idfield target-fk-column})
               my-ref))))
     (filterv some?))))
