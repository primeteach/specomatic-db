(ns specomatic-db.access-control
  "Functions that provide all aspects of access control of specomatic.db: Permissions, access predicates and associated SQL views."
  (:require
   [clojure.string       :as str]
   [honey.sql            :as sql]
   [next.jdbc            :as jdbc]
   [pandect.algo.md5     :refer [md5]]
   [specomatic-db.db.firebird.sql]
   [specomatic-db.db.postgres.sql]
   [specomatic-db.db.sql :as db-sql]
   [specomatic-db.schema :as schema]
   [specomatic-db.util   :as u]
   [specomatic.core      :as sc]
   [specomatic.util      :as su]))

(def
  ^{:doc     "All verbs that affect CRUD permissions."
    :no-doc  true
    :private true}
  crud-verbs
  [:verb/read :verb/create :verb/update :verb/delete :verb/*])

(def
  ^{:doc     "All CRUD verbs (without the wildcard verb/*) as a set."
    :no-doc  true
    :private true}
  crud-verbs-set
  (disj (set crud-verbs) :verb/*))

(defn ^:no-doc view-name
  "Returns the SQL identifier of the access control view for given predicate and entity type (keywords)"
  [pred etype]
  (let [my-name (str "ac_" (str/replace (name pred) "-" "_") "_" (str/replace (name etype) "-" "_"))]
    (if (> (count my-name) 31)
      (str "ac_" (subs (md5 my-name) 0 28))
      my-name)))

(defn ^:no-doc view-name-seql
  "Returns the seql entity key of the access control view for given predicate and entity type (keywords)"
  [pred etype]
  (str/replace (view-name pred etype) "_" "-"))

(defn ^:no-doc view-sql
  "Returns the SQL DDL for creating / altering an access control predicate view."
  [db {:keys [schema user-etype]} pred etype pred-def]
  (let [user-fk  (schema/default-fk-column schema user-etype)
        etype-fk (schema/default-fk-column schema etype)]
    (db-sql/create-or-replace-view
     db
     {:name  (view-name pred etype)
      :query (-> {:select [[(sc/id-field schema user-etype) user-fk]
                           [(sc/id-field schema etype) etype-fk]]
                  :from   [(su/strip-ns etype)]}
                 (merge pred-def)
                 sql/format
                 first)})))

(defn ^:no-doc views-sql
  "Returns a sequence of SQL views, one for every predicate and schema entity that has an access control predicate query"
  [db
   {:keys [ac-predicates]
    :as   config}]
  (reduce into
          (for [[pred pred-defs-by-etype] ac-predicates]
            (for [[etype pred-def] pred-defs-by-etype]
              (view-sql db config pred etype pred-def)))))

(defn ensure-views-exist!
  "Ensures an access control view exists in the database for every entity in the entity-schema and every predicate in predicates"
  [db config]
  (doseq [ddl (views-sql db config)]
    (jdbc/execute! db ddl)))

(defn root?
  "Checks if `user` is a root user."
  [user]
  (or (:root? user) (true? user)))

(defn allowed-all?
  "Checks if the user can do what the verb describes with all entities of this type"
  [user verb etype]
  (boolean
   (let [verbs (if (contains? crud-verbs-set verb)
                 #{:verb/* verb}
                 #{verb})]
     (or (root? user)
         (some #(and
                 (contains? verbs (:permission/verb %))
                 (= etype (:permission/obj %))
                 (= :predicate/none (:permission/pred %)))
               (:permissions user))))))

(defn allowed-some?
  "Checks if the user can do what the verb describes with some entities of this type"
  [user verb etype]
  (boolean
   (let [verbs (if (contains? crud-verbs-set verb)
                 #{:verb/* verb}
                 #{verb})]
     (or (root? user)
         (some #(and
                 (some #{(:permission/verb %)} verbs)
                 (= etype (:permission/obj %)))
               (:permissions user))))))

(defn allowed?
  "Checks if the user can do what the verb describes with this entity of this type"
  [user verb etype entity]
  (boolean
   (let [verbs (if (contains? crud-verbs-set verb)
                 #{:verb/* verb}
                 #{verb})]
     (or (allowed-all? user verb etype)
         (some true?
               (for [v verbs]
                 (v entity)))))))

(defn may-read-some?
  "Checks if the given permissions allow read access to some entities of this type"
  [etype permissions]
  (boolean
   (some #(and
           (some #{(:permission/verb %)} #{:verb/read :verb/*})
           (= etype (:permission/obj %)))
         permissions)))

(defn- sufficient-predicates*
  "Returns a set of predicates where if any one is true for an entity of this type then it may be `verb`ed given the permissions"
  [etype verb permissions]
  (set
   (map :permission/pred
        (filter #(and
                  (some #{(:permission/verb %)} #{verb :verb/*})
                  (= etype (:permission/obj %)))
                permissions))))

(defn sufficient-predicates
  "Returns a set of predicates where if any one is true for an entity of this type then it may be `verb`ed given the permissions"
  [etype verb permissions]
  (sufficient-predicates* etype verb permissions))

(defn etypes-extra-conditions
  "Returns a vector of extra HoneySQL conditions that need to be applied when retrieving the `etypes` for the user with `user-id` and `permissions`."
  [schema etypes permissions verb {:keys [user-id user-etype]}]
  (let [id-field (->> user-etype
                      (schema/default-fk-column schema)
                      su/strip-ns)
        conds    (reduce
                  into
                  (for [etype etypes
                        :let
                        [preds
                         (sufficient-predicates*
                          etype
                          verb
                          permissions)]
                        :when
                        (not-any? #(= % :predicate/none) preds)]
                    (for [pred preds]
                      [:exists
                       {:select [id-field]
                        :from   [(keyword (view-name pred etype))]
                        :where  [:and
                                 [:= id-field user-id]
                                 [:or
                                  [:=
                                   (u/honeysql-field (sc/id-field schema etype))
                                   nil]
                                  [:=
                                   (schema/default-fk-column schema etype)
                                   (u/honeysql-field (sc/id-field schema etype))]]]}])))]
    (if (not-empty conds)
      [(into
         [:and]
         conds)]
      [])))

(defn conditions-snippet
  "Returns a snippet / sqlvec suitable for composing with a HugSQL statement"
  [schema etypes permissions verb user-info]
  (let [my-cond (first (etypes-extra-conditions schema etypes permissions verb user-info))]
    (when (not-empty my-cond)
      (sql/format-expr my-cond))))

(defn fields-extra-read-conditions
  "Returns a sequence of extra HoneySQL conditions that need to be applied when retrieving the `fields` for the user with `user-id` and `permissions`."
  [schema fields permissions user-id user-etype]
  (let [etypes (u/etypes-from-fields schema fields)]
    (etypes-extra-conditions schema
                             etypes
                             permissions
                             :verb/read
                             {:user-id    user-id
                              :user-etype user-etype})))

(defn fields-forbidden-entities
  "Returns the seql entities that occur in `fields` and are forbidden to read given the `permissions`."
  [schema fields permissions]
  (not-empty
   (let [etypes (u/etypes-from-fields schema fields)]
     (remove #(may-read-some? % permissions)
             etypes))))

(defn fields-allowed?
  "Checks if the `permissions` give read access to all `fields`."
  [schema fields permissions]
  (nil? (fields-forbidden-entities schema fields permissions)))

(defn- hsql-field?
  [schema x permissions]
  (and (keyword? x)
       (sc/field-defined? schema x)
       (may-read-some? (sc/etype-from-field schema x) permissions)))

(defn- hsql-literal?
  [x]
  (not (or (coll? x)
           (keyword? x)
           (symbol? x))))

(defn- hsql-field-or-literal?
  [schema x permissions]
  (or (hsql-field? schema x permissions)
      (hsql-literal? x)))

(defn allowed-condition?
  "Checks if the condition is allowed."
  [schema condition permissions]
  (let [[op & args] condition]
    (cond (some #{op} #{:= :!= :< :> :like})
          (and (every? #(hsql-field-or-literal? schema % permissions) args) (= 2 (count args)))
          (= :in op)
          (and (every? #(or (hsql-field? schema % permissions)
                            (every? hsql-literal? %))
                       args)
               (= 2 (count args)))
          (some #{op} #{:and :or})
          (every? #(allowed-condition? schema % permissions) args))))

(defn forbidden-conditions
  "Returns a sequence of forbidden conditions in the HoneySQL conditions vector `conditions` (recursive), nil if none are forbidden."
  [schema conditions permissions]
  (not-empty
   (remove #(allowed-condition? schema % permissions)
           conditions)))

(defn concatenate-extra-conditions
  "Appends extra conditions to `conditions` restricting read access according to access control permissions."
  [env fields conditions]
  (let [{:keys [user config]}    env
        {:keys [id permissions]} user]
    (into
     (or conditions [])
     (when-not (root? user)
       (fields-extra-read-conditions (:schema config) fields permissions id (:user-etype config))))))

(defn check-query-arguments
  "Returns true if arguments to `specomation.core/query` are allowed, throws exceptions if not."
  [env fields conditions]
  (let [{:keys [user config]} env
        {:keys [schema]}      config]
    (if (root? user)
      true
      (if-let [my-forbidden-conditions (forbidden-conditions schema conditions (:permissions user))]
        (throw (ex-info "Access to conditions denied" {:conditions my-forbidden-conditions}))
        (if-let [forbidden-entities (fields-forbidden-entities schema
                                                               (u/fields-without-verbs fields)
                                                               (:permissions user))]
          (throw (ex-info "Access to entities denied" {:entities forbidden-entities}))
          (if-let [forbidden-fields (not-empty (remove #(sc/field-defined? schema %)
                                                       (u/flatten-fields (u/fields-without-verbs fields))))]
            (throw (ex-info "Access to fields denied" {:entities forbidden-fields}))
            true))))))
