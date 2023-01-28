-- MUTATIONS --
-- Inserting, changing and deleting of records.

-- :name insert! :<! :1
-- :doc Inserts a single record into the database. Takes `cols` and `vals` vectors, containing column names and values.
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
insert into :i:table (:i*:cols)
values (:v*:vals)
returning /*~ (identifier-param-quote (:id-field params) options) ~*/ AS id, (select txid from get_transaction(txid_current())), (select txts from get_transaction(txid_current()))

-- :name update! :<! :1
-- :doc Updates a single record in `table` by `id`.
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
update :i:table set
/*~
(string/join ","
  (for [[field _] (:updates params)]
    (str (identifier-param-quote (name field) options)
      " = :v:updates." (name field))))
~*/
where /*~ (identifier-param-quote (:id-field params) options) ~*/ = :id
/*~ (when (:ac-conditions params)
" and (:snip:ac-conditions)") ~*/
returning (select txid from get_transaction(txid_current())), (select txts from get_transaction(txid_current()))

-- :name delete! :<! :1
-- :doc Delete a single record in `table` by `id`.
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
delete from :i:table
where /*~ (identifier-param-quote (:id-field params) options) ~*/ = :id
/*~ (when (:ac-conditions params)
" and (:snip:ac-conditions)") ~*/
returning /*~ (identifier-param-quote (:id-field params) options) ~*/, (select txid from get_transaction(txid_current())), (select txts from get_transaction(txid_current()))

-- :name upsert-reference-coll-element! :! :n
insert into :i:table (:i:entity-idfield, :i:target-idfield)
values (:entity-id, :target-id)
on conflict (:i:entity-idfield, :i:target-idfield) do nothing

-- MIGRATIONS --

-- :snip column-def
:i:name :sql:type

-- :snip ref-column-def
:i:name integer

-- :snip id-column-def
/* :require [specomatic.spec :as-alias sp] */
:i:name serial primary key

-- :name ensure-table-transaction :!
-- :doc ensure transaction table exists
create table if not exists transaction_ (
  txid serial primary key,
  txts timestamp,
  system_txid bigint unique
)

-- :name clear-transaction-system-txid :!
-- :doc set all system txids to null
update transaction_ set system_txid = null

-- :name create-generic
-- :command :execute
-- :result :raw
-- :doc create table, generic
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
create table :i:table (
  :sql:column-defs
/*~ (when (:join-table-unique-constraint params)
", unique (:i:join-table-unique-constraint.main-id, :i:join-table-unique-constraint.target-id)") ~*/
)

-- :name create-generic-history :!
-- :doc create table for historic records, generic
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
create table /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/  (
  txid_from integer,
  txid_until integer,
  txts_from timestamp,
  txts_until timestamp,
  mut char(1), -- [I]nsert, [U]pdate, [D]elete
  :sql:column-defs,
  primary key (/*~ (identifier-param-quote (:id-field params) options) ~*/, txid_from)
)


-- :name ensure-procedure-get-transaction :!
-- :doc ensure procedure for registering write transactions exists
create or replace function get_transaction
 (system_txid bigint, out txid integer, out txts timestamp)
as $$
#variable_conflict use_variable
begin
  select transaction_.txid, transaction_.txts into txid, txts from transaction_ where transaction_.system_txid = system_txid;
  if (txid is null) then
    insert into transaction_ (txts, system_txid) values (current_timestamp, system_txid) returning transaction_.txid,  transaction_.txts into txid,  txts;
  end if;
end $$
language plpgsql volatile;

-- :name create-or-replace-history-trigger-function :!
-- :doc create trigger function for history recording, generic
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
CREATE or replace function  /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/() RETURNS trigger AS $$
  declare current_txid int;
  declare current_txts timestamp;
BEGIN
  select txid, txts into current_txid, current_txts from get_transaction(txid_current());

 if (tg_op = 'INSERT' or tg_op = 'UPDATE') then
       update /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/
    set txid_until = current_txid, txts_until = current_txts
    where /*~ (identifier-param-quote (:id-field params) options) ~*/ = new./*~ (identifier-param-quote (:id-field params) options) ~*/
    and  txid_until = 2147483647;

    insert into /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/
    (txid_from, txid_until, txts_from, txts_until, mut, :i*:column-names)
    values
    (current_txid, 2147483647, current_txts, cast('9999-12-31' as timestamp), (case when tg_op = 'INSERT' then 'I' when tg_op = 'UPDATE' then 'U' end), /*~
(string/join ", "
  (for [column (:column-names params)]
    (str "new." (identifier-param-quote column options))))
~*/);
  end if;

  if tg_op = 'DELETE' then
    update /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/
    set txid_until = current_txid, txts_until = current_txts
    where /*~ (identifier-param-quote (:id-field params) options) ~*/ = old./*~ (identifier-param-quote (:id-field params) options) ~*/
    and  txid_until = 2147483647;
    insert into /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/
    (txid_from, txid_until, txts_from, txts_until, mut, :i*:column-names)
    values
    (current_txid, 2147483647, current_txts, cast('9999-12-31' as timestamp), 'D', /*~
(string/join ", "
  (for [column (:column-names params)]
    (str "old." (identifier-param-quote column options))))
~*/);
  end if;

  return null;
end;
$$
language plpgsql volatile

-- :name create-or-replace-history-trigger :!
-- :doc create trigger for history recording, generic
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
create trigger /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/
AFTER insert OR update OR delete
on /*~ (identifier-param-quote (:table params) options) ~*/
  FOR EACH ROW EXECUTE function /*~ (identifier-param-quote (str (:table params) "_h") options) ~*/()

-- :name alter-table-add-foreign-key :!
-- :doc add foreign key to table
alter table :i:table add constraint :i:constraint-name foreign key (:i:column) references :i:target
/*~ (when (:cascade? params)
" on update cascade on delete cascade") ~*/

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
