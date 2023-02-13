(ns specomatic-db.seql
  "Contains all functions that work directly with the seql library: Provides schema generation and querying and registers transformations."
  (:require
   [clojure.set                  :as set]
   [clojure.walk                 :as walk]
   [seql.coerce                  :as seql-coerce]
   [seql.helpers                 :as sh]
   [seql.params                  :as params]
   [seql.query                   :as query]
   [seql.schema                  :as seql-schema]
   [specomatic-db.access-control :as ac]
   [specomatic-db.db.conversion  :as cnv]
   [specomatic-db.field-def      :as sdf]
   [specomatic-db.schema         :as db-schema]
   [specomatic-db.util           :as u]
   [specomatic.core              :as sc]
   [specomatic.etype-def         :as se]
   [specomatic.field-def         :as sf]
   [specomatic.util              :as su]))

(defmulti field-def
  "Returns a seql field definition for field `field` of `entity` with `spec`, given `entity-schema`."
  (fn [entity field specomatic-field-def entity-schema]
    (cond
      (= (sc/id-field entity-schema entity) field)
      :ident
      :else
      (:kind specomatic-field-def))))

(defmethod field-def :ident
  [_etype field _specomatic-field-def _entity-schema]
  (sh/field field (sh/ident)))

(defmethod field-def ::sf/simple
  [_etype field _specomatic-field-def _entity-schema]
  (sh/field field))

(defmethod field-def ::sf/reference
  [etype field specomatic-field-def entity-schema]
  (let [referenced-entity (sf/target specomatic-field-def)]
    (sh/has-one field
                (if (sf/inverse? specomatic-field-def)
                  [(sc/id-field entity-schema etype) (sdf/column-name specomatic-field-def)]
                  [(sdf/column-name specomatic-field-def) (sc/id-field entity-schema referenced-entity)]))))

(defmethod field-def ::sf/reference-coll
  [etype field specomatic-field-def entity-schema]
  (if (= :has-many (:reference-type specomatic-field-def))
    (sh/has-many field [(sc/id-field entity-schema etype) (sdf/db-via specomatic-field-def)])
    (sh/has-many-through field
                         (sdf/db-via specomatic-field-def))))

(defn set-transform!
  "Registers transformation functions (readers) for the given `entity-schema`."
  [entity-schema]
  (doseq [etype (keys entity-schema)
          :let  [fields (sc/field-defs entity-schema etype)]]
    (doseq [[field my-field-def] fields
            :when                (and field-def
                                      (not (sf/relational? my-field-def)))]
      (seql-coerce/with-reader! field
                                (fn [x _] (cnv/db-field-value->entity-field-value (sf/dispatch my-field-def) x))))))

(defn- join-table-seql-entities
  [schema query-table-keyword]
  (let [ref-colls-by-etype (sc/reference-colls-by-owning-etype schema)]
    (for [[_ ref-colls] ref-colls-by-etype]
      (for [[_ my-field-def] ref-colls
            :let             [join-table (sdf/join-table my-field-def)]]
        (sh/entity [(su/strip-ns join-table) (query-table-keyword join-table nil)]
                   (sh/field (sdf/join-table-id-field my-field-def) (sh/ident)))))))

(defn- predicate-relation
  [schema etype predicate]
  (let [my-view-name (ac/view-name-seql predicate etype)]
    (sh/has-many (keyword my-view-name)
                 [(sc/id-field schema etype)
                  (keyword
                   my-view-name
                   (name (db-schema/default-fk-column schema etype)))])))

(defn- views-seql-entities
  "Returns the access control views as seql entity definitions."
  [etype predicates-by-etype
   {:keys [schema]
    :as   config}]
  (let [user-etype (:user-etype config)]
    (for [predicate (etype predicates-by-etype)]
      (sh/entity
       [(keyword (ac/view-name-seql predicate etype)) (keyword (ac/view-name predicate etype))]
       (sh/field (db-schema/default-fk-column schema etype) (sh/ident))
       (sh/field (db-schema/default-fk-column schema user-etype))))))

(defn- entities
  "Returns a sequence of seql entity definitions for the given `config`.
  Optionally accepts a `query-table-keyword` function parameter for mapping entity keywords to table / view keywords for querying (default: `cnv/etype->query-table-keyword`)"
  ([config]
   (entities config cnv/etype->query-table-keyword))
  ([config query-table-keyword]
   (let [{:keys [ac-predicates schema schema-components]} config
         predicates-by-etype                              (apply merge-with
                                                                 into
                                                                 (into
                                                                  (for [[predicate predicate-defs] ac-predicates]
                                                                    (into {}
                                                                          (for [[etype _] schema
                                                                                :when     (etype
                                                                                           predicate-defs)]
                                                                            [etype [predicate]])))))]
     (reduce
      into
      ; specomatic schema entities
      (into
       (for [etype (sc/etypes schema)
             :let  [etype-def  (sc/etype-def schema etype)
                    field-defs (se/field-defs etype-def)]]
         (into
          [(apply
            sh/entity
            (reduce
             into
             [[[(su/strip-ns etype) (query-table-keyword etype etype-def)]
               (sh/inline-condition :where [condition] condition)]
              (for [[field specomatic-field-def] field-defs]
                (field-def etype field specomatic-field-def schema))
              (for [[field specomatic-field-def] field-defs]
                (sh/column-name field (or (sdf/column-name specomatic-field-def) field)))
              (for [predicate (etype predicates-by-etype)]
                (predicate-relation schema etype predicate))
              (etype schema-components)]))]
          (views-seql-entities etype predicates-by-etype config)))
       (join-table-seql-entities schema query-table-keyword))))))

