-- MUTATIONS --
-- Inserting, changing and deleting of records.

-- :name insert! :<! :1
-- :doc Inserts a single record into the database. Takes `cols` and `vals` vectors, containing column names and values.
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
insert into :i:table (:i*:cols)
values (:v*:vals)
returning /*~ (identifier-param-quote (:id-field params) options) ~*/, (select txid from get_transaction(current_transaction)), (select txts from get_transaction(current_transaction))

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
returning /*~ (identifier-param-quote (:id-field params) options) ~*/, (select txid from get_transaction(current_transaction)), (select txts from get_transaction(current_transaction))

-- :name delete! :<! :1
-- :doc Delete a single record in `table` by `id`.
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
delete from :i:table
where /*~ (identifier-param-quote (:id-field params) options) ~*/ = :id
/*~ (when (:ac-conditions params)
" and (:snip:ac-conditions)") ~*/
returning /*~ (identifier-param-quote (:id-field params) options) ~*/, (select txid from get_transaction(current_transaction)), (select txts from get_transaction(current_transaction))

-- :name upsert-reference-coll-element! :! :n
update or insert into :i:table (:i:entity-idfield, :i:target-idfield)
values (:entity-id, :target-id)
matching (:i:entity-idfield, :i:target-idfield)

-- MIGRATIONS --

-- :snip column-def
/* :require [specomatic.spec :as-alias sp]
            [specomatic-db.db.migration :as migration]
            [specomatic-db.db.firebird.util :refer [firebirdsql]] */
:i:name
/*~ (condp = (:type params)
      ::sp/integer "integer"
      'integer? "integer"
      'string? "varchar(255)"
      'my-boolean? "char(1)"
      (migration/sql-type firebirdsql (:type params))) ~*/

-- :snip ref-column-def
:i:name integer

-- :name ensure-sequence-transaction :!
-- :doc ensure transaction sequence exists
execute block as begin
if (not exists(select 1 from rdb$generators where rdb$generator_name = 'TRANSACTION_')) then
execute statement 'create sequence transaction_;';
end

-- :name ensure-table-transaction :!
-- :doc ensure transaction table exists
execute block as begin
if (not exists(select 1 from rdb$relations where rdb$relation_name = 'TRANSACTION_')) then
execute statement 'create table transaction_ (
  txid integer not null primary key,
  txts timestamp,
  system_txid integer unique
)';
end;

-- :name clear-transaction-system-txid :!
-- :doc set all system txids to null
update transaction_ set system_txid = null

-- :name create-generic :!
-- :doc create table, generic
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
create table :i:table (
  :sql:column-defs,
/*~ (when (:join-table-unique-constraint params)
" unique (:i:join-table-unique-constraint.main-id, :i:join-table-unique-constraint.target-id),") ~*/
  primary key (/*~ (identifier-param-quote (:id-field params) options) ~*/)
)

-- :name create-generic-history :!
-- :doc create table for historic records, generic
/* :require [hugsql.parameters :refer [identifier-param-quote]] */
create table /*~ (identifier-param-quote (str (:table params) "_H") options) ~*/  (
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
create or alter procedure get_transaction
 (system_txid integer)
returns (txid integer, txts timestamp)
as
begin
  select txid, txts from transaction_ where system_txid = \:system_txid into \:txid, \:txts;
  if (\:txid is null) then begin
    txid = (select gen_id(transaction_, 1) from rdb$database);
    txts = (select cast('NOW' as timestamp) from rdb$database);
    insert into transaction_ (txid, txts, system_txid) values (\:txid, \:txts, \:system_txid);
  end
  suspend;
end


-- :name create-or-alter-history-trigger :!
-- :doc create update trigger for history recording, generic
/* :require [clojure.string :as string]
            [hugsql.parameters :refer [identifier-param-quote]] */
create or alter trigger /*~ (identifier-param-quote (str (:table params) "_H") options) ~*/
 active after insert or update or delete
 position 0
 on :i:table
as
  declare current_txid int;
  declare current_txts timestamp;
begin
  execute procedure get_transaction current_transaction returning_values \:current_txid, \:current_txts;
  if (inserting or updating) then begin
    update /*~ (identifier-param-quote (str (:table params) "_H") options) ~*/
    set txid_until = \:current_txid, txts_until = \:current_txts
    where /*~ (identifier-param-quote (:id-field params) options) ~*/ = new./*~ (identifier-param-quote (:id-field params) options) ~*/
    and  txid_until = 2147483647;
    insert into /*~ (identifier-param-quote (str (:table params) "_H") options) ~*/
    (txid_from, txid_until, txts_from, txts_until, mut, :i*:column-names)
    values
    (\:current_txid, 2147483647, \:current_txts, cast('9999-12-31' as timestamp), (case when inserting then 'I' when updating then 'U' end), /*~
(string/join ", "
  (for [column (:column-names params)]
    (str "new." (identifier-param-quote column options))))
~*/);
  end
  if (deleting) then begin
    update /*~ (identifier-param-quote (str (:table params) "_H") options) ~*/
    set txid_until = \:current_txid, txts_until = \:current_txts
    where /*~ (identifier-param-quote (:id-field params) options) ~*/ = old./*~ (identifier-param-quote (:id-field params) options) ~*/
    and  txid_until = 2147483647;
    insert into /*~ (identifier-param-quote (str (:table params) "_H") options) ~*/
    (txid_from, txid_until, txts_from, txts_until, mut, :i*:column-names)
    values
    (\:current_txid, 2147483647, \:current_txts, cast('9999-12-31' as timestamp), 'D', /*~
(string/join ", "
  (for [column (:column-names params)]
    (str "old." (identifier-param-quote column options))))
~*/);
  end
end

-- :name create-pk-sequence :!
-- :doc create primary key sequence for a given table
create sequence :i:table

-- :name create-pk-sequence-trigger :!
-- :doc create primary key sequence trigger, generic
create trigger /*~ (let [trigger-name (str "TR_" (:id-field params) "_" (:table params))]
  (identifier-param-quote (subs trigger-name 0 (min 31 (count trigger-name))) options)) ~*/
  for :i:table active
before insert position 0
as begin if (new./*~ (identifier-param-quote (:id-field params) options) ~*/ is null) then new./*~ (identifier-param-quote (:id-field params) options) ~*/ = gen_id(:i:table, 1); if (new./*~ (identifier-param-quote (:id-field params) options) ~*/ <= 0) then new./*~ (identifier-param-quote (:id-field params) options) ~*/ = gen_id(:i:table, 1); end

-- :name alter-table-add-foreign-key :!
-- :doc add foreign key to table
alter table :i:table add constraint :i:constraint-name foreign key (:i:column) references :i:target
/*~ (when (:cascade? params)
" on update cascade on delete cascade") ~*/

-- :name select-all-constraints
-- :doc select constraint info from firebird
select rdb$constraint_name from rdb$relation_constraints

-- :name select-all-tables
-- :doc select table info from firebird
select rdb$relation_name from rdb$relations

-- :name select-fields
-- :doc select field info from firebird
select rdb$relation_fields.rdb$field_name from rdb$relation_fields
join rdb$fields on rdb$relation_fields.rdb$field_source = rdb$fields.rdb$field_name
where rdb$relation_name = :table-name

-- ACCESS CONTROL --

-- :name create-or-replace-view :!
create or alter view
:sql:name
as
:sql:query
