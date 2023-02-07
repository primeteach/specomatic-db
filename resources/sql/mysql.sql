-- MUTATIONS --
-- Inserting, changing and deleting of records.

-- :name get-last-insert-id
-- :doc Gets the last auto increment id after an insert.
SELECT LAST_INSERT_ID();

-- :name insert! :<!
-- :doc Inserts a single record into the database. Takes `cols` and `vals` vectors, containing column names and values.
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
INSERT INTO :i:table (:i*:cols)
VALUES (:v*:vals)

-- :name insert-history! :<!
-- :doc Inserts a single record into the database. Takes `cols` and `vals` vectors, containing column names and values.
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
INSERT INTO :i:table (:i*:cols)
VALUES (:v*:vals)

-- :name update! :<! :1
-- :doc Updates a single record in `table` by `id`.
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
UPDATE :i:table set
/*~
(string/join ","
  (for [[field _] (:updates params)]
    (str (identifier-param-quote (name field) options)
      " = :v:updates." (name field))))
~*/
WHERE /*~ (identifier-param-quote (:id-field params) options) ~*/ = :id
/*~ (when (:ac-conditions params)
" and (:snip:ac-conditions)") ~*/

-- :name delete! :<! :1
-- :doc Delete a single record in `table` by `id`.
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
DELETE FROM :i:table
WHERE /*~ (identifier-param-quote (:id-field params) options) ~*/ = :id
/*~ (when (:ac-conditions params)
" and (:snip:ac-conditions)") ~*/

-- :name upsert-reference-coll-element! :! :n
INSERT INTO :i:table (:i:entity-idfield, :i:target-idfield)
VALUES (:entity-id, :target-id)
ON CONFLICT (:i:entity-idfield, :i:target-idfield) do nothing

-- MIGRATIONS --

-- :snip column-def
:i:name :sql:type

-- :snip ref-column-def
:i:name integer

-- :snip id-column-def
/* :require [specomatic.spec :as-alias sp] */
:i:name INT AUTO_INCREMENT PRIMARY KEY

-- :name create-generic
-- :command :execute
-- :result :raw
-- :doc create table, generic
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
CREATE TABLE :i:table (
  :sql:column-defs
/*~ (when (:join-table-unique-constraint params)
", unique (:i:join-table-unique-constraint.main-id, :i:join-table-unique-constraint.target-id)") ~*/
)

-- :name alter-table-add-foreign-key :!
-- :doc add foreign key to table
ALTER TABLE :i:table ADD CONSTRAINT :i:constraint-name FOREIGN KEY (:i:column) REFERENCES :i:target(id)
/*~ (when (:cascade? params)
" ON UPDATE CASCADE ON DELETE CASCADE") ~*/

-- :name select-all-constraints
-- :doc select constraint info from postgres
select constraint_name from information_schema.referential_constraints

-- :name select-all-tables
-- :doc select table info from postgres
select table_name
from information_schema.tables
where table_schema not in ('information_schema', 'pg_catalog')
and table_type = 'BASE TABLE'

-- :name select-fields
-- :doc select field info from postgres
select column_name
from information_schema.columns
where table_schema not in ('information_schema', 'pg_catalog')
and table_name = :table-name

-- ACCESS CONTROL --

-- :name create-or-replace-view :!
create or replace view
:sql:name
as
:sql:query