(defn- schema*
  [config]
  (apply
   sh/make-schema
   (entities config)))

(defn- schema-historic*
  [config]
  (apply
   sh/make-schema
   (entities config cnv/etype->historic-query-table-keyword)))

(def schema
  "Returns a seql schema for the given specomatic `config`."
  (memoize schema*))

(def schema-historic
  "Returns a seql schema for the given specomatic `config`."
  (memoize schema-historic*))

(defn- translate-conditions
  "Converts the keywords in the `conditions` from specomatic keywords to keywords usable for seql conditions, according to `entity-schema`.
  Optionally accepts a `query-table-keyword` function parameter for mapping entity type keywords to table / view keywords for querying (default: `cnv/etype->query-table-keyword`)."
  ([param-schema conditions]
   (translate-conditions param-schema conditions cnv/etype->query-table-keyword))
  ([param-schema conditions query-table-keyword]
   (walk/postwalk #(if (and (keyword? %) (namespace %))
                     (let [simple-etype    (keyword (namespace %))
                           etype           (db-schema/etype-from-simple-keyword param-schema simple-etype)
                           seql-view-field (su/qualify
                                             (if etype
                                               (query-table-keyword etype (sc/etype-def param-schema etype))
                                               (query-table-keyword simple-etype nil))
                                             (su/strip-ns
                                               (if-let [my-field-def (sc/field-def param-schema %)]
                                                 (sdf/column-name my-field-def)
                                                 %)))]
                       (keyword (str (namespace seql-view-field) "." (name seql-view-field))))
                     %)
                  conditions)))

(defn query
  "Returns a vector of [`seql-etype` `fields` `conditions` `allowed-or-preds-by-verb`] representing a seql query for retrieving a sequence of `etype` entities containing the seql-style `fields` and matching the HoneySQL `conditions`, as well as how to determine the value of each access control verb (`allowed-or-preds-by-verb`).
  Optionally accepts a `query-table-keyword` function parameter for mapping entity type keywords to table / view keywords for querying (default: `cnv/etype->query-table-keyword`)
  Shape of `env`:
  {:config  specomatic config}"
  ([env etype fields conditions]
   (query env etype fields conditions cnv/etype->query-table-keyword))
  ([env etype fields conditions query-table-keyword]
   (let [seql-etype (su/strip-ns etype)
         {:keys [config user]} env
         {my-schema  :schema
          user-etype :user-etype}
         config
         verbs
         (filter #(and (keyword? %)
                       (= "verb" (namespace %)))
                 fields)
         allowed-or-preds-by-verb
         (zipmap verbs
                 (map
                  #(if (ac/allowed-all? user % etype)
                     true
                     (ac/sufficient-predicates etype % (get-in env [:user :permissions])))
                  verbs))
         my-fields-without-verbs (u/fields-without-verbs fields)
         preds (->> allowed-or-preds-by-verb
                    vals
                    (filter set?)
                    (apply set/union))
         pred-fields (for [pred preds]
                       {(keyword (name seql-etype)
                                 (ac/view-name-seql pred etype))
                        [(keyword (ac/view-name-seql pred etype)
                                  (name (db-schema/default-fk-column my-schema etype)))]})
         user-id (get-in env [:user :id])
         ac-conditions (for [pred preds]
                         [:or
                          [:=
                           (keyword (str
                                     (ac/view-name-seql pred etype)
                                     "."
                                     (name (db-schema/default-fk-column my-schema user-etype))))
                           user-id]
                          [:=
                           (keyword (str
                                     (ac/view-name-seql pred etype)
                                     "."
                                     (name (db-schema/default-fk-column my-schema user-etype))))
                           nil]])
         seql-fields my-fields-without-verbs]
     [seql-etype
      (into seql-fields pred-fields)
      (mapv (fn [condition] [(keyword (name seql-etype) "where") condition])
            (concat (translate-conditions my-schema conditions query-table-keyword)
                    ac-conditions))
      allowed-or-preds-by-verb])))

(defn- relation-query-tables
  "Returns a collection of tables used for the given `join`."
  [arg-schema join]
  (let [resolved     (seql-schema/resolve-relation arg-schema join)
        query-tables #{(:remote-entity resolved)}]
    (if-let [intermediate (:intermediate resolved)]
      (conj query-tables intermediate)
      query-tables)))

(defn query-tables
  "Returns a collection of tables used in given query."
  [config etype fields]
  (let [my-schema
        (schema config)

        {:keys [table joins]}
        (params/for-query my-schema
                          (su/strip-ns etype)
                          (u/fields-without-verbs fields)
                          [])]
    [table
     (reduce into
             (map (partial relation-query-tables my-schema)
                  joins))]))

(defn execute-query
  "Executes query via seql."
  [env etype fields conditions]
  (query/execute env
                 etype
                 fields
                 conditions))
