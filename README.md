# specomatic-db

Define your entities and relationships using [clojure.spec](https://clojure.org/guides/spec) and / or [specomatic](https://github.com/primeteach/specomatic), get an immutable SQL database that understands [seql](https://github.com/exoscale/seql) and supports access control.

## Introduction

Specomatic-db builds on the abstract base library [specomatic](https://github.com/primeteach/specomatic). The core concept is the specomatic-db schema that extends the specomatic schema with persistence-specific information.

From the schema, specomatic-db creates an immutable SQL database (with full historisation) and a [seql](https://github.com/exoscale/seql) schema. Entities and their history are retrieved using seql and persisted with a CRUD-flavored mutation system. Both retrieving and persisting can be restricted by attribute- and role-based access control rules.

## Design goals

* Use [clojure.spec](https://clojure.org/guides/spec) definitions as the basis of the schema
* Liberation from repetitive parts of SQL for CRUD as well as schema migrations
* Make it easy to use plain SQL where necessary
* Cross-RDBMS historisation
* Cross-RDBMS access control
* Support existing database schemas

## Overview

`specomatic-db.core` is the main namespace for consumers of specomatic-db. It contains functions for retrieving and persisting entities, as well as initialising the database.

The functions in the `specomatic-db.registry` namespace query the clojure.spec registry (via specomatic) to generate the schema.

The functions in the `specomatic-db.schema`, `specomatic-db.etype-def` and `specomatic-db.field-def` namespaces are pure functions that take the schema or parts of it as a first parameter and answer questions about it. These namespaces are extensions of `specomatic.core`, `specomatic.etype-def` and `specomatic.field-def`.

PostgreSQL and (for historical reasons) Firebird are supported. Other backends can be added by implementing the multimethods in `specomatic-db.db.migration`, `specomatic-db.db.mutation` and `specomatic-db.db.sql`.

## Defining the schema

The main way to define entity types is by using clojure.spec and the `defent` macro. See [the section on entity types in the specomatic README](https://github.com/primeteach/specomatic#entity-types) for a more detailed explanation of `defent` and other ways to define entity types.

Relationships are specified using relational fields, mainly via `specomatic.spec/reference` and `specomatic.spec/reference-coll` macros. For a more detailed explanation and other ways to define relationships, see [the section on relationships in the specomatic README](https://github.com/primeteach/specomatic#relationships)

The following example schema defines some entities and relationships for the cinema domain.

```clojure
(s/def :spec/review-stars (s/int-in 1 6))
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
```

## Setup

To work with this, construct an environment and initialize the database:

```clojure
(require '[specomatic-db.core :as sdb])
(require '[specomatic-db.registry :as sdb-registry])

;; Query the spec registry to derive a specomatic config (including the specomatic-db schema as its main part)
;; from the above specs

(def config (sdb-registry/config ['schema-ns]))

(def env
  {:jdbc next-jdbc-connectable
   :config config
   :user {:root? true}) ; skip access control

;; Validate the schema and initialize the database, applying all necessary migrations

(sdb/init! env)
```

## Migrations

The above `sdb/init!` has already applied migrations and a table exists for every entity type in the the database. This can be skipped by passing `{:skip-migration? true}` as a second argument. If corresponding tables already exist in the database, any missing fields are created. Nothing is removed from the database schema.

Instead of directly applying migrations on initialisation, they can be generated from `migration/diff-schema`
```clojure
(require '[specomatic-db.db.migration :as migration])

(migration/diff-schema next-jdbc-map (:schema config))
```
This returns a map of entity types as keys and maps of `:constraints sqlvecs :main sqlvecs}` as values.

PostgreSQL and Firebird drivers are implemented.

## Queries

For querying, specomatic-db builds on the excellent [seql](https://github.com/exoscale/seql) library.

The following examples use the data from the `specomatic-db.core-test` integration tests, inserted by `insert-all!`.

```clojure
(sdb/query env ::reviewer) ;; default fields, no conditions

=> (#:reviewer{:name "Jane", :id 1} #:reviewer{:name "John", :id 2})

(sdb/query env ::reviewer [:reviewer/name]) ;; restrict fields

=> (#:reviewer{:name "Jane"} #:reviewer{:name "John"})

(sdb/query env
          ::movie
          ;; more interesting seql to combine multiple relationships
          [:movie/title :movie/release-year {:movie/directors [:director/name {:director/user [:user/username]}]}
            {:movie/actors [:actor/name]}]
          ;; a vector of HoneySQL conditions, automatically joined by :and
          [[:= :director/name ["The Wachowskis"]]
           [:and [:< :movie/release-year 2000]
                 [:like :movie/title "%Matrix%"]]]

=> (#:movie{:title "The Matrix",
            :release-year 1999,
            :directors [#:director{:name "The Wachowskis",
                                   :user #:user{:username "the-wachowskis"}}],
            :actors    [#:actor{:name "Keanu Reeves"}
                        #:actor{:name "Laurence Fishburne"}]})

=> (#:reviewer{:name "Jane", :id 1} #:reviewer{:name "John", :id 2})

;; Single entities can also be queried by id:

(sdb/by-id env ::schema/reviewer 1 [:reviewer/name])

=> #:reviewer{:name "Jane"}
```

## Mutations

Mutations are performed by `create!`, `update!`, and `delete!`.

`save!` calls `update!` if the entity has a non-nil id, `create!` if it has not.

Mutations always return the transaction id `:tx/id` (see Historisation)

```clojure
(sdb/create! env ::schema/user {:user/username "robert"})

=> {:user/username "robert", :user/id 5, :tx/id 18}

(sdb/update! env ::schema/user {:user/id 5 :user/username "bob"})

=> {:user/id 5, :user/username "bob", :tx/id 19}

(sdb/delete! env ::schema/user 5)

=> {:id 5, :tx/id 20}
```

### Nested mutations

`create!` and `update!` can save contents of relational fields.

Only uncomplicated cases, where the foreign key resides in the nested entity, are handled.

```clojure
(sdb/save! env ::schema/review #:review{:movie      5 ;; movie has to exist before review, no nested mutation
                                        :reviewer   1 ;; reviewer has to exist before review, no nested mutation
                                        :title      "Highly recommend"
                                        :stars      5
                                        :paragraphs [{:paragraph/content "Awesome."}
                                                     {:paragraph/content "Just awesome."}]}
=>

{:review/movie 6,
 :review/reviewer 1,
 :review/title "Highly recommend",
 :review/stars 5,
 :review/paragraphs
 [{:paragraph/content "Awesome.",
   :paragraph/review 1,
   :paragraph/id 1,
   :tx/id 21}
  {:paragraph/content "Just awesome.",
   :paragraph/review 1,
   :paragraph/id 2,
   :tx/id 21}],
 :review/id 1,
 :tx/id 21}
```

Nested mutations run in the same database transation as the outer entity, guaranteeing consistency.

This functionality can be extended via the `save-related!` multimethod.

## Historisation

In addition to the main table corresponding to an entity type, the migration system creates an associated history table that is populated via triggers with all historical versions of the records. This means that historisation is automatically applied to any mutations, whether done via specomatic-db or plain SQL.

Historical versions can be queried by adding a `:tx/id` to the environment, like this:

```clojure
(sdb/query (assoc env :tx/id 18) ::schema/user [:user/username] [:user/id 5])

=> (#:user{:username "robert"})

(sdb/query (assoc env :tx/id 19) ::schema/user [:user/username] [:user/id 5])

=> (#:user{:username "bob"})

(sdb/query (assoc env :tx/id 20) ::schema/user [:user/username] [:user/id 5])

=> ()

```

## Access control

Access control is governed by permissions and predicates.

Permissions are assigned to individual users and define the operations (verbs) users are allowed to carry out on entities of certain types. They can be conditional on access control predicates.

Permissions consist of a `:permission/verb`, `:permission/obj` and `:permission/pred`.
* `:permission/verb` Can represent any operation on an entity. CRUD verbs: `:verb/read`, `:verb/create`, `:verb/update`, `:verb/delete` govern access control for the respective operations in specomatic-db. The special verb `:verb/*` is a shorthand for all CRUD verbs. For other operations e.g. more complex mutations, other verbs like `:verb/import` could be defined.
* `:permission/obj` is the entity type in your schema the permission applies to
* `:permission/pred` is either `:predicate/none` if the permission is unconditional or refers to an access control predicate.

For example, the following permission map represents an unconditional permission to read movies from the database:

```clojure
{:permission/verb :verb/read
 :permission/obj  ::schema/movie
 :permission/pred :predicate/none}
```

While the following permission map represents an permission to carry out any CRUD operations on movies, provided they satisfy the `:predicate/director` predicate.

```clojure
{:permission/verb :verb/*
 :permission/obj  ::schema/movie
 :permission/pred :predicate/director}
```

Such a predicate is defined by way of a HoneySQL query that defines the relationship of the restricted entity `::schema/movie` to the user entity `::schema/user`:

```clojure
(def director-predicate
  {::schema/movie {:select [[:movie.id :movieid] [:user_.id :userid]]
                   :from   [:movie]
                   :join   [:moviedirector [:= :movie.id :moviedirector.movieid]
                            :director [:= :moviedirector.directorid :director.id]
                            :user_ [:= :director.id :user_.directorid]]}})
```

This has the effect of restricting the permission to the director's own movies.

For setting up access control, create a base config with predicates under the `:ac-predicates` key and the entity type that acts as the user entity under `:user-etype`:

```clojure
(def base-config
 {:ac-predicates {:predicate/director director-predicate}
  :user-etype    ::schema/user})
```

This is passed as the second argument to `specomatic-db.registry/config` to create the final config.

```clojure
(def config (sdb-registry/config ['schema-ns] base-config))
```

User id and permissions are part of the environment:

```clojure
(def the-wachowskis-user-id
  (:user/id (first (sdb/query env ::user [:user/id] [[:= :user/username "the-wachowskis"]]))))

(def restricted-env
  {:jdbc next-jdbc-connectable
   :config config
   :user {:id the wachowskis-user-id
          :permissions #{{:permission/verb :verb/*
                          :permission/obj  ::schema/movie
                          :permission/pred :predicate/director}}}})
```

Directors can now only read their own movies:

```clojure
(sdb/query restricted-env ::schema/movie [:movie/title])

=> (#:movie{:title "The Matrix"}
    #:movie{:title "The Matrix Reloaded"}
    #:movie{:title "The Matrix Revolutions"})
```

### Access control for fields

Non-root users can only query the fields that are defined in the specomatic-db schema for the entities they are allowed to read. For root users, no such restriction exists: They can query for any field that exist in the database.

### Access control for conditions

Non-root users can only use the `#{:and :or := :!= :< :> :like :in}` HoneySQL conditions.

## Schema structure

Like the [specomatic schema](https://github.com/primeteach/specomatic#schema-structure), the specomatic-db schema is a map of entity types to entity type definitions:

```clojure
{::actor    ...
 ::director ...
 ::movie    ...
 ::review   ...
 ::user     ...}
```

### Entity type definitions

Specomatic-db entity type definitions extend the [specomatic entity type definitions](https://github.com/primeteach/specomatic#entity-type-definitions) with the persistence-specific `:table-name` and `:query-name`.

```clojure
{;; set of fields (keywords) that are part of the display name of the entity type.
 :display-name-fields #{:movie/title}}
 ;; field definitions, see below
 :field-defs           ...
 :id-field            :movie/id
 :required-fields     #{:movie/title :movie/release-year}
 ;; the name of the table used for persisting the entity, as a keyword
 :table-name          :movie
 ;; the name of the table or view used for querying the entity (usually the same as :table-name), as a keyword
 :query-name          :movie}
```

### Field type definitions

Specomatic-db field type definitions extend the [specomatic field type definitions](https://github.com/primeteach/specomatic#field-type-definitions) with the persistence-specific `:column-name`, `:db-via`, `:join-table`, `join-table-id-field`, `:not-persistent?`, `:owns-relation?` and `save-related?`.

For example, the definition of the simple (non-relational) field `:review/stars` looks like this:

```clojure
{;; the database column used for the field, usually the same as the field, but could be overridden
 :column-name :review/stars
 :kind ::sf/simple
 :dispatch :spec/review-stars}
```

While the definition of `:review/paragraphs` looks like this:

```clojure
{;; the database column on the opposite side of the relationship, if available.
 ;; Usually the same as :via, but may be overriden
 :db-via         :paragraph/review
 :kind           ::sf/reference-coll
 :inverse-of     :paragraph/review
 :reference-type :has-many
 :target         ::schema/paragraph
 :via            :paragraph/review
 ;; Indicates whether the entity type that the field definition is part of owns the relationship
 :owns-relation? false
 ;; Indicates whether contents of the field should be saved (created or updated) with the entity
 :save-related?  true}
```

## Mapping from field specs to SQL data types

This mapping is provided by implementing the following multimethods:

`specomatic-db.db.migration/sql-type` defines the SQL data type to use for a certain database backend and spec keyword or description (see `specomatic-core.field-def/dispatch`).

```clojure
(defmethod migration/sql-type ["firebirdsql" :spec/review-stars] [_ _] "SMALLINT")
```

The following mapping is built in for both implemented database backends:

`::sp/integer` => "integer"

`'integer?`     => "integer"

`'string?`      => "varchar(255)"

`specomatic-db.db.migration/db-field-value->entity-field-value-impl` coerces a database value into an entity value for a certain spec keyword or description (see `specomatic-core.field-def/dispatch`).

`specomatic-db.db.migration/entity-field-value->db-field-value-impl` coerces a entity value into an database value for a certain spec keyword or description (see `specomatic-core.field-def/dispatch`).

## Acknowledgements

* Historisation is inspired by this article from Bert Wagner https://bertwagner.com/posts/faking-temporal-tables-with-triggers/

## Plans

* Support non-integer ids.
* Migrations: Create foreign keys at the end to reduce dependency on order
* Move non-database related parts of specomatic-db access control to specomatic
* Maybe: Use Postgres Temporal Tables extension
* Maybe: Use Postgres row-level security
* Maybe: Make access control for conditions extensible
